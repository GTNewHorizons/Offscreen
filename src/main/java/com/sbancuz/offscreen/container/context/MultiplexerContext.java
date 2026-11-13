package com.sbancuz.offscreen.container.context;

import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/** Code the multiplexer runs, and adapter writes it asks for: what it sends passes unchecked. */
public class MultiplexerContext extends ExecutionContext {

    public MultiplexerContext(final ServerContainerMultiplexer multiplexer) {
        super(multiplexer);
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
}
