package com.sbancuz.offscreen.window;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;
import org.lwjglx.opengl.Display;

import com.sbancuz.offscreen.container.ContainerAdapter.PayloadEffect;
import com.sbancuz.offscreen.container.HeldClick;
import com.sbancuz.offscreen.container.ScreenView;
import com.sbancuz.offscreen.container.ServerContainer;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.container.ServerProbes;
import com.sbancuz.offscreen.container.context.UnroutedMainScreenContext;
import com.sbancuz.offscreen.mixins.early.GuiScreenAccessor;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * The main window's screen slot, and the view it is for the multiplexer: a free screen opened
 * over a container screen (which stays open underneath), screen changes made in it, its input
 * and updates, moving screens to and from hosted windows, and ingame focus.
 */
@Lwjgl3Aware
public final class MainWindow implements ScreenView {

    private final ServerContainerMultiplexer multiplexer;
    private final Minecraft mc = Minecraft.getMinecraft();

    /** The container screen under the free screen the main window shows now; it stays open. */
    private @Nullable GuiScreen covered;
    /** The previous state, to regain ingame focus on a change only. */
    private boolean lastMainNull = true, lastActive = true;

    private final KeyBinding moveKey = new KeyBinding("key.offscreen.move", Keyboard.KEY_F7, "key.offscreen");
    /** The move key is polled per frame: the screen's input handling drains the key events. */
    private boolean moveDown, movePending;

    public MainWindow(final ServerContainerMultiplexer multiplexer) {
        this.multiplexer = multiplexer;
    }

