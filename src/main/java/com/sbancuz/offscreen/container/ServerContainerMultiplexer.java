package com.sbancuz.offscreen.container;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.server.S01PacketJoinGame;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sbancuz.offscreen.config.ContainerProtocolConfig;
import com.sbancuz.offscreen.container.ContainerAdapter.PayloadEffect;
import com.sbancuz.offscreen.container.ServerContainer.State;
import com.sbancuz.offscreen.container.context.ContextStack;
import com.sbancuz.offscreen.container.context.InboundPacketContext;
import com.sbancuz.offscreen.container.context.MultiplexerContext;
import com.sbancuz.offscreen.container.reopen.OpeningInteraction;
import com.sbancuz.offscreen.core.Driver;
import com.sbancuz.offscreen.mixins.early.MinecraftAccessor;
import com.sbancuz.offscreen.window.GuiGlobals;
import com.sbancuz.offscreen.window.HostedScreen;
import com.sbancuz.offscreen.window.MainWindow;
import com.sbancuz.offscreen.window.Window;

/**
 * The server keeps one open container per player; this multiplexes it between every container
 * GUI the client shows, in the main window and in hosted windows: only one of them, the active
 * container, is open on the server at a time; the others are paused snapshots that are reopened
 * when the player uses them. It is the single owner of what the client knows about the server's
 * open container and the only source of changes to it. Main thread only, except the read-only
 * checks the outbound packet and click hooks make for packets from other threads.
 * <p>
 * The model is the {@link #activeContainer} (proven to be the server's open container at this
 * point of the inbound stream) and the {@link Phase}. It owns the parts the hooks work through:
 * the {@link ContextStack} code runs in, the {@link MainWindow}, the {@link GatedViewInput} and
 * {@link GatedViewOverlay}, the
 * {@link ServerProbes} and the adapters. Every hook the mixins call is here.
 */
public final class ServerContainerMultiplexer {

    public static final Logger LOG = LogManager.getLogger("Offscreen/Containers");
    public static final ServerContainerMultiplexer INSTANCE = new ServerContainerMultiplexer();

    /** What is known about the server's open container. */
    enum Phase {
        /** a reset runs; the active container is a placeholder (the player inventory) */
        RESETTING,
        /** three resets failed; everything is gated until a retry succeeds */
        DEGRADED,
        /** the active container is proven and a procedure waits for the server */
        WAITING_FOR_SERVER,
        /** the active container is proven and nothing waits */
        IDLE
    }

    final Minecraft mc = Minecraft.getMinecraft();
    public final ContextStack contexts = new ContextStack(this);
    public final GuiGlobals globals = new GuiGlobals();
    public final MainWindow mainWindow = new MainWindow(this);
    public final GatedViewInput gatedInput = new GatedViewInput(this);
    public final GatedViewOverlay overlay = new GatedViewOverlay(this);
    /** Registered adapters, latest first. */
    private final List<ContainerAdapter> adapters = new CopyOnWriteArrayList<>();
    private ServerProbes probes = new ServerProbes(this);

    /** Set on join; until then, and after a disconnect, the multiplexer does nothing. */
    private boolean joined;
    /** The player inventory's record, rebuilt with the player object (join, respawn). */
    private ServerContainer playerInventory;
    private ServerContainer active;
    private Phase phase = Phase.RESETTING;
    private @Nullable ContainerSwitch runningSwitch;
    /** Containers the server opened that are not closed for good, by client container. */
    private final Map<Container, ServerContainer> openContainers = new IdentityHashMap<>();
    /** Client containers whose close logic already ran: it runs at most once. */
    private final Set<Container> closeLogicRan = Collections.newSetFromMap(new WeakHashMap<>());

    private final List<Runnable> deferred = new ArrayList<>();
    private boolean dead;
    private int packets, stall;

    private ServerContainerMultiplexer() {}

    /** On the main thread, at mod init. */
    public void init() {
        contexts.init();
        mainWindow.register();
        gatedInput.register();
    }

    public void registerAdapter(final ContainerAdapter adapter) {
        adapters.add(0, adapter);
    }

