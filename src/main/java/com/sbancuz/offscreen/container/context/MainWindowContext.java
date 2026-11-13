package com.sbancuz.offscreen.container.context;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.container.ContainerAdapter.PayloadEffect;
import com.sbancuz.offscreen.container.ScreenView;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/** The bottom context: the main window, acting for its view while it shows a container screen. */
final class MainWindowContext extends ExecutionContext {

    MainWindowContext(final ServerContainerMultiplexer multiplexer) {
        super(multiplexer);
    }

    @Override
    public boolean actsForActiveView() {
        return multiplexer.mainWindow.isActiveView();
    }

    @Override
    boolean blocksOutbound() {
        return false;
    }

    @Override
    PayloadEffect effectOfUnknownPayload() {
        return multiplexer.mainWindow.effectOfUnknownPayload();
    }

    @Override
    public void displayScreen(@Nullable final GuiScreen s, final Consumer<GuiScreen> vanilla) {
        multiplexer.mainWindow.onDisplayRequested(s, vanilla);
    }

    @Override
    public void closeScreen(final boolean packet) {
        multiplexer.mainWindow.onCloseRequested(packet);
    }

    @Override
    public void requestReconcile() {
        multiplexer.reconcileWithServer();
    }

    @Override
    public ScreenView view() {
        return multiplexer.mainWindow;
    }
}
