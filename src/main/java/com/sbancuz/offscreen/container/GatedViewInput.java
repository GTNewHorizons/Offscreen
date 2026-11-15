package com.sbancuz.offscreen.container;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Slot;

import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;

import com.sbancuz.offscreen.mixins.early.GuiContainerAccessor;

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

    /** How far the pointer may have moved off the slots, in GUI pixels, for a held press to be made again. */
    private static final int POINTER_DRIFT = 2;
    private static final int NO_SLOT = -1;

    /** A press on a gated view and where it was made, kept while the server switches to the view's container. */
    private record HeldPress(ScreenView view, int button, int x, int y, int slotNumber) {}

    private final ServerContainerMultiplexer multiplexer;
    private final Minecraft mc = Minecraft.getMinecraft();
    private final KeyBinding refreshKey = new KeyBinding("key.offscreen.refresh", Keyboard.KEY_F6, "key.offscreen");
    private @Nullable HeldPress held;

    GatedViewInput(final ServerContainerMultiplexer multiplexer) {
        this.multiplexer = multiplexer;
    }

    void register() {
        ClientRegistry.registerKeyBinding(refreshKey);
    }

    /** {@code original} is the view's own {@code handleInput()}; a gated view never reaches it. */
    public void handleInput(final ScreenView v, final Runnable original) {
        if (v.receivesInput()) {
            queueHeldPress(v);
            original.run();
            return;
        }

        // drained here, so nothing later (the game's own loops in the main window) sees a gated view's events
        while (Mouse.next()) onGatedMouseEvent(v);
        while (Keyboard.next()) {
            if (Keyboard.getEventKeyState() && onGatedKey(v, Keyboard.getEventKey())) break;
        }
    }

    /** A press on the badge refreshes, any other requests activation and is held; nothing else does anything. */
    private void onGatedMouseEvent(final ScreenView v) {
        final int button = Mouse.getEventButton();
        if (button < 0 || !Mouse.getEventButtonState()) return;

        final GuiScreen s = v.screen();
        final int x = guiX(s, Mouse.getEventX());
        final int y = guiY(s, Mouse.getEventY());
        final ServerContainer b = v.gatingContainer();
        if (multiplexer.overlay.badgeContains(b, x, y) && !multiplexer.isDegraded()) refresh(b);
        else requestActivation(new HeldPress(v, button, x, y, slotNumberAt(s, x, y)));
    }

    /** A key on a gated view; other keys do nothing, only a press activates. Returns true when the view closes. */
    private boolean onGatedKey(final ScreenView v, final int key) {
        final boolean close = key == Keyboard.KEY_ESCAPE || key == mc.gameSettings.keyBindInventory.getKeyCode();
        if (close) multiplexer.routing()
            .runAfterScope(() -> v.close(true));
        else if (key == refreshKey.getKeyCode()) refresh(v.gatingContainer());
        return close;
    }

    /**
     * The held press becomes the next mouse event of {@code v}, which then handles it like any other, along with
     * the hooks mods patch into its input. lwjgl queues it where the pointer is now, and ModularUI2 clicks what is
     * hovered, so it is only queued while the pointer is over the slot it was made on (off the slots: where it was
     * made).
     */
    private void queueHeldPress(final ScreenView v) {
        final HeldPress h = held;
        if (h == null || !h.view.samePlace(v) || !v.isActiveView()) return;
        held = null;

        // what the mouse did while the view waited is dropped
        while (Mouse.next()) continue;
        final GuiScreen s = v.screen();
        final int x = guiX(s, Mouse.getX());
        final int y = guiY(s, Mouse.getY());
        final int slot = slotNumberAt(s, x, y);
        final boolean drifted = Math.abs(x - h.x) > POINTER_DRIFT || Math.abs(y - h.y) > POINTER_DRIFT;
        if (slot != h.slotNumber || slot == NO_SLOT && drifted) return;

        Mouse.addButtonEvent(h.button, true);
        // the button came up while the view waited
        if (!Mouse.isButtonDown(h.button)) Mouse.addButtonEvent(h.button, false);
    }

    private int slotNumberAt(@Nullable final GuiScreen s, final int guiX, final int guiY) {
        if (s instanceof GuiContainerAccessor gc && gc.offscreen$getSlotAtPosition(guiX, guiY) instanceof Slot slot) {
            return slot.slotNumber;
        }
        return NO_SLOT;
    }

    /** lwjgl's pixel as a GUI position, converted as {@code GuiScreen.handleMouseInput} does. */
    private int guiX(final GuiScreen s, final int pixelX) {
        return pixelX * s.width / Math.max(1, mc.displayWidth);
    }

    private int guiY(final GuiScreen s, final int pixelY) {
        return s.height - pixelY * s.height / Math.max(1, mc.displayHeight) - 1;
    }

    /** Switches to the view's container; the press is queued once that is the active container, dropped if not. */
    private void requestActivation(final HeldPress press) {
        if (multiplexer.isDegraded()) {
            multiplexer.resetToPlayerInventory(); // the server state is unknown: a press retries the reset
            return;
        }

        final ServerContainer b = press.view.gatingContainer();
        held = press;
        final OverlayHint refused = multiplexer.switchTo(b, false, ok -> { if (!ok && held == press) held = null; });
        b.showHint(refused);
        if (refused != OverlayHint.NONE) held = null;
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