    // ================================================================== choke points (5)

    /** {@code Minecraft.displayGuiScreen}. */
    public void onDisplayGuiScreen(@Nullable final GuiScreen incoming, final Consumer<GuiScreen> vanilla) {
        if (!isInWorld()) vanilla.accept(incoming);
        else contexts.current()
            .displayScreen(incoming, vanilla);
    }

    /** {@code EntityClientPlayerMP.closeScreen} / {@code closeScreenNoPacket}. Returns true if handled. */
    public boolean onCloseScreen(final boolean packet) {
        if (!contexts.onMainThread() || !isInWorld()) return false;
        if (playerDead()) {
            onDeath(); // GuiContainer.updateScreen closes on death
            mainWindow.showUnrouted(null);
            return true;
        }

        contexts.current()
            .closeScreen(packet);
        return true;
    }

    /** One inbound play packet. */
    public void onInboundPacket(final Packet p, final Runnable handler) {
        packets++;
        if (p instanceof S01PacketJoinGame) {
            handler.run();
            onJoin();
            return;
        }

        if (isInWorld()) new InboundPacketContext(this, p).handle(handler);
        else handler.run();
    }

    /** One outbound packet: {@code send} lets it leave; the current context may drop it or add a follow-up. */
    public void onOutboundPacket(final Packet p, final Runnable send) {
        Runnable action = send;
        try {
            if (isInWorld()) action = contexts.current()
                .routeOutbound(p, send);
        } catch (final RuntimeException e) {
            LOG.error("[Containers] outbound routing failed; passing the packet", e);
        }
        action.run();
    }

    /** Whether a click or C11 for window {@code w} may be sent by the running code. */
    public boolean allowsClick(final int w) {
        return !isInWorld() || contexts.current()
            .allowsClick(w);
    }

    /** A handler changed the open container silently (ModularUI2 close and reopen packets): check after it. */
    public void requestReconcile() {
        if (isInWorld()) contexts.current()
            .requestReconcile();
    }

    /** Whether {@code GuiContainer.onGuiClosed} may run this container's close logic itself. */
    public boolean allowsClientCloseLogic(final Container c) {
        return !isInWorld() || containerOf(c) == null && !closeLogicRan.contains(c);
    }

    // ================================================================== model queries

    /** Whether the multiplexer runs: joined, with a player and a world. */
    public boolean isInWorld() {
        return joined && mc.thePlayer != null && mc.theWorld != null;
    }

    public ServerContainer activeContainer() {
        return active;
    }

    public boolean knowsServerState() {
        return phase == Phase.WAITING_FOR_SERVER || phase == Phase.IDLE;
    }

    /** The active container is known and nothing waits for the server. */
    public boolean isIdle() {
        return phase == Phase.IDLE;
    }

    public boolean isDegraded() {
        return phase == Phase.DEGRADED;
    }

    public boolean idleAtPlayerInventory() {
        return isIdle() && active.isPlayerInventory();
    }

    public boolean isSwitching() {
        return runningSwitch != null;
    }

    /** A switch to a container the server opened runs: what opens now is that container reopening. */
    private boolean isReopening() {
        return runningSwitch != null && !runningSwitch.to.isPlayerInventory();
    }

    public ServerContainer playerInventory() {
        return playerInventory;
    }

    public ServerProbes probes() {
        return probes;
    }

    /** The record of a client container: the inventory's, an open one's, or null for one the server does not know. */
    @Nullable
    ServerContainer containerOf(final Container c) {
        if (c == playerInventory.clientContainer) return playerInventory;
        return openContainers.get(c);
    }

    /** The container a screen shows; null for a free screen or none. */
    public @Nullable ServerContainer containerOf(@Nullable final GuiScreen s) {
        if (s instanceof GuiContainerCreative) return playerInventory;
        if (s instanceof GuiContainer gc) return containerOf(gc.inventorySlots);
        return null;
    }

    public boolean isFreeScreen(@Nullable final GuiScreen s) {
        return s != null && containerOf(s) == null;
    }

