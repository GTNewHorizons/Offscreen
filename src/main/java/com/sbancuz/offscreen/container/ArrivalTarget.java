package com.sbancuz.offscreen.container;

import net.minecraft.client.gui.GuiScreen;

/** Where a GUI the server opens is shown: a view, an API request, or nowhere. */
public interface ArrivalTarget extends ArrivalCause {

    /** The running switch's target lost its view: the GUI is not shown, and closes since nothing shows it. */
    ArrivalTarget HIDDEN = new ArrivalTarget() {

        @Override
        public boolean canPlace() {
            return true;
        }

        @Override
        public Runnable place(final GuiScreen s) {
            return ServerProbes.NOTHING;
        }
    };

    /** Whether a GUI can still be shown here. */
    boolean canPlace();

    /** Shows {@code s} while its packet is handled; returns what to do once its container is known. */
    Runnable place(GuiScreen s);
}
