package com.sbancuz.offscreen.container.context;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.container.ContainerAdapter.PayloadEffect;
import com.sbancuz.offscreen.container.ScreenView;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/** Any thread but the main one: no view, no routing. */
final class OffThreadContext extends ExecutionContext {

    OffThreadContext(final ServerContainerMultiplexer multiplexer) {
        super(multiplexer);
    }

    @Override
    public boolean actsForActiveView() {
        return false;
    }

    @Override
    boolean mayClick() {
        return true;
    }

    @Override
    boolean blocksOutbound() {
        return false;
    }

    @Override
    boolean onMainThread() {
        return false;
    }

    @Override
    PayloadEffect effectOfUnknownPayload() {
        return PayloadEffect.NEUTRAL;
    }

    @Override
    public void displayScreen(@Nullable final GuiScreen s, final Consumer<GuiScreen> vanilla) {
        vanilla.accept(s);
    }

    @Override
    public void closeScreen(final boolean packet) {}

    @Override
    public void requestReconcile() {}

    @Override
    public ScreenView view() {
        return multiplexer.mainWindow;
    }
}