    /** Whether the running code acts for the active view; fixed at the start of a view's run. */
    public boolean fromActiveView() {
        return contexts.current()
            .actsForActiveView();
    }

    public boolean hasOpenContainerOf(final ContainerAdapter adapter) {
        for (final ServerContainer b : openContainers.values()) if (b.adapter == adapter) return true;
        return false;
    }

    /** ClientCloseLogicContext the inventory container on the server would drop nothing: empty cursor and 2x2 grid. */
    public boolean playerInventoryDropsNothing() {
        return cursorStack() == null && !playerInventory.wouldDropItemsOnClose();
    }

    /** The inventory is the idle active container, and closing it would drop nothing. */
    public boolean idleAtQuietPlayerInventory() {
        return idleAtPlayerInventory() && playerInventoryDropsNothing();
    }

    @Nullable
    ItemStack cursorStack() {
        return mc.thePlayer.inventory.getItemStack();
    }

    boolean playerDead() {
        return mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0f;
    }

    /** The current screen inbound handlers see: the top screen of the active container's place. */
    public @Nullable GuiScreen screenForInboundHandlers() {
        final Optional<ScreenView> v = viewOf(active);
        if (v.isPresent()) return v.get()
            .topScreenOfPlace();
        if (mainWindow.gatingContainer() == null) return mainWindow.screen();
        return null;
    }

    void setState(final ServerContainer b, final State s) {
        if (b.state != s && ContainerProtocolConfig.debugLog) LOG.info("[Containers] {} -> {}", b, s);
        b.state = s;
    }

    // ================================================================== views

    /** Every hosted entry, bottom to top in each window. */
    public List<HostedScreen<?>> hostedScreens() {
        final List<HostedScreen<?>> out = new ArrayList<>();
        for (final Window w : Driver.INSTANCE.getWindows()) out.addAll(w.stack());
        return out;
    }

    /** The view whose own screen shows {@code b}: the main window first, also for its covered screen. */
    Optional<ScreenView> viewOf(final ServerContainer b) {
        if (mainWindow.hasViewOf(b)) return Optional.of(mainWindow);
        for (final HostedScreen<?> e : hostedScreens()) if (containerOf(e.screen()) == b) return Optional.of(e);
        return Optional.empty();
    }

    boolean hasView(final ServerContainer b) {
        return viewOf(b).isPresent();
    }

    /** Removes every view that shows {@code b}. */
    private void removeViews(final ServerContainer b) {
        mainWindow.removeViewsOf(b);
        for (final HostedScreen<?> e : hostedScreens()) if (containerOf(e.screen()) == b) e.removeFromPlace();
    }

    /** The screen showing {@code b}, else the current one: the GUI a click on {@code b} is predicted for. */
    private @Nullable GuiScreen screenShowing(final ServerContainer b) {
        if (containerOf(mainWindow.coveredScreen()) == b) return mainWindow.coveredScreen();
        return viewOf(b).map(ScreenView::screen)
            .orElse(mc.currentScreen);
    }

    // ================================================================== sending

    /** Sends a packet as the multiplexer: it passes unchecked. */
    public void sendAsMultiplexer(final Packet p) {
        runAsMultiplexer(() -> mc.thePlayer.sendQueue.addToSendQueue(p));
    }

    /** Runs {@code r} (an adapter's channel write, an API trigger) so that what it sends passes unchecked. */
    public void runAsMultiplexer(final Runnable r) {
        contexts.run(new MultiplexerContext(this), r);
    }

    /** The adapter for a screen; vanilla's when no other claims it. */
    public ContainerAdapter adapterOf(final GuiScreen screen) {
        for (final ContainerAdapter a : adapters) if (a.handles(screen)) return a;
        return ContainerAdapter.VANILLA;
    }

    /** Classification of an outbound mod payload by the first adapter that knows it. */
    public PayloadEffect classifyPayload(final Packet packet) {
        for (final ContainerAdapter a : adapters) {
            final PayloadEffect o = a.classifyPayload(packet);
            if (o != PayloadEffect.UNKNOWN) return o;
        }
        return PayloadEffect.UNKNOWN;
    }

