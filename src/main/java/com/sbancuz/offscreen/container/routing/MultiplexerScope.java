package com.sbancuz.offscreen.container.routing;

import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/** Code the multiplexer runs, and adapter writes it asks for: what it sends passes unchecked. */
public final class MultiplexerScope extends RoutingScope {

    private final boolean silent;

    public MultiplexerScope(final ServerContainerMultiplexer multiplexer) {
        this(multiplexer, false);
    }

    /** {@code silent}: nothing sent from here reaches the server (a client container's close logic). */
    public MultiplexerScope(final ServerContainerMultiplexer multiplexer, final boolean silent) {
        super(multiplexer);
        this.silent = silent;
    }

    @Override
    public boolean actsForActiveView() {
        return true;
    }

    @Override
    boolean mayClick() {
        return true;
    }

    @Override
    boolean isMultiplexer() {
        return true;
    }

    @Override
    boolean blocksOutbound() {
        return silent || super.blocksOutbound();
    }
}
