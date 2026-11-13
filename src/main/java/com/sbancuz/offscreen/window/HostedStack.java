package com.sbancuz.offscreen.window;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.api.UIRegistry;
import com.sbancuz.offscreen.compat.ModCompat;
import com.sbancuz.offscreen.integration.nei.NeiScope;
import com.sbancuz.offscreen.scope.ScopePipeline;
import com.sbancuz.offscreen.window.input.HostedInput;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

public class HostedStack extends ObjectArrayList<HostedScreen<?>> {

    private final ScopePipeline windowScopes;

    /** {@code input} is the window's lwjgl input, live while any of its screens runs. */
    public HostedStack(final HostedInput input) {
        windowScopes = ScopePipeline.builder()
            .always(input)
            .ifMod(ModCompat.NEI, () -> new NeiScope(this::resolveCurrentContainer))
            .build();
    }

    private @Nullable GuiContainer resolveCurrentContainer() {
        final HostedScreen<?> t = top();
        if (t == null) return null;
        final GuiScreen gs = t.getGuiScreen();
        return gs instanceof GuiContainer gc ? gc : null;
    }

    @Override
    public void clear() {
        windowScopes.restore();
        super.clear();
    }

    public @Nullable HostedScreen<?> findEntry(final GuiScreen screen) {
        for (HostedScreen<?> hostedScreen : this) {
            if (hostedScreen.getGuiScreen() == screen) return hostedScreen;
        }
        return null;
    }

    public void pushEntry(final HostedScreen<?> e) {
        e.requestResize();
        add(e);
    }

    public void popTo(final HostedScreen<?> target) {
        while (top() != target && size() > 1) {
            pop().dispose();
        }
    }

    /**
     * Runs {@code action} on the top screen in its scopes. Minecraft's display size is the window's pixel size
     * throughout, scopes included, so code sizing itself from it ({@code ScaledResolution}, scissor math, vanilla's
     * mouse conversion) sizes itself for this window.
     */
    public void runScoped(final int pixelWidth, final int pixelHeight,
        final Consumer<HostedScreen<? extends HostUI>> action) {
        final Minecraft mc = Minecraft.getMinecraft();
        final HostedScreen<?> screen = top();
        final GuiScreen savedScreen = mc.currentScreen;
        final int mainWidth = mc.displayWidth, mainHeight = mc.displayHeight;
        boolean poisoned = false;

        mc.displayWidth = pixelWidth;
        mc.displayHeight = pixelHeight;
        windowScopes.enter();
        try {
            screen.scope()
                .enter();

            try {
                action.accept(screen);
            } catch (final Throwable t) {
                poisoned = true;
                Offscreen.LOG.error("[Offscreen] scoped action failed", t);
            } finally {
                if (!poisoned) {
                    try {
                        handleScreenChange(savedScreen);
                    } catch (final Throwable t) {
                        Offscreen.LOG.error("[Offscreen] screen-change handling failed", t);
                    }
                }
                screen.scope()
                    .restore();
            }
        } finally {
            windowScopes.restore();
            mc.displayWidth = mainWidth;
            mc.displayHeight = mainHeight;
        }
    }

    /**
     * Closes the top screen as the player closing it would: its {@code onGuiClosed} inside its scope, then disposal.
     */
    public void closeTop(final int pixelWidth, final int pixelHeight) {
        final HostedScreen<?> closing = top();
        runScoped(
            pixelWidth,
            pixelHeight,
            s -> s.getGuiScreen()
                .onGuiClosed());
        if (!remove(closing)) return; // it closed itself meanwhile
        closing.dispose();
        if (!isEmpty()) top().requestResize();
    }

    private void handleScreenChange(final GuiScreen prevTop) {
        final Minecraft mc = Minecraft.getMinecraft();
        final GuiScreen now = mc.currentScreen;
        if (now == prevTop) return;

        final HostedScreen<?> match = findEntry(now);
        if (match != null) {
            popTo(match);
            match.requestResize();
            return;
        }

        if (now == null) {
            // the screen closed itself (displayGuiScreen already ran its onGuiClosed): the one below shows, or the
            // window, now empty, is destroyed by its owner after the run
            final HostedScreen<?> closed = pop();
            Offscreen.LOG.info("[Offscreen] pop {} (close-to-null, depth {})", closed, size());
            closed.dispose();
            if (isEmpty()) return;
            top().requestResize();
            mc.currentScreen = top().getGuiScreen();
            return;
        }

        pushStolen(now);
    }

    public void pushStolen(final GuiScreen stolen) {
        final HostUI ui = UIRegistry.resolve(stolen);
        if (ui == null) {
            Offscreen.LOG.warn(
                "[Offscreen] no UI factory for stolen screen: {}",
                stolen.getClass()
                    .getSimpleName());
            return;
        }
        final HostedScreen<?> entry = new HostedScreen<>(ui);
        entry.requestResize();
        push(entry);
        Offscreen.LOG.info(
            "[Offscreen] STEAL {} (adapter: {}, depth {})",
            stolen.getClass()
                .getSimpleName(),
            ui.getClass()
                .getSimpleName(),
            size());
    }
}
