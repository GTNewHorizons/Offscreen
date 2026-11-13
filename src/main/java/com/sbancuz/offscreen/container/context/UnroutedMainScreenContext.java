package com.sbancuz.offscreen.container.context;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/** The multiplexer changes the main window's screen itself: vanilla runs unrouted. */
public final class UnroutedMainScreenContext extends ExecutionContext {

    public UnroutedMainScreenContext(final ServerContainerMultiplexer multiplexer) {
        super(multiplexer);
    }

    @Override
    public void displayScreen(@Nullable final GuiScreen s, final Consumer<GuiScreen> vanilla) {
        vanilla.accept(s);
        multiplexer.globals.restoreMainWindowContainer();
    }
}
