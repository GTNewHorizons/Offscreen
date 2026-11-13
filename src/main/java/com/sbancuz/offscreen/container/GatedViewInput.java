package com.sbancuz.offscreen.container;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjglx.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * Input that reaches a gated view. A press asks to make the view's container the server's
 * open one and is held, to be replayed once the view receives input again; Escape and the
 * inventory key close the view; the refresh key, or a press on the badge, refreshes it.
 * The places feed their own events in: {@link com.sbancuz.offscreen.window.MainWindow} and
 * {@link com.sbancuz.offscreen.window.HostedScreen}.
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

    /** A press on a gated view: the badge refreshes, anything else requests activation and is held. */
    public void onPress(final ScreenView v, final int x, final int y, final int button) {
        final ServerContainer b = v.gatingContainer();
        if (multiplexer.overlay.badgeContains(b, x, y) && !multiplexer.isDegraded()) refresh(b);
        else requestActivation(new HeldClick(v, x, y, button));
    }

    public void onRelease(final int button) {
        if (held != null) held.onButtonReleased(button);
    }

    /** A key on a gated view; other keys do nothing, only a press activates. Returns true when the view closed. */
    public boolean onKey(final ScreenView v, final int key) {
        final boolean close = key == Keyboard.KEY_ESCAPE || key == mc.gameSettings.keyBindInventory.getKeyCode();
        if (close) multiplexer.contexts.current()
            .runAfterScope(() -> v.close(true));
        else if (key == refreshKey.getKeyCode()) refresh(v.gatingContainer());
        return close;
    }

    /** The held click goes to {@code v} if it was made in its place and still means the same there. */
    public void deliverHeldClick(final ScreenView v, final Consumer<HeldClick> deliver) {
        final HeldClick h = held;
        if (h == null || !h.armed || !h.view.samePlace(v) || !v.isActiveView()) return;
        held = null;
        if (h.meansTheSameOn(v.screen())) deliver.accept(h);
    }

    /** Switches to the view's container; the click is delivered once that is the active container. */
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