    /**
     * The transaction id of a multiplexer click on window {@code w}: its client container's counter,
     * else the multiplexer's own.
     */
    short nextTransactionId(final int w) {
        final InventoryPlayer inventory = mc.thePlayer.inventory;
        if (w == 0) return playerInventory.clientContainer.getNextTransactionID(inventory);
        for (final ServerContainer b : openContainers.values())
            if (b.windowId == w) return b.clientContainer.getNextTransactionID(inventory);
        return probes.nextOwnTransactionId();
    }

    /** A normal left click on a slot of {@code b}, with client prediction: parks and unparks the cursor stack. */
    void clickSlot(final ServerContainer b, final int slot, final Consumer<Boolean> onAnswer) {
        final EntityClientPlayerMP player = mc.thePlayer;
        final short tid = b.clientContainer.getNextTransactionID(player.inventory);
        final GuiScreen screen = screenShowing(b);

        final Supplier<ItemStack> click = () -> b.clientContainer.slotClick(slot, 0, 0, player);
        final ItemStack predicted = contexts
            .call(new MultiplexerContext(this), () -> globals.callWith(screen, b.clientContainer, click));
        final C0EPacketClickWindow c = new C0EPacketClickWindow(b.windowId, slot, 0, 0, predicted, tid);
        probes.sendTracked(c, ServerProbes.NO_CAUSE, onAnswer, this::resetToPlayerInventory);
    }

    /** A hosted view sent packets that may open a GUI: what opens goes to it until this probe is answered. */
    public void attributeArrivalsTo(final HostedScreen<?> v) {
        if (knowsServerState()) probes.askIfOpen(active.windowId, v, ServerProbes.IGNORE, ServerProbes.NOTHING);
    }

    /** A probe or barrier was answered: the watchdog sees progress. */
    void noteProgress() {
        stall = 0;
    }

    // ================================================================== procedures (8)

    /** Runs {@code r} the next time the active container is HELD and ready. */
    void whenIdle(final Runnable r) {
        if (isIdle()) r.run();
        else deferred.add(r);
    }

    private void becomeIdle() {
        phase = Phase.IDLE;
        stall = 0;
        while (isIdle() && !deferred.isEmpty()) deferred.remove(0)
            .run();
    }

    /** The running procedure is superseded: older probe answers trigger nothing, a switch ends. */
    private void abandonRunningProcedure() {
        probes.abandonPending();
        final ContainerSwitch t = runningSwitch;
        runningSwitch = null;
        if (t != null) t.notifyDone(false);
    }

    /** {@code b} is proven to be the server's open container. */
    void setActive(final ServerContainer b) {
        active = b;
    }

    /** The running transfer is over; the active container is ready. */
    void switchEnded() {
        runningSwitch = null;
        becomeIdle();
    }

    /** Refreshes the active container's contents from the server, then {@code done}. */
    void resyncActiveThen(final Runnable done) {
        phase = Phase.WAITING_FOR_SERVER;
        probes.resync(active.windowId, done, this::resetToPlayerInventory);
    }

    /** Refreshes the active container's contents, then becomes idle (a covered screen is shown again). */
    public void resyncActive() {
        resyncActiveThen(this::becomeIdle);
    }

    /** The server has the inventory open: it becomes the active container, refreshed, idle. */
    private void activatePlayerInventory() {
        active = playerInventory;
        resyncActive();
    }

    /**
     * A container stops being the server's open one: paused while a view shows it, closed for
     * good otherwise.
     */
    void pauseContainer(final ServerContainer b) {
        if (b.isPlayerInventory() || b.state == State.CLOSED) return;
        if (hasView(b)) setState(b, State.PAUSED);
        else closeContainer(b);
    }

    /** Closed for good: views closed, close logic run once, adapter told. */
    public void closeContainer(final ServerContainer b) {
        if (b.isPlayerInventory() || b.state == State.CLOSED) return;
        setState(b, State.CLOSED);
        removeViews(b);
        openContainers.remove(b.clientContainer);
        runClientCloseLogic(b.clientContainer);
        b.adapter.onContainerClosed(b);
    }

