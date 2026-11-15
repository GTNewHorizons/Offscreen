package com.sbancuz.offscreen.container;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;

import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;

import cpw.mods.fml.client.registry.ClientRegistry;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * The gate in front of every view's input, the same for the main window and for hosted windows: it wraps the
 * screen's own {@code handleInput()} inside the view's run, where lwjgl's {@code Mouse} and {@code Keyboard} hold
 * the events of the view's window. A view that receives input handles them itself. A gated view's events end
 * here: a press asks to make the view's container the server's open one and is held until the view receives
 * input again; Escape and the inventory key close the view; the refresh key, or a press on the badge, refreshes
 * it.
 */
@Lwjgl3Aware
public final class GatedViewInput {

    private final ServerContainerMultiplexer multiplexer;
    private final Minecraft mc = Minecraft.getMinecraft();
    private final KeyBinding refreshKey = new KeyBinding("key.offscreen.refresh", Keyboard.KEY_F6, "key.offscreen");
    private @Nullable HeldClick held;

    GatedViewInput(final ServerContainerMultiplexer multiplexer) {
        this.multiplexer = multiplexer;
    }

    void register() {
        ClientRegistry.registerKeyBinding(refreshKey);
    }

    /** {@code original} is the view's own {@code handleInput()}; a gated view never reaches it. */
    public void handleInput(final ScreenView v, final Runnable original) {
        if (v.receivesInput()) {
            queueHeldClick(v);
            original.run();
            return;
        }

        // drained here, so nothing later (the game's own loops in the main window) sees a gated view's events
        while (Mouse.next()) onGatedMouseEvent(v);
        while (Keyboard.next()) {
            if (Keyboard.getEventKeyState() && onGatedKey(v, Keyboard.getEventKey())) break;
        }
    }

    /** A press on the badge refreshes, any other requests activation and is held; a release may be the held one's. */
    private void onGatedMouseEvent(final ScreenView v) {
        final int button = Mouse.getEventButton();
        if (button < 0) return;
        if (!Mouse.getEventButtonState()) {
            if (held != null) held.onButtonReleased(button);
            return;
        }

        final GuiScreen s = v.screen();
        final int x = guiX(s, Mouse.getEventX());
        final int y = guiY(s, Mouse.getEventY());
        final ServerContainer b = v.gatingContainer();
        if (multiplexer.overlay.badgeContains(b, x, y) && !multiplexer.isDegraded()) refresh(b);
        else requestActivation(new HeldClick(v, x, y, button));
    }

    /** A key on a gated view; other keys do nothing, only a press activates. Returns true when the view closes. */
    private boolean onGatedKey(final ScreenView v, final int key) {
        final boolean close = key == Keyboard.KEY_ESCAPE || key == mc.gameSettings.keyBindInventory.getKeyCode();
        if (close) multiplexer.contexts.current()
            .runAfterScope(() -> v.close(true));
        else if (key == refreshKey.getKeyCode()) refresh(v.gatingContainer());
        return close;
    }

    /**
     * The held press becomes the next mouse event of {@code v}, which then handles it like any other, along with
     * the hooks mods patch into its input. It is only queued if it was made in this place and still means the same
     * where the pointer is now.
     */
    private void queueHeldClick(final ScreenView v) {
        final HeldClick h = held;
        if (h == null || !h.armed || !h.view.samePlace(v) || !v.isActiveView()) return;
        held = null;

        // what the mouse did while the view waited is dropped, except the held button coming up
        while (Mouse.next()) {
            if (!Mouse.getEventButtonState()) h.onButtonReleased(Mouse.getEventButton());
        }
        final GuiScreen s = v.screen();
        if (!h.meansTheSameAt(s, guiX(s, Mouse.getX()), guiY(s, Mouse.getY()))) return;
        Mouse.addButtonEvent(h.button(), true);
        if (h.released()) Mouse.addButtonEvent(h.button(), false);
    }

    /** lwjgl's pixel as a GUI position, converted as {@code GuiScreen.handleMouseInput} does. */
    private int guiX(final GuiScreen s, final int pixelX) {
        return pixelX * s.width / Math.max(1, mc.displayWidth);
    }

    private int guiY(final GuiScreen s, final int pixelY) {
        return s.height - pixelY * s.height / Math.max(1, mc.displayHeight) - 1;
    }

    /** Switches to the view's container; the click is queued once that is the active container. */
    private void requestActivation(final HeldClick click) {
        if (multiplexer.isDegraded()) {
            multiplexer.resetToPlayerInventory(); // the server state is unknown: a press retries the reset
            return;
        }

        final ServerContainer b = click.view.gatingContainer();
        final OverlayHint refused = multiplexer.switchTo(b, false, ok -> click.armed = ok);
        b.showHint(refused);
        if (refused == OverlayHint.NONE) held = click;
    }

    /** Refreshes the contents of S: switches to S, and back to the active container F once S is open. */
    private void refresh(final ServerContainer s) {
        final ServerContainer f = multiplexer.activeContainer();
        if (!multiplexer.isIdle() || s == f) return;
        if (!f.canReopen()) {
            s.showHint(OverlayHint.REFRESH_WOULD_LOSE); // F could not come back
            return;
        }

        s.showHint(multiplexer.switchTo(s, false, ok -> {
            // if the way back is refused, S stays the active container and F stays paused
            if (ok) multiplexer.whenIdle(() -> f.showHint(multiplexer.switchTo(f, false, ServerProbes.IGNORE)));
        }));
    }
}
