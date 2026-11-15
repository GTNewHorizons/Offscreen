package com.sbancuz.offscreen.container.context;

import com.sbancuz.offscreen.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.window.HostedScreen;

/**
 * A run of a hosted entry: the outermost scope of everything a hosted window runs for one of its screens. Inside
 * it Minecraft's GUI globals are the entry's (its screen, its container, the window's size), as they are the main
 * window's for a main-window screen; an entry whose container is not the server's open one runs here like any
 * other, it only acts for no active view.
 */
public final class HostedViewContext extends ViewContext {

    private final HostedScreen<?> entry;
    private final int pixelWidth, pixelHeight;

    public HostedViewContext(final ServerContainerMultiplexer multiplexer, final HostedScreen<?> entry,
        final int pixelWidth, final int pixelHeight, final boolean appliesDeferred) {
        super(multiplexer, entry, appliesDeferred);
        this.entry = entry;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
    }

    @Override
    public void enter() {
        multiplexer.globals.lend(entry.screen(), entry.containerForGlobals(), pixelWidth, pixelHeight);
        super.enter();
    }

    @Override
    public void restore() {
        multiplexer.globals.giveBack();
        // what the server opens in reply, before it confirms the order, belongs to this view
        if (passedServerDependentTraffic()) multiplexer.attributeArrivalsTo(entry);
        super.restore();
    }
}