    /**
     * The client-side close logic of a container, at most once, with nothing it sends reaching the
     * server and the client inventory and cursor restored afterwards: the server's state is the
     * real one.
     */
    private void runClientCloseLogic(final Container clientContainer) {
        if (!closeLogicRan.add(clientContainer)) return;

        final EntityClientPlayerMP player = mc.thePlayer;
        final InventoryPlayer inventory = player.inventory;
        final ItemStack[] mainInventory = copyOf(inventory.mainInventory);
        final ItemStack[] armor = copyOf(inventory.armorInventory);
        final ItemStack held = ItemStack.copyItemStack(inventory.getItemStack());

        try {
            contexts.runClientCloseLogic(clientContainer, player);
        } catch (final RuntimeException e) {
            LOG.warn("[Containers] client-side container close failed", e);
        } finally {
            System.arraycopy(mainInventory, 0, inventory.mainInventory, 0, mainInventory.length);
            System.arraycopy(armor, 0, inventory.armorInventory, 0, armor.length);
            inventory.setItemStack(held);
        }
    }

    private ItemStack[] copyOf(final ItemStack[] stacks) {
        final ItemStack[] out = new ItemStack[stacks.length];
        for (int i = 0; i < stacks.length; i++) out[i] = ItemStack.copyItemStack(stacks[i]);
        return out;
    }

    /**
     * After the close packets of the active container {@code b} left: {@code b} is paused or
     * closed, a probe proves the inventory is open, then it is refreshed.
     */
    private void afterRelease(final ServerContainer b, final boolean pause) {
        abandonRunningProcedure();
        phase = Phase.WAITING_FOR_SERVER;
        mc.thePlayer.inventory.setItemStack(null);
        if (pause) pauseContainer(b);
        else closeContainer(b);
        probes.askIfOpen(0, ok -> activatePlayerInventory(), this::resetToPlayerInventory);
    }

    /** The active container closes for good. */
    private void closeActive(final ServerContainer b) {
        b.adapter.closeOnServer(b, this, true);
        afterRelease(b, false);
    }

    /**
     * A view of the inventory closed while it is open on the server, or the server closed it: the cursor is dropped.
     */
    public void closePlayerInventory(final boolean sendC0D) {
        if (sendC0D) sendAsMultiplexer(new C0DPacketCloseWindow(0));
        mc.thePlayer.inventory.setItemStack(null);
        resyncActive();
    }

    /** A world interaction from an unhooked path while a container is open: pause it first, the server closes it. */
    public void pauseActiveForWorldUse() {
        LOG.warn("[Containers] world interaction from an unhooked path while {} holds the active: pausing it", active);
        active.adapter.closeOnServer(active, this, !active.canReopen());
        afterRelease(active, true);
    }

    /** A view of {@code b} went away. {@code packet == false}: nothing is sent to the server. */
    public void viewClosed(final ServerContainer b, final boolean packet) {
        if (b != active) {
            // paused: no packet. A reopening target is left to its switch.
            if (b.state != State.REOPENING) closeContainer(b);
            return;
        }
        if (!isIdle()) return; // mid-procedure: a container without a view is closed once idle

        if (b.isPlayerInventory()) closePlayerInventory(packet); // C0D(0) as vanilla does; other inventory views stay
        else if (packet) closeActive(b);
        else {
            // closeScreenNoPacket, or the C0D already left: find out what the server has
            mc.thePlayer.inventory.setItemStack(null);
            reconcileWithServer();
        }
    }

    /** Where a screen shown by a handler goes; during a switch, what opens is the target reopening in place. */
    public ArrivalTarget arrivalTarget(final boolean arrival) {
        if (!arrival || !isReopening()) return probes.pendingArrivalTarget();
        return viewOf(runningSwitch.to).<ArrivalTarget>map(v -> v)
            .orElse(ArrivalTarget.HIDDEN);
    }

