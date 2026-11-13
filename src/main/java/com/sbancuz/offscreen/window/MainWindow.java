package com.sbancuz.offscreen.window;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjglx.input.Keyboard;
import org.lwjglx.opengl.Display;

import com.sbancuz.offscreen.compat.ModCompat;
import com.sbancuz.offscreen.compat.Mui2Compat;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * The main window's screen slot: moving its screen to a new hosted window with the move key,
 * and a hosted window's top screen back.
 */
@Lwjgl3Aware
public final class MainWindow {

    private final Minecraft mc = Minecraft.getMinecraft();

    private final KeyBinding moveKey = new KeyBinding("key.offscreen.move", Keyboard.KEY_F7, "key.offscreen");
    /** The move key is polled per frame: the screen's input handling drains the key events. */
    private boolean moveDown, movePending;

    public void register() {
        ClientRegistry.registerKeyBinding(moveKey);
        MinecraftForge.EVENT_BUS.register(this);
    }

    public @Nullable GuiScreen screen() {
        return mc.currentScreen;
    }

    private boolean isInWorld() {
        return mc.theWorld != null && mc.thePlayer != null;
    }

    // ================================================================== input and updates

    /** Whether runTick is handling the main window's own screen, in a world. */
    private boolean handlesOwnScreen(final GuiScreen screen) {
        return screen == mc.currentScreen && isInWorld();
    }

    /** Wraps {@code currentScreen.handleInput()} in {@code runTick}: the move key, then the screen's input. */
    public void onHandleInput(final GuiScreen screen, final Runnable original) {
        if (!handlesOwnScreen(screen)) {
            original.run();
            return;
        }
        final boolean move = movePending;
        movePending = false;
        if (move && moveToNewWindow()) return;

        original.run();
    }

    /** Drawn after the main window's screen: the MOVE key poll. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDrawScreenPost(final GuiScreenEvent.DrawScreenEvent.Post event) {
        if (event.gui != screen() || !isInWorld()) return;
        final int key = moveKey.getKeyCode();
        final boolean down = Display.isActive() && key > 0 && Keyboard.isKeyDown(key);
        movePending |= down && !moveDown;
        moveDown = down;
    }

    // ================================================================== moving screens between windows

    public boolean isMoveKey(final int keyCode) {
        return keyCode == moveKey.getKeyCode();
    }

    /** To a new hosted window: no displayGuiScreen, no onGuiClosed, no packet. */
    public boolean moveToNewWindow() {
        final GuiScreen s = screen();
        if (s == null) return false;
        final Window w = new Window(
            s.getClass()
                .getSimpleName());
        if (!w.isOpen()) return false;

        if (ModCompat.MODULAR_UI2.isLoaded()) Mui2Compat.detachFromMainWindow(s);

        mc.currentScreen = null;
        w.pushScreen(s);
        // the main window is the game again
        if (Display.isActive() && !mc.inGameHasFocus) mc.setIngameFocus();
        return true;
    }

    /** From hosted window {@code w}, only if the main window has no screen: its top screen moves. */
    public void moveFromWindow(final Window w) {
        final HostedStack st = w.stack();
        if (st.isEmpty() || screen() != null || !isInWorld()) return;
        final HostedScreen<?> moving = st.pop();

        if (!showMoved(moving.getGuiScreen())) {
            // a GuiOpenEvent listener refused: undo
            mc.displayGuiScreen(null);
            st.pushEntry(moving);
            return;
        }
        if (st.isEmpty()) w.destroy();
        else st.top()
            .requestResize();
    }

    /** Shows a moved screen; false when a GuiOpenEvent listener refused it. */
    private boolean showMoved(final GuiScreen s) {
        mc.displayGuiScreen(s);
        return mc.currentScreen == s;
    }
}
