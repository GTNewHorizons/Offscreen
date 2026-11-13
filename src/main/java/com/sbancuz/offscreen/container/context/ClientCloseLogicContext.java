package com.sbancuz.offscreen.container.context;

import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/** A client container's close logic runs, once per container; nothing it sends reaches the server. */
final class ClientCloseLogicContext extends MultiplexerContext {

    ClientCloseLogicContext(final ServerContainerMultiplexer multiplexer) {
        super(multiplexer);
    }

    @Override
    boolean blocksOutbound() {
        return true;
    }
}
