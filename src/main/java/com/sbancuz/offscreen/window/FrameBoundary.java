package com.sbancuz.offscreen.window;

import java.util.ArrayList;
import java.util.List;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.core.Driver;

/** Client thread only. */
public final class FrameBoundary {

    private static final List<Runnable> afterSwap = new ArrayList<>();
    private static final List<Runnable> running = new ArrayList<>();

    private FrameBoundary() {}

    static void afterSwap(final Runnable task) {
        afterSwap.add(task);
    }

    public static void onFrameSubmitted() {
        if (afterSwap.isEmpty()) return;
        running.addAll(afterSwap);
        afterSwap.clear();
        try {
            for (final Runnable task : running) {
                try {
                    task.run();
                } catch (final Throwable t) {
                    Offscreen.LOG.error("[Offscreen] post-frame task failed", t);
                }
            }
        } finally {
            running.clear();
        }
    }

    public static void onShutdown() {
        afterSwap.clear();
        for (final Window window : Driver.INSTANCE.getWindows()) {
            window.awaitPresentation();
        }
    }
}
