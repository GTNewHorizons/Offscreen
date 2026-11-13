package com.sbancuz.offscreen.container.context;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S2EPacketCloseWindow;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.network.play.server.S31PacketWindowProperty;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.network.play.server.S3APacketTabComplete;

import com.sbancuz.offscreen.container.ContainerAdapter.PayloadEffect;
import com.sbancuz.offscreen.container.ServerContainer;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.window.HostedScreen;
import com.sbancuz.offscreen.window.Window;

/**
 * The handling of one inbound play packet. What the packet tells about the server's open
 * container is checked before the handler; screens the handler shows are placed in a window;
 * containers the server opened are recorded after it.
 */
public final class InboundPacketContext extends ExecutionContext {

    /** A container screen with a new container, shown by the handler. */
    private record Arrival(GuiContainer screen, Runnable afterBind) {}

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Packet packet;
    /** S07: screens the handler shows go to the main window unrouted. */
    private final boolean respawn;
    /** The main window's screen before the handler ran. */
    private final @Nullable GuiScreen mainBefore;
    /** The handler closed the open container's GUI or showed an inventory screen. */
    private boolean needsReconcile;
    /** A packet for a window the multiplexer does not know is open. */
    private boolean unexplainedWindow;
    private final List<Arrival> arrivals = new ArrayList<>(0);

    public InboundPacketContext(final ServerContainerMultiplexer multiplexer, final Packet packet) {
        super(multiplexer);
        this.packet = packet;
        this.respawn = packet instanceof S07PacketRespawn;
        this.mainBefore = multiplexer.mainWindow.screen();
    }

    /** Runs the vanilla handler in this frame, between the evidence rules and post-handling. */
    public void handle(final Runnable handler) {
        if (consumedAsEvidence()) return;

        boolean ok = false;
        try {
            runWithMainWindowGlobals(handler);
            ok = true;
        } finally {
            afterHandler(ok);
        }
    }

    /** The handler sees the main window's screen and the active container as Minecraft's globals. */
    private void runWithMainWindowGlobals(final Runnable handler) {
        final GuiScreen screen = multiplexer.screenForInboundHandlers();
        final Container open = multiplexer.activeContainer().clientContainer;
        multiplexer.contexts.run(this, () -> multiplexer.globals.runWith(screen, open, handler));
    }

    @Override
    public boolean actsForActiveView() {
        return false;
    }

    @Override
    boolean mayClick() {
        return true;
    }

    @Override
    PayloadEffect effectOfUnknownPayload() {
        return PayloadEffect.NEUTRAL;
    }

    @Override
    public void requestReconcile() {
        needsReconcile = true;
    }

    // ================================================================== what the packet tells

    /** Returns true if the packet was an answer of ours and is consumed. */
    private boolean consumedAsEvidence() {
        if (packet instanceof S32PacketConfirmTransaction s) return answersProbe(s);
        if (packet instanceof S3APacketTabComplete) return multiplexer.probes()
            .onTabCompleteAnswer();
        if (packet instanceof S2EPacketCloseWindow) {
            multiplexer.onServerClosed();
            return true;
        }

        // slot and property packets name the window the server has open
        if (packet instanceof S2FPacketSetSlot s) checkWindowEvidence(s.func_149175_c());
        if (packet instanceof S30PacketWindowItems s) checkWindowEvidence(s.func_148911_c());
        if (packet instanceof S31PacketWindowProperty s) checkWindowEvidence(s.func_149182_c());
        return false;
    }

    /** A rejected click of the user is not a probe: vanilla answers it with C0F inside the handler. */
    private boolean answersProbe(final S32PacketConfirmTransaction s) {
        final int w = s.func_148889_c();
        if (multiplexer.probes()
            .onConfirmTransaction(w, s.func_148890_d(), s.func_148888_e())) return true;
        checkWindowEvidence(w);
        return false;
    }

    /** A window the multiplexer does not know is open was opened without it: reset after the handler. */
    private void checkWindowEvidence(final int w) {
        if (w <= 0 || !multiplexer.knowsServerState() || multiplexer.activeContainer().windowId == w) return;
        ServerContainerMultiplexer.LOG.warn(
            "[Containers] window evidence for {} that the model cannot explain (active {})",
            w,
            multiplexer.activeContainer());
        unexplainedWindow = true;
    }

