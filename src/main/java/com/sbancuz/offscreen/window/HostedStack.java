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
import com.sbancuz.offscreen.compat.NeiCompat;
import com.sbancuz.offscreen.scope.ScopePipeline;
import com.sbancuz.offscreen.window.input.FrameEvent;
import com.sbancuz.offscreen.window.input.MouseShadow;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

public class HostedStack extends ObjectArrayList<HostedScreen<?>> {

    private final ScopePipeline windowScopes = ScopePipeline.builder()
        .ifMod(ModCompat.NEI, () -> NeiCompat.windowScope(this::resolveCurrentContainer))
        .build();

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

    public void popTo(final HostedScreen<?> target) {
        while (top() != target && size() > 1) {
            pop().dispose();
        }
    }

    public void runScoped(final Consumer<HostedScreen<?>> action) {
        runScoped(FrameEvent.EMPTY, -1, -1, action);
    }

    public void runScoped(final FrameEvent event, final int pixelWidth, final int pixelHeight,
        final Consumer<HostedScreen<?>> action) {
        final HostedScreen<?> screen = top();
        final GuiScreen savedScreen = Minecraft.getMinecraft().currentScreen;
        boolean poisoned = false;

        windowScopes.enter();
        try {
            screen.scope()
                .enter();

            try {
                MouseShadow.set(event.mouseX, event.mouseY, pixelWidth, pixelHeight);
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
                MouseShadow.clear();
                screen.scope()
                    .restore();
            }
        } finally {
            windowScopes.restore();
        }
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
            if (size() > 1) {
                final HostedScreen<?> t = pop();
                Offscreen.LOG.info("[Offscreen] pop {} (close-to-null, depth {})", t, size());
                t.dispose();
                top().requestResize();
            }
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