    /** The server opened {@code clientContainer}, shown by {@code screen}; it is the open container now. */
    public void onServerOpened(final Container clientContainer, final GuiScreen screen) {
        final ServerContainer b = new ServerContainer(
            clientContainer,
            clientContainer.windowId,
            adapterOf(screen),
            OpeningInteraction.NONE);
        openContainers.put(clientContainer, b);
        final OpeningInteraction openedBy = probes.pendingInteraction();
        // probes of other windows can no longer be answered and cause nothing
        probes.dropAllExceptWindow(b.windowId);
        if (isReopening()) {
            runningSwitch.onTargetOpened(b);
            return;
        }

        // a C0D of ours may be in flight when the active container was not ready: then only a barrier ends the wait
        final boolean racing = !isIdle();
        final ServerContainer former = active;
        abandonRunningProcedure();

        // the former active container is displaced; the new one is idle once its probe is answered
        b.openedBy = openedBy;
        pauseContainer(former);
        active = b;
        phase = Phase.WAITING_FOR_SERVER;
        probes.askIfOpen(b.windowId, ok -> becomeIdle(), this::resetToPlayerInventory);
        if (racing) probes.sendBarrier();
    }

    /** The server closed its open container; the inventory is open now. */
    public void onServerClosed() {
        stall = 0;
        mc.thePlayer.inventory.setItemStack(null);
        if (!knowsServerState()) return; // the reset continues
        if (runningSwitch != null && runningSwitch.from == active) {
            final boolean parking = runningSwitch.isParking();
            closeContainer(active);
            active = playerInventory;
            // a park for the closed window may never be answered
            if (parking) {
                abandonRunningProcedure();
                resyncActive();
            }
            return;
        }

        abandonRunningProcedure(); // the server closed the target right after opening it
        if (!active.isPlayerInventory()) closeContainer(active);
        else {
            // closeScreen() while nothing was open: close the client GUI like vanilla
            removeViews(active);
            mainWindow.closeFreeScreen();
        }
        activatePlayerInventory();
    }

    /**
     * Client screens changed in a way that implies the server's open container changed without the
     * multiplexer: ask the server what it has open.
     */
    public void reconcileWithServer() {
        if (!knowsServerState() || runningSwitch != null) return;

        final ServerContainer former = active;
        abandonRunningProcedure();
        phase = Phase.WAITING_FOR_SERVER;
        probes.checkOpen(0, () -> {
            closeContainer(former);
            activatePlayerInventory();
        }, () -> keepIfStillOpen(former));
    }

    /** The server still has the container open. It stays active if a view still shows it. */
    private void keepIfStillOpen(final ServerContainer former) {
        if (former.isPlayerInventory()) resetToPlayerInventory();
        else if (former.state == State.OPEN && hasView(former)) becomeIdle();
        else closeActive(former);
    }

    /**
     * Closes whatever is open and checks that the inventory is: three tries, then degraded. With debug logging the
     * trace shows the trigger.
     */
    public void resetToPlayerInventory() {
        if (ContainerProtocolConfig.debugLog) LOG.warn("[Containers] reset (active {})", active, new Throwable());
        else LOG.warn("[Containers] reset (active {})", active);
        abandonRunningProcedure();
        active = playerInventory;
        if (phase != Phase.DEGRADED) phase = Phase.RESETTING;
        resetAttempt(1);
    }

    private void resetAttempt(final int n) {
        sendAsMultiplexer(new C0DPacketCloseWindow(0));
        probes.checkOpen(0, () -> {
            for (final ServerContainer b : new ArrayList<>(openContainers.values())) pauseContainer(b);
            activatePlayerInventory();
        }, () -> resetFailed(n));
    }

    private void resetFailed(final int n) {
        if (n < 3) {
            resetAttempt(n + 1);
            return;
        }
        LOG.error("[Containers] degraded: the server's open container is still unknown after three resets");
        phase = Phase.DEGRADED;
    }