    private void afterHandler(final boolean ok) {
        try {
            postHandle();
            if (!ok && multiplexer.knowsServerState() && !multiplexer.isIdle()) multiplexer.resetToPlayerInventory(); // the
                                                                                                                      // handler
                                                                                                                      // failed
        } catch (final Throwable t) {
            ServerContainerMultiplexer.LOG.error("[Containers] inbound post-handling failed", t);
        }
        multiplexer.mainWindow.restoreIngameFocus();
    }

    /** After the handler: record what opened, and check what the handler changed. */
    private void postHandle() {
        if (!multiplexer.isInWorld()) return; // the handler disconnected
        if (packet instanceof S2FPacketSetSlot s && s.func_149175_c() == -1) multiplexer.probes()
            .onResyncEnded();
        if (respawn) {
            multiplexer.onRespawn();
            return;
        }

        // handlers write a window id onto whatever container is open; id 0 is the inventory's alone
        mc.thePlayer.inventoryContainer.windowId = 0;
        bindArrivals();
        if (needsReconcile) multiplexer.reconcileWithServer();
        if (unexplainedWindow) multiplexer.resetToPlayerInventory();
        if (packet instanceof S06PacketUpdateHealth h && h.func_149332_c() <= 0f) multiplexer.onDeath();
    }

    // ================================================================== screens the handler shows

    @Override
    public void displayScreen(@Nullable final GuiScreen s, final Consumer<GuiScreen> vanilla) {
        if (respawn) multiplexer.mainWindow.showUnrouted(s);
        else if (s == null) closeServerGui();
        else placeScreen(s);
    }

    @Override
    public void closeScreen(final boolean packet) {
        closeServerGui();
    }

    /**
     * The handler closed the open container's GUI: its view and children close, then the client checks what is open.
     */
    private void closeServerGui() {
        final GuiScreen ctx = mc.currentScreen;
        needsReconcile = true;
        mc.currentScreen = null;
        if (ctx != null && ctx == multiplexer.mainWindow.screen()) {
            multiplexer.mainWindow.showUnrouted(null);
            return;
        }

        for (final HostedScreen<?> e : multiplexer.hostedScreens()) {
            if (e.screen() != ctx) continue;
            final Window w = e.window();
            w.removeFrom(
                w.stack()
                    .boundBaseOf(e));
            return;
        }
    }

    /** A screen from a handler: a free one goes where a GUI the server opens would go. */
    private void placeScreen(final GuiScreen incoming) {
        if (incoming instanceof GuiContainer gc && gc.inventorySlots != null) {
            placeContainerScreen(gc);
            return;
        }
        multiplexer.arrivalTarget(false)
            .place(incoming);
        mc.currentScreen = incoming;
    }

    /** A container screen from a handler; one whose container is not known yet was opened by the server. */
    private void placeContainerScreen(final GuiContainer gc) {
        final ServerContainer bound = multiplexer.containerOf(gc);
        // an inventory screen from a handler: the server may or may not have switched to the inventory
        if (bound == multiplexer.playerInventory()) needsReconcile = true;
        final Runnable afterBind = multiplexer.arrivalTarget(bound == null)
            .place(gc);

        // the handler writes the window id onto thePlayer.openContainer right after this call
        mc.currentScreen = gc;
        mc.thePlayer.openContainer = gc.inventorySlots;
        if (bound == null) arrivals.add(new Arrival(gc, afterBind));
    }

    /** Records the containers that opened, then accounts for the main window. */
    private void bindArrivals() {
        for (final Arrival a : arrivals) bind(a);
        if (multiplexer.mainWindow.screen() == mainBefore) return;

        final ServerContainer closed = multiplexer.mainWindow.afterScreenChanged(mainBefore);
        // the active container's view went away without a server close being seen; anything else just closes
        if (closed == multiplexer.activeContainer()) needsReconcile = true;
        else if (closed != null) multiplexer.closeContainer(closed);
    }

    private void bind(final Arrival a) {
        final Container c = a.screen.inventorySlots;
        if (c.windowId == 0 || multiplexer.containerOf(a.screen) != null) return; // a free screen after all
        a.afterBind.run();
        multiplexer.onServerOpened(c, a.screen);
    }
}
