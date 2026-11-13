package com.sbancuz.offscreen.container;

import java.util.function.Supplier;

import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.window.Window;

/**
 * An API open: what the server opens until its probe is answered goes to the requested window, created on the first
 * one.
 */
final class ApiOpenRequest implements ArrivalTarget {

    private final ServerContainerMultiplexer multiplexer;
    private Supplier<Window> window;

    ApiOpenRequest(final ServerContainerMultiplexer multiplexer, final Supplier<Window> window) {
        this.multiplexer = multiplexer;
        this.window = window;
    }

    @Override
    public boolean canPlace() {
        return true;
    }

    /** On top of what the window shows, or in the main window if the window could not open. */
    @Override
    public Runnable place(final GuiScreen s) {
        final Window w = window.get();
        window = () -> w;
        if (!w.isOpen()) return multiplexer.mainWindow.place(s);
        w.pushNow(s);
        return ServerProbes.NOTHING;
    }
}
