package com.sbancuz.offscreen.scope.container.routing;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.scope.container.ServerContainerMultiplexer;

/** The multiplexer changes the main window's screen itself: {@code displayGuiScreen} is vanilla's, not routed. */
public final class UnroutedMainScreenScope extends RoutingScope {

    public UnroutedMainScreenScope(final ServerContainerMultiplexer multiplexer) {
        super(multiplexer);
    }

    @Override
    public void displayScreen(@Nullable final GuiScreen s, final Consumer<GuiScreen> vanilla) {
        vanilla.accept(s);
        multiplexer.mainWindow.syncOpenContainer();
    }
}