    /** Containers without a view, or whose target is gone, are closed; checked each tick while idle. */
    private void closeUnreachable() {
        for (final ServerContainer b : new ArrayList<>(openContainers.values())) {
            if (!hasView(b)) closeOrphan(b);
            else if (b.state == State.PAUSED && b.openedBy.targetGoneForGood()) {
                LOG.info("[Containers] {} lost access; closing it", b);
                closeContainer(b);
            }
            if (!isIdle()) return;
        }
    }

    /** A container without a view is closed, on the server if it is the open one. */
    private void closeOrphan(final ServerContainer b) {
        if (b == active) closeActive(b);
        else closeContainer(b);
    }

    // ================================================================== switching the open container

    /**
     * Starts switching the server's open container from the active one to {@code to}. Returns
     * {@link OverlayHint#NONE} when it started, else why it was refused; then nothing was sent
     * and {@code done} will not run.
     *
     * @param keepParked a parked cursor stack stays in the inventory
     * @param done       receives whether {@code to} ended up the idle active container
     */
    OverlayHint switchTo(final ServerContainer to, final boolean keepParked, final Consumer<Boolean> done) {
        final OverlayHint refused = whySwitchRefused(to);
        if (refused != OverlayHint.NONE) return refused;

        final ContainerSwitch t = new ContainerSwitch(this, to, keepParked, done);
        runningSwitch = t;
        phase = Phase.WAITING_FOR_SERVER;
        if (!to.isPlayerInventory()) setState(to, State.REOPENING);
        t.start();
        return OverlayHint.NONE;
    }

    /** Why a switch would be refused, in this order. */
    private OverlayHint whySwitchRefused(final ServerContainer to) {
        if (!isIdle() || to.state == State.CLOSED || active == to) return OverlayHint.BUSY;
        if (active.wouldDropItemsOnClose()) return OverlayHint.PINNED;
        final ItemStack cursor = cursorStack();
        if (cursor != null && findParkingSlot(active, cursor).isEmpty()) return OverlayHint.NO_PARK_SLOT;
        if (!to.canReopenNow()) return OverlayHint.CANNOT_REOPEN;
        return OverlayHint.NONE;
    }

    /** Where to park the cursor stack: an empty slot of the player inventory that accepts it; the hotbar last. */
    Optional<Slot> findParkingSlot(final ServerContainer b, final ItemStack cursor) {
        final InventoryPlayer inventory = mc.thePlayer.inventory;
        return b.slots()
            .stream()
            .filter(s -> s.inventory == inventory && !s.getHasStack() && s.getSlotIndex() <= 35)
            .filter(s -> s.isItemValid(cursor))
            .min(Comparator.comparing(s -> s.getSlotIndex() < 9));
    }

    // ================================================================== world interactions and API opens

    /** Right click on a block or entity, item use, horse inventory request. Returns true to cancel it. */
    public boolean onWorldUse(final boolean horse) {
        if (!contexts.onMainThread() || !isInWorld()) return false;
        if (!isIdle()) return true;
        final Consumer<Boolean> again = ok -> { if (ok) repeatUse(horse); };
        if (!active.isPlayerInventory()) {
            active.showHint(switchTo(playerInventory, true, again)); // close the active container first
            return true;
        }

        if (cursorStack() == null) return false;
        parkCursorThen(() -> repeatUse(horse));
        return true;
    }

    /** Parks a loaded cursor before opening another GUI. A refused or rejected park stops the action. */
    private void parkCursorThen(final Runnable done) {
        final ItemStack cursor = cursorStack();
        if (cursor == null) {
            done.run();
            return;
        }

        final Optional<Slot> slot = findParkingSlot(active, cursor);
        if (slot.isEmpty()) {
            active.showHint(OverlayHint.NO_PARK_SLOT);
            return;
        }

        phase = Phase.WAITING_FOR_SERVER;
        clickSlot(active, slot.get().slotNumber, ok -> {
            if (ok) deferred.add(0, done);
            else active.showHint(OverlayHint.PARK_FAILED);
            becomeIdle();
        });
    }

