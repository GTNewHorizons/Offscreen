package com.sbancuz.offscreen.container.context;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/**
 * The multiplexer changes the main window's screen itself: vanilla runs unrouted, against the real main-window
 * state. A run that has Minecraft's GUI globals lent is suspended meanwhile and resumed afterwards.
 */
public final class UnroutedMainScreenContext extends ExecutionContext {

    private boolean suspendedLoans;

    public UnroutedMainScreenContext(final ServerContainerMultiplexer multiplexer) {
        super(multiplexer);
    }

    @Override
    public void enter() {
        suspendedLoans = multiplexer.globals.suspendLoans();
        super.enter();
    }

    @Override
    public void restore() {
        super.restore();
        if (suspendedLoans) multiplexer.globals.resumeLoans();
    }

    @Override
    public void displayScreen(@Nullable final GuiScreen s, final Consumer<GuiScreen> vanilla) {
        vanilla.accept(s);
        multiplexer.globals.restoreMainWindowContainer();
    }
}
