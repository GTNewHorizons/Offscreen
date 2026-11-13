package com.sbancuz.offscreen.window;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.compat.ModCompat;
import com.sbancuz.offscreen.compat.NeiCompat;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.scope.Scope;
import com.sbancuz.offscreen.scope.ScopePipeline;
import com.sbancuz.offscreen.window.input.FrameEvent;
import com.sbancuz.offscreen.window.input.MouseShadow;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * The screens of one hosted window, bottom to top. Every change goes through the container
 * screen routing of the {@link ServerContainerMultiplexer}; this class only keeps the list and runs
 * scoped code for one entry.
 */
public class HostedStack extends ObjectArrayList<HostedScreen<?>> {

    private final ScopePipeline windowScopes = ScopePipeline.builder()
        .ifMod(ModCompat.NEI, () -> NeiCompat.windowScope(this::resolveCurrentContainer))
        .build();

    private @Nullable HostedScreen<?> running;

    private @Nullable GuiContainer resolveCurrentContainer() {
        final HostedScreen<?> t = running != null ? running : isEmpty() ? null : top();
        if (t == null) return null;
        final GuiScreen gs = t.screen();
        return gs instanceof GuiContainer gc ? gc : null;
    }

    public @Nullable HostedScreen<?> findEntry(final GuiScreen screen) {
        for (final HostedScreen<?> hostedScreen : this) {
            if (hostedScreen.screen() == screen) return hostedScreen;
        }
        return null;
    }

    /** The entry {@code entry} hangs on: the nearest bound entry at or below it, else itself. */
    public HostedScreen<?> boundBaseOf(final HostedScreen<?> entry) {
        for (int i = indexOf(entry); i >= 0; i--) {
            final HostedScreen<?> e = get(i);
            if (ServerContainerMultiplexer.INSTANCE.containerOf(e.screen()) != null) return e;
        }
        return entry;
    }

    public void pushEntry(final HostedScreen<?> e) {
        e.requestResize();
        add(e);
    }

    /** Runs {@code action} for the top entry, in its scope and for its view. */
    public void runScoped(final Consumer<HostedScreen<?>> action) {
        if (isEmpty()) return;
        runScoped(FrameEvent.EMPTY, -1, -1, top(), true, action);
    }

    /** Runs {@code action} for {@code entry}, which need not be in the stack any more (disposal). */
    public void runScopedOn(final HostedScreen<?> entry, final boolean applyDeferred,
        final Consumer<HostedScreen<?>> action) {
        runScoped(FrameEvent.EMPTY, -1, -1, entry, applyDeferred, action);
    }

    public void runScoped(final FrameEvent event, final int pixelWidth, final int pixelHeight,
        final HostedScreen<?> entry, final boolean applyDeferred, final Consumer<HostedScreen<?>> action) {
        final HostedScreen<?> prevRunning = running;
        running = entry;

        try {
            ServerContainerMultiplexer.INSTANCE.contexts
                .runInHostedView(entry, () -> runScopes(event, pixelWidth, pixelHeight, entry, action), applyDeferred);
        } finally {
            running = prevRunning;
        }
    }

    /** Every scope is entered inside the try whose finally restores it. */
    private void runScopes(final FrameEvent event, final int pixelWidth, final int pixelHeight,
        final HostedScreen<?> entry, final Consumer<HostedScreen<?>> action) {
        try {
            windowScopes.enter();
            runEntryScope(event, pixelWidth, pixelHeight, entry, action);
        } catch (final Throwable t) {
            Offscreen.LOG.error("[Offscreen] scoped action failed", t);
        } finally {
            windowScopes.restore();
        }
    }

    /** The entry's own scope and the mouse shadow, inside the window scopes. */
    private void runEntryScope(final FrameEvent event, final int pixelWidth, final int pixelHeight,
        final HostedScreen<?> entry, final Consumer<HostedScreen<?>> action) {
        final Scope own = entry.scope();
        try {
            own.enter();
            MouseShadow.set(event.mouseX, event.mouseY, pixelWidth, pixelHeight);
            action.accept(entry);
        } finally {
            MouseShadow.clear();
            own.restore();
        }
    }

    /** Drops every entry without telling the multiplexer (world unload, window destruction). */
    void disposeAll() {
        while (!isEmpty()) pop().dispose();
        windowScopes.restore();
    }
}