    /** The interaction is repeated once if its key is still down. */
    private void repeatUse(final boolean horse) {
        if (horse && mc.gameSettings.keyBindInventory.getIsKeyPressed()) mc.thePlayer.func_110322_i();
        if (horse || !mc.gameSettings.keyBindUseItem.getIsKeyPressed()) return;
        if (mc instanceof MinecraftAccessor accessor) accessor.offscreen$rightClick();
    }

    /**
     * Opens what {@code trigger} makes the server open into the window {@code where} supplies,
     * which is asked for when the server opens something.
     */
    public void openInWindow(final Supplier<Window> where, final Runnable trigger) {
        if (!isInWorld() || isDegraded()) return;
        final ApiOpenRequest req = new ApiOpenRequest(this, where);

        final Runnable fire = () -> {
            runAsMultiplexer(trigger);
            probes.askIfOpen(0, req, ServerProbes.IGNORE, ServerProbes.NOTHING);
        };
        whenIdle(() -> {
            if (active.isPlayerInventory()) parkCursorThen(fire);
            else active.showHint(switchTo(playerInventory, true, ok -> { if (ok) fire.run(); }));
        });
    }

    // ================================================================== lifecycle

    /** S01: tests whether the server answers barriers, then the inventory is the active container. */
    private void onJoin() {
        forgetEverything();
        joined = true;
        playerInventory = ServerContainer.playerInventory(mc.thePlayer.inventoryContainer);
        active = playerInventory;

        probes.testBarrierSupport(() -> {
            active = playerInventory;
            becomeIdle();
        }, this::resetToPlayerInventory);
    }

    /** S07: the player object was replaced. */
    public void onRespawn() {
        closeAllButPlayerInventory();
        playerInventory = ServerContainer.playerInventory(mc.thePlayer.inventoryContainer);
        dead = false;
        resetToPlayerInventory();
        whenIdle(() -> { for (final ContainerAdapter a : adapters) a.afterRespawn(this); });
    }

    /**
     * S06 with health 0, or a dead player at a tick: C0D as vanilla sends it, then check that the inventory is open.
     */
    public void onDeath() {
        if (dead) return;
        dead = true;
        if (knowsServerState() && !active.isPlayerInventory())
            sendAsMultiplexer(new C0DPacketCloseWindow(active.windowId));
        closeAllButPlayerInventory();
        if (knowsServerState()) phase = Phase.WAITING_FOR_SERVER;
        probes.checkOpen(0, this::activatePlayerInventory, this::resetToPlayerInventory);
    }

    /** Every container but the inventory closes for good, and so do their views. */
    private void closeAllButPlayerInventory() {
        abandonRunningProcedure();
        for (final ServerContainer b : new ArrayList<>(openContainers.values())) closeContainer(b);
        removeViews(playerInventory);
    }

    /** Disconnect, world unload: drop everything, no packets. */
    private void forgetEverything() {
        Driver.INSTANCE.closeAll();
        mainWindow.forgetCover();
        for (final ServerContainer b : openContainers.values()) b.state = State.CLOSED;
        openContainers.clear();

        // every procedure is forgotten
        deferred.clear();
        probes = new ServerProbes(this);
        runningSwitch = null;
        phase = Phase.RESETTING;
        joined = false;
        dead = false;
    }

    public void tick() {
        if (!isInWorld()) {
            if (joined) forgetEverything();
            return;
        }

        if (playerDead()) onDeath();
        else dead = false;
        resetIfStalled();
        if (isIdle()) closeUnreachable();
        mainWindow.restoreIngameFocus();
    }

    /**
     * Watchdog, only without a barrier: reset when nothing progressed for a while; it also retries degraded. A
     * premature reset is safe.
     */
    private void resetIfStalled() {
        final boolean watched = isDegraded() || !probes.barrierSupported();
        final boolean moving = !mc.isGamePaused() && packets > 0;
        packets = 0;
        if (isIdle() || !watched) stall = 0;
        else if (moving && ++stall >= ContainerProtocolConfig.watchdogTicks) resetToPlayerInventory();
    }
}
