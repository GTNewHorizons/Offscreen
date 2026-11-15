package com.sbancuz.offscreen.window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjglx.input.Keyboard;
import org.lwjglx.opengl.Display;

import com.sbancuz.offscreen.container.ContainerAdapter.PayloadEffect;
import com.sbancuz.offscreen.container.ScreenView;
import com.sbancuz.offscreen.container.ServerContainer;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.container.ServerProbes;
import com.sbancuz.offscreen.container.routing.UnroutedMainScreenScope;
import com.sbancuz.offscreen.container.routing.ViewScope;
import com.sbancuz.offscreen.scope.ScopePipeline;
import com.sbancuz.offscreen.scope.ScreenScope;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * The main window's screen slot: moving its screen to a new hosted window with the move key, and a hosted
 * window's screens back. It is also the view the main window is for the container multiplexer: a free screen
 * opened over a container screen (which stays open underneath), screen changes made in it, its input and
 * updates, and ingame focus.
 */
@Lwjgl3Aware
public final class MainWindow implements ScreenView {

    private final ServerContainerMultiplexer multiplexer = ServerContainerMultiplexer.INSTANCE;
    private final Minecraft mc = Minecraft.getMinecraft();

    /** The innermost entered {@link ScreenScope}; null while no run has Minecraft's GUI globals. Kept by the scopes. */
    public @Nullable ScreenScope screenScope;
    /** The container screen under the free screen the main window shows now; it stays open. */
    private @Nullable GuiScreen covered;
    /** The previous state, to regain ingame focus on a change only. */
    private boolean lastMainNull = true, lastActive = true;

    private final KeyBinding moveKey = new KeyBinding("key.offscreen.move", Keyboard.KEY_F7, "key.offscreen");
    /** The move key is polled per frame: the screen's input handling drains the key events. */
    private boolean moveDown, movePending;

    public void register() {
        ClientRegistry.registerKeyBinding(moveKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** The main window's screen, also while a {@link ScreenScope} makes {@code currentScreen} another one. */
    @Override
    public @Nullable GuiScreen screen() {
        if (screenScope == null) return mc.currentScreen;
        return screenScope.mainWindowScreen();
    }

    /** Whether Minecraft's GUI globals describe another screen right now, not this window's. */
    private boolean globalsBorrowed() {
        return screenScope != null && screenScope.describesAnotherScreen();
    }

    /** While the globals are this window's, the open container is its screen's container or the inventory. */
    public void syncOpenContainer() {
        // a disconnect inside a handler leaves no player behind
        if (globalsBorrowed() || mc.thePlayer == null) return;
        mc.thePlayer.openContainer = mc.thePlayer.inventoryContainer;
        if (mc.currentScreen instanceof GuiContainer gc && gc.inventorySlots != null) {
            mc.thePlayer.openContainer = gc.inventorySlots;
        }
    }

    public @Nullable GuiScreen coveredScreen() {
        return covered;
    }

    // ================================================================== input and updates

    /** Whether runTick is handling the main window's own screen, in a world. */
    private boolean handlesOwnScreen(final GuiScreen screen) {
        return !globalsBorrowed() && screen == mc.currentScreen && multiplexer.isInWorld();
    }

    /**
     * Wraps {@code currentScreen.handleInput()} in {@code runTick}: the move key, then the screen's input behind
     * the gate every view's input passes.
     */
    public void onHandleInput(final GuiScreen screen, final Runnable original) {
        if (!handlesOwnScreen(screen)) {
            original.run();
            return;
        }
        final boolean move = movePending;
        movePending = false;
        if (move && moveToNewWindow()) return;

        runAsView(() -> multiplexer.gatedInput.handleInput(this, original));
    }

    /** Wraps {@code currentScreen.updateScreen()} in {@code runTick}: a gated screen is a snapshot, without updates. */
    public void onUpdateScreen(final GuiScreen screen, final Runnable original) {
        if (!handlesOwnScreen(screen)) original.run();
        else if (receivesInput()) runAsView(original);
    }

    /** Runs the screen's input or update as the view's; screen changes it defers are applied after the run. */
    private void runAsView(final Runnable body) {
        new ViewScope(multiplexer, this, true).run(body);
    }

    /** Drawn after the main window's screen: what the multiplexer shows over a view, and the MOVE key poll. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDrawScreenPost(final GuiScreenEvent.DrawScreenEvent.Post event) {
        if (globalsBorrowed() || event.gui != screen() || !multiplexer.isInWorld()) return;
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
        if (s == null || globalsBorrowed() || multiplexer.isSwitching()) return false;
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
        syncOpenContainer();
        for (final GuiScreen g : moving) w.stack()
            .pushScreen(g);
        // the main window is the game again
        if (Display.isActive() && !mc.inGameHasFocus) mc.setIngameFocus();
        restoreIngameFocus();
        return true;
    }

    /**
     * From hosted window {@code w}, only if the main window has no screen: its top screen moves, with the
     * container screen it sits on.
     */
    public void moveFromWindow(final Window w) {
        final HostedStack st = w.stack();
        if (st.isEmpty() || screen() != null || !multiplexer.isInWorld()) return;
        if (globalsBorrowed() || multiplexer.isSwitching()) return;
        final int from = st.indexOf(st.boundBaseOf(st.top()));
        final List<HostedScreen<?>> moving = new ArrayList<>(st.subList(from, st.size()));
        st.removeElements(from, st.size());

        for (final HostedScreen<?> e : moving) {
            if (showMoved(e.screen())) continue;
            // a GuiOpenEvent listener refused: undo
            showUnrouted(null);
            covered = null;
            st.addAll(moving);
            st.top()
                .requestResize();
            return;
        }
        if (st.isEmpty()) w.destroy();
        else st.top()
            .requestResize();
    }

    /** Shows a moved screen; false when a GuiOpenEvent listener refused it. */
    private boolean showMoved(final GuiScreen s) {
        final GuiScreen before = mc.currentScreen;
        showUnrouted(s);
        if (mc.currentScreen != s) return false;
        if (multiplexer.containerOf(before) != null && multiplexer.isFreeScreen(s)) covered = before;
        return true;
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

    /** Changes the screen as the multiplexer: vanilla side effects on the real main-window state, no routing. */
    public void showUnrouted(@Nullable final GuiScreen s) {
        ScopePipeline.of(new UnroutedMainScreenScope(multiplexer), new ScreenScope(this))
            .run(() -> mc.displayGuiScreen(s));
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
        syncOpenContainer();
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
        if (globalsBorrowed() || !multiplexer.isInWorld()) return;
        final boolean mainNull = mc.currentScreen == null;
        final boolean active = Display.isActive();
        final boolean transition = !lastMainNull || !lastActive;
        if (mainNull && active && !mc.inGameHasFocus && transition) mc.setIngameFocus();
        lastMainNull = mainNull;
        lastActive = active;
    }
}
