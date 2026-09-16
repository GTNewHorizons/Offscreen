package com.sbancuz.offscreen.api;

import java.util.Collection;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.sbancuz.offscreen.core.Driver;
import com.sbancuz.offscreen.core.SessionRegistry;
import com.sbancuz.offscreen.window.Window;

public final class OffscreenAPI {

    private OffscreenAPI() {}

    /** Hosts a client-only screen (synced GUIs open through their factory). */
    public static @Nullable Window open(Supplier<?> screenFactory) {
        final Object screen = screenFactory.get();
        if (screen == null) return null;
        final HostUI ui = UIRegistry.resolve(screen);
        if (ui == null) return null;
        final Window w = new Window(screen.toString());
        w.setUI(ui);
        return w;
    }

    public static <H extends HostUI> @Nullable Window open(Supplier<?> screenFactory, HostUIFactory<H> factory) {
        final Object screen = screenFactory.get();
        if (screen == null) return null;
        final HostUI ui = factory.create(screen);
        if (ui == null) return null;
        final Window w = new Window(screen.toString());
        w.setUI(ui);
        return w;
    }

    public static void close(@Nullable Window window) {
        if (window == null) return;
        final Session session = SessionRegistry.INSTANCE.get(window);
        if (session != null) session.dispose();
        else window.destroy();
    }

    public static void closeAll() {
        Driver.INSTANCE.closeAll();
    }

    public static void closeAll(@Nullable Predicate<Window> predicate) {
        if (predicate == null) return;
        Driver.INSTANCE.closeAll(predicate);
    }

    public static Collection<Window> getWindows() {
        return Driver.INSTANCE.getWindows();
    }

    public static int getWindowCount() {
        return Driver.INSTANCE.getWindowCount();
    }

    public static @Nullable Window getWindow(int id) {
        return Driver.INSTANCE.getWindow(id);
    }

    public static boolean isOpen(@Nullable Window window) {
        return window != null && window.isOpen();
    }

    public static void push(@Nullable Window window, @Nullable HostUI ui) {
        if (window == null || ui == null) return;
        window.push(ui);
    }

    public static void push(@Nullable Window window, @Nullable Supplier<?> screenFactory) {
        if (window == null || screenFactory == null) return;
        final Object screen = screenFactory.get();
        if (screen == null) return;
        final HostUI ui = UIRegistry.resolve(screen);
        if (ui == null) return;
        window.push(ui);
    }

    public static boolean pop(@Nullable Window window) {
        if (window == null) return false;
        return window.pop();
    }

    public static @Nullable HostUI getCurrentUI(@Nullable Window window) {
        if (window == null) return null;
        return window.getCurrentUI();
    }

    public static int getScreenDepth(@Nullable Window window) {
        if (window == null) return 0;
        return window.getScreenDepth();
    }

    public static void resize(@Nullable Window window, int width, int height) {
        if (window == null) return;
        window.resize(width, height);
    }

    public static void setTitle(@Nullable Window window, @Nullable String title) {
        if (window == null || title == null) return;
        window.setTitle(title);
    }

    public static void setClearColor(@Nullable Window window, float r, float g, float b, float a) {
        if (window == null) return;
        window.setClearColor(r, g, b, a);
    }

    /** Packed {@code 0xAARRGGBB} variant of {@link #setClearColor(Window, float, float, float, float)}. */
    public static void setClearColor(@Nullable Window window, int argb) {
        if (window == null) return;
        window.setClearColor(argb);
    }

    public static void setBackground(@Nullable Window window, @Nullable Runnable background) {
        if (window == null) return;
        window.setBackground(background);
    }

    public static @Nullable Window findByTitle(@Nullable String title) {
        if (title == null) return null;
        for (Window w : Driver.INSTANCE.getWindows()) {
            if (w.getTitle()
                .equals(title)) {
                return w;
            }
        }
        return null;
    }

    public static @Nullable Window findByTitle(@Nullable Predicate<String> predicate) {
        if (predicate == null) return null;
        for (Window w : Driver.INSTANCE.getWindows()) {
            if (predicate.test(w.getTitle())) {
                return w;
            }
        }
        return null;
    }
}
