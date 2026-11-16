package com.sbancuz.offscreen.scope;

/**
 * State that is swapped while code runs for a screen that is not the main window's: lwjgl's input, the statics of
 * other mods, Minecraft's GUI globals. Whatever {@link #enter} changes, {@link #restore} puts back.
 */
public interface Scope {

    Scope NOP = new Scope() {

        @Override
        public void enter() {}

        @Override
        public void restore() {}
    };

    void enter();

    void restore();

    /** Runs {@code body} inside the scope. */
    default void run(final Runnable body) {
        enter();
        try {
            body.run();
        } finally {
            restore();
        }
    }
}
