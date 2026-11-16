package com.sbancuz.offscreen.window;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.api.UIRegistry;
import com.sbancuz.offscreen.compat.ModCompat;
import com.sbancuz.offscreen.container.ServerContainer;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.container.ServerProbes;
import com.sbancuz.offscreen.container.routing.ViewScope;
import com.sbancuz.offscreen.core.Driver;
import com.sbancuz.offscreen.integration.nei.NeiScope;
import com.sbancuz.offscreen.integration.vanilla.VanillaUI;
import com.sbancuz.offscreen.scope.ScopePipeline;
import com.sbancuz.offscreen.window.input.HostedInput;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * The screens of one hosted window, bottom to top: a screen its GUI opens is pushed over it, and closing it shows
 * the one below again. Every change to the stack is made here, and so is every run of an entry's code.
 */
public class HostedStack extends ObjectArrayList<HostedScreen<?>> {

    private final ServerContainerMultiplexer multiplexer = ServerContainerMultiplexer.INSTANCE;
    private final Window window;
    private final ScopePipeline windowScopes;

    /** The last entry was removed: the window has nothing left to show. */
    private boolean emptied;

    /** {@code input} is the window's lwjgl input, live while any of its screens runs. */
    HostedStack(final Window window, final HostedInput input) {
        this.window = window;
        this.windowScopes = ScopePipeline.builder()
            .always(input)
            .ifMod(ModCompat.NEI, () -> new NeiScope(this::resolveCurrentContainer, this::resolveMainWindowContainer))
            .build();
    }
    private @Nullable GuiContainer resolveMainWindowContainer() {
        return multiplexer.mainWindow.screen() instanceof GuiContainer gc ? gc : null;
    }

    /** The container screen of the entry whose code runs now, for NEI. */
    private @Nullable GuiContainer resolveCurrentContainer() {
        final GuiScreen running = multiplexer.routing()
            .view()
            .screen();
        return running instanceof GuiContainer gc ? gc : null;
    }

    public @Nullable HostedScreen<?> findEntry(final GuiScreen screen) {
        for (final HostedScreen<?> hostedScreen : this) {
            if (hostedScreen.screen() == screen) return hostedScreen;
        }
        return null;
    }

    /** The entry {@code entry} hangs on: the nearest container screen at or below it, else itself. */
    public HostedScreen<?> boundBaseOf(final HostedScreen<?> entry) {
        for (int i = indexOf(entry); i >= 0; i--) {
            final HostedScreen<?> e = get(i);
            if (multiplexer.containerOf(e.screen()) != null) return e;
        }
        return entry;
    }

    /** Whether the stack lost its last entry and got no new one since. */
    boolean wasEmptied() {
        return emptied && isEmpty();
    }

    // ================================================================== running an entry's code

    /**
     * Runs {@code action} on the top screen in its scopes, so that Minecraft's globals, lwjgl's input and the
     * statics of other mods are that screen's, as they are the main window's for a main-window screen.
     */
    public void runScoped(final Consumer<HostedScreen<?>> action) {
        if (!isEmpty()) runScoped(top(), true, action);
    }

    /**
     * The scopes of a run, outermost first: the entry's view (who the code acts for), its screen (Minecraft's GUI
     * globals), the window's (lwjgl input, NEI), the UI's own. Screen changes the entry's GUI asks for meanwhile are
     * applied
     * when the view's scope ends, unless {@code applyRequests} is false (layout, disposal).
     */
    private void runScoped(final HostedScreen<?> entry, final boolean applyRequests,
        final Consumer<HostedScreen<?>> action) {
        // hosted screens live in a world; between its unload and the windows closing nothing runs
        if (!multiplexer.isInWorld()) return;
        final ViewScope view = new ViewScope(multiplexer, entry, applyRequests);
        try {
            ScopePipeline.of(view, entry.screenScope(), windowScopes, entry.scope())
                .run(() -> action.accept(entry));
        } catch (final Throwable t) {
            Offscreen.LOG.error("[Offscreen] scoped action failed", t);
        }
    }

    // ================================================================== changing the stack

    public HostedScreen<?> pushUI(final HostUI ui) {
        final HostedScreen<?> e = new HostedScreen<>(window, ui);
        e.requestResize();
        add(e);
        emptied = false;
        return e;
    }

    /** Pushes the entry for {@code s}; every screen has a host UI, vanilla's at least. */
    public HostedScreen<?> pushScreen(final GuiScreen s) {
        if (UIRegistry.resolve(s) instanceof HostUI ui) return pushUI(ui);
        return pushUI(new VanillaUI(s));
    }

    /** Pushes {@code s} and lays it out now: initGui must run before the packet handler writes the window id. */
    public HostedScreen<?> pushNow(final GuiScreen s) {
        final HostedScreen<?> e = pushScreen(s);
        final ScaledResolution resolution = new ScaledResolution(
            Minecraft.getMinecraft(),
            window.getPixelWidth(),
            window.getPixelHeight());
        runScoped(e, false, entry -> entry.resize(resolution.getScaledWidth(), resolution.getScaledHeight()));
        return e;
    }

    /**
     * Removes {@code e} and everything above it, each as the player closing it would: its {@code onGuiClosed}
     * inside its scopes, then disposal. Containers that lose their view this way are left to the caller.
     */
    public void removeFrom(final HostedScreen<?> e) {
        while (contains(e)) {
            final HostedScreen<?> closing = pop();
            runScoped(
                closing,
                false,
                s -> s.screen()
                    .onGuiClosed());
            closing.dispose();
        }

        // an emptied window is destroyed by its owner on its next frame or tick
        if (isEmpty()) emptied = true;
        else top().requestResize();
    }

    /**
     * A GUI the server opened is pushed over {@code entry}; once its container is known it replaces {@code entry}
     * if that shows a container too.
     */
    Runnable placeArrivalOver(final HostedScreen<?> entry, final GuiScreen s) {
        if (!window.isOpen()) return Driver.INSTANCE.mainWindow.place(s);
        final HostedScreen<?> arrived = pushNow(s);
        if (multiplexer.containerOf(entry.screen()) == null) return ServerProbes.NOTHING;
        return () -> {
            if (!contains(entry) || !remove(arrived)) return;
            removeFrom(entry);
            add(arrived);
            arrived.requestResize();
            emptied = false;
        };
    }

    /**
     * The GUI of {@code entry} displayed {@code incoming}: back to a screen of the stack, a child, or a replacement.
     */
    void applyDisplayRequest(final HostedScreen<?> entry, final GuiScreen incoming) {
        final HostedScreen<?> existing = findEntry(incoming);
        if (existing != null) {
            // back to a screen of this stack: the ones above it close
            final int above = indexOf(existing) + 1;
            if (above < size()) removeFrom(get(above));
            return;
        }

        final ServerContainer old = entry.gatingContainer();
        final ServerContainer now = multiplexer.containerOf(incoming);
        if (!contains(entry) || old == null || now == null || now == old) {
            pushScreen(incoming); // a child of the entry
            return;
        }

        // the GUI replaced itself by one of another container (Baubles back to the inventory)
        removeFrom(boundBaseOf(entry));
        pushScreen(incoming);
        multiplexer.viewClosed(old, false);
    }

    /** Drops every entry without closing it for the multiplexer (world unload, window destruction). */
    void disposeAll() {
        while (!isEmpty()) pop().dispose();
        windowScopes.restore();
    }
}
