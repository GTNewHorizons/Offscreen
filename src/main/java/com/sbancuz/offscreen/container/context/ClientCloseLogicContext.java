package com.sbancuz.offscreen.container.context;

import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Container;

import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/** A client container's close logic runs, once per container; nothing it sends reaches the server. */
final class ClientCloseLogicContext extends MultiplexerContext {

    private final Container clientContainer;

    ClientCloseLogicContext(final ServerContainerMultiplexer multiplexer, final Container clientContainer) {
        super(multiplexer);
        this.clientContainer = clientContainer;
    }

    @Override
    public void enter() {
        multiplexer.globals.lend(Minecraft.getMinecraft().currentScreen, clientContainer);
        super.enter();
    }

    @Override
    public void restore() {
        super.restore();
        multiplexer.globals.giveBack();
    }

    @Override
    boolean blocksOutbound() {
        return true;
    }
}