    public void register() {
        ClientRegistry.registerKeyBinding(moveKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** The main window's screen, also while a run overrides {@code currentScreen}. */
    @Override
    public @Nullable GuiScreen screen() {
        return multiplexer.globals.mainScreen();
    }

    public @Nullable GuiScreen coveredScreen() {
        return covered;
    }

    // ================================================================== the main window as a view

    /** Its own container, or for a free screen over a covered one the covered one's. */
    @Override
    public @Nullable ServerContainer gatingContainer() {
        final ServerContainer own = multiplexer.containerOf(screen());
        if (own != null) return own;
        return multiplexer.containerOf(covered);
    }

    @Override
    public @Nullable GuiScreen topScreenOfPlace() {
        return screen();
    }

    /** Whether {@code b} has a view here: the screen shown, or the covered one. */
    public boolean hasViewOf(final ServerContainer b) {
        return multiplexer.containerOf(screen()) == b || multiplexer.containerOf(covered) == b;
    }

    @Override
    public void removeFromPlace() {
        if (screen() != null) showUnrouted(null);
        restoreIngameFocus();
    }

    /** Removes the views of {@code b}; the covered one goes with the free screen over it. */
    public void removeViewsOf(final ServerContainer b) {
        if (multiplexer.containerOf(screen()) == b) removeFromPlace();
        if (covered == null || multiplexer.containerOf(covered) != b) return;
        covered = null;
        removeFromPlace();
    }

    @Override
    public boolean samePlace(final ScreenView other) {
        return other == this;
    }

    /** A free screen over a covered one closes back to it. */
    @Override
    public void close(final boolean packet) {
        final GuiScreen s = screen();
        if (multiplexer.containerOf(s) == null && covered != null) uncover(s);
        else ScreenView.super.close(packet);
    }

    @Override
    public PayloadEffect effectOfUnknownPayload() {
        if (screen() == null) return PayloadEffect.NEUTRAL;
        return ScreenView.super.effectOfUnknownPayload();
    }

    @Override
    public boolean canPlace() {
        return true;
    }

    @Override
    public Runnable place(final GuiScreen s) {
        showUnrouted(s);
        return ServerProbes.NOTHING;
    }

    // ================================================================== screen changes

    /** Changes the screen as the multiplexer: vanilla side effects, no routing. */
    public void showUnrouted(@Nullable final GuiScreen s) {
        multiplexer.globals.runOnMainWindowState(
            () -> multiplexer.contexts.run(new UnroutedMainScreenContext(multiplexer), () -> mc.displayGuiScreen(s)));
    }

    /** Vanilla runs, then a container view that went away is closed. */
    @Override
    public void onDisplayRequested(@Nullable final GuiScreen incoming, final Consumer<GuiScreen> vanilla) {
        final GuiScreen old = mc.currentScreen;
        vanilla.accept(incoming);
        final ServerContainer closed = afterScreenChanged(old);
        if (closed != null) multiplexer.viewClosed(closed, true);
    }

    /** The view closes; without one, a close while the inventory is open on the server drops the cursor. */
    @Override
    public void onCloseRequested(final boolean packet) {
        if (screen() != null) close(packet);
        else if (packet && multiplexer.idleAtPlayerInventory()) multiplexer.closePlayerInventory(true);
    }

    /**
     * Bookkeeping after the screen changed from {@code old}: the covered screen, the open container, focus.
     * Returns the container whose main-window view went away, if any.
     */
    public @Nullable ServerContainer afterScreenChanged(@Nullable final GuiScreen old) {
        final ServerContainer closed = updateCover(old, screen());
        multiplexer.globals.restoreMainWindowContainer();
        restoreIngameFocus();
        return closed;
    }

    /**
     * A free screen covers the container screen it replaced, which stays open; showing that screen
     * again uncovers it. Returns the container whose view went away, if any.
     */
    private @Nullable ServerContainer updateCover(@Nullable final GuiScreen old, @Nullable final GuiScreen now) {
        if (now == old) return null; // a cancelled GuiOpenEvent
        if (covered != null && now == covered) {
            covered = null;
            // the contents may have changed under the free screen
            if (isActiveView()) multiplexer.resyncActive();
            return null;
        }

        if (multiplexer.isFreeScreen(now)) {
            if (multiplexer.containerOf(old) != null) covered = old;
            return null;
        }

        ServerContainer closed = multiplexer.containerOf(old);
        if (closed == null) closed = multiplexer.containerOf(covered);
        covered = null;
        if (closed == multiplexer.containerOf(now)) return null;
        return closed;
    }

    /** A free child closes: the covered parent is shown again. */
    private void uncover(@Nullable final GuiScreen child) {
        showUnrouted(covered);
        afterScreenChanged(child);
    }

    /** The server closed the inventory: vanilla closes whatever the main window shows. */
    public void closeFreeScreen() {
        if (multiplexer.isFreeScreen(screen()) && covered == null) showUnrouted(null);
    }

    /** Disconnect: nothing is covered any more. */
    public void forgetCover() {
        covered = null;
    }

    /** The main window regains ingame focus when it has no screen, on the change and on OS focus. */
    public void restoreIngameFocus() {
        if (multiplexer.globals.areLent() || !multiplexer.isInWorld()) return;
        final boolean mainNull = mc.currentScreen == null;
        final boolean active = Display.isActive();
        final boolean transition = !lastMainNull || !lastActive;
        if (mainNull && active && !mc.inGameHasFocus && transition) mc.setIngameFocus();
        lastMainNull = mainNull;
        lastActive = active;
    }

    // ================================================================== input and updates

    /** Whether runTick is handling the main window's own screen, in a world. */
    private boolean handlesOwnScreen(final GuiScreen screen) {
        return !multiplexer.globals.areLent() && screen == mc.currentScreen && multiplexer.isInWorld();
    }

    /** Wraps {@code currentScreen.handleInput()} in {@code runTick}: the move key, then the view's input. */
    public void onHandleInput(final GuiScreen screen, final Runnable original) {
        if (!handlesOwnScreen(screen)) {
            original.run();
            return;
        }
        final boolean move = movePending;
        movePending = false;
        if (move && moveToNewWindow()) return;

        multiplexer.contexts.runInMainWindowView(() -> deliverInput(screen, original));
    }

    /** A gated screen gets nothing, a delivering one its held click first. */
    private void deliverInput(final GuiScreen screen, final Runnable original) {
        if (!receivesInput()) {
            drainGatedEvents();
            return;
        }
        multiplexer.gatedInput.deliverHeldClick(this, h -> replayHeldClick(screen, h));
        original.run();
    }

    /** Wraps {@code currentScreen.updateScreen()} in {@code runTick}: gated screens get no updates. */
    public void onUpdateScreen(final GuiScreen screen, final Runnable original) {
        if (!handlesOwnScreen(screen)) original.run();
        else if (receivesInput()) multiplexer.contexts.runInMainWindowView(original);
    }

    /** A gated screen's events are drained here, so the later loops of runTick see none. */
    private void drainGatedEvents() {
        while (Mouse.next()) onGatedMouseEvent();
        while (Keyboard.next()) {
            if (Keyboard.getEventKeyState() && multiplexer.gatedInput.onKey(this, Keyboard.getEventKey())) break;
        }
    }

    private void onGatedMouseEvent() {
        final GuiScreen s = screen();
        final int button = Mouse.getEventButton();
        if (button < 0) return;

        final int x = Mouse.getEventX() * s.width / Math.max(1, mc.displayWidth);
        final int y = s.height - Mouse.getEventY() * s.height / Math.max(1, mc.displayHeight) - 1;
        if (Mouse.getEventButtonState()) multiplexer.gatedInput.onPress(this, x, y, button);
        else multiplexer.gatedInput.onRelease(button);
    }

    /** The held click as {@code GuiScreen.handleMouseInput} would deliver it. */
    private void replayHeldClick(final GuiScreen screen, final HeldClick h) {
        if (!(screen instanceof GuiScreenAccessor s)) return;
        try {
            s.offscreen$setEventButton(h.button());
            s.offscreen$setLastMouseEvent(Minecraft.getSystemTime());
            s.invokeMouseClicked(h.x(), h.y(), h.button());

            // the button went up while the click was held
            if (!h.released()) return;
            s.offscreen$setEventButton(-1);
            s.invokeMouseMovedOrUp(h.x(), h.y(), h.button());
        } catch (final IOException e) {
            ServerContainerMultiplexer.LOG.warn("[Containers] held click delivery failed", e);
        }
    }

    /** Drawn after the main window's screen: the overlay, and the MOVE key poll. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDrawScreenPost(final GuiScreenEvent.DrawScreenEvent.Post event) {
        if (multiplexer.globals.areLent() || event.gui != screen() || !multiplexer.isInWorld()) return;
        final int key = moveKey.getKeyCode();
        final boolean down = Display.isActive() && key > 0 && Keyboard.isKeyDown(key);
        movePending |= down && !moveDown;
        moveDown = down;
        multiplexer.overlay.draw(this, event.gui.width, event.gui.height);
    }

    // ================================================================== moving screens between windows

    public boolean isMoveKey(final int keyCode) {
        return keyCode == moveKey.getKeyCode();
    }

    /** To a new hosted window: no displayGuiScreen, no onGuiClosed, no packet. */
    public boolean moveToNewWindow() {
        final GuiScreen s = screen();
        if (s == null || multiplexer.globals.areLent() || multiplexer.isSwitching()) return false;
        final Window w = new Window(
            s.getClass()
                .getSimpleName());
        if (!w.isOpen()) return false;

        // the covered screen moves with the free screen over it
        final List<GuiScreen> moving = new ArrayList<>(2);
        if (covered != null) moving.add(covered);
        moving.add(s);
        covered = null;
        for (final GuiScreen g : moving) multiplexer.adapterOf(g)
            .detachFromMainWindow(g);

        mc.currentScreen = null;
        multiplexer.globals.restoreMainWindowContainer();
        for (final GuiScreen g : moving) w.pushScreen(g);
        // the main window is the game again
        if (Display.isActive() && !mc.inGameHasFocus) mc.setIngameFocus();
        restoreIngameFocus();
        return true;
    }

    /** From hosted window {@code w}, only if the main window has no screen: the bound view and its children move. */
    public void moveFromWindow(final Window w) {
        final HostedStack st = w.stack();
        if (st.isEmpty() || screen() != null || multiplexer.globals.areLent() || multiplexer.isSwitching()) return;
        final int from = st.indexOf(st.boundBaseOf(st.top()));
        final List<HostedScreen<?>> moving = new ArrayList<>(st.subList(from, st.size()));
        st.removeElements(from, st.size());

        for (final HostedScreen<?> e : moving) {
            if (showMoved(e.screen())) continue;
            // a GuiOpenEvent listener refused: undo
            showUnrouted(null);
            covered = null;
            for (final HostedScreen<?> back : moving) st.pushEntry(back);
            return;
        }
        if (st.isEmpty()) w.destroy();
    }

    /** Shows a moved screen; false when a GuiOpenEvent listener refused it. */
    private boolean showMoved(final GuiScreen s) {
        final GuiScreen before = mc.currentScreen;
        showUnrouted(s);
        if (mc.currentScreen != s) return false;
        if (multiplexer.containerOf(before) != null && multiplexer.isFreeScreen(s)) covered = before;
        return true;
    }
}
