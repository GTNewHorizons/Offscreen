package com.sbancuz.offscreen.api;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Registry mapping screen types to {@link HostUIFactory} implementations.
 * <p>
 * Resolution walks the screen's class hierarchy from concrete → root,
 * returning the first registered match (nearest superclass).
 */
public final class UIRegistry {

    private static final List<Registration<?>> registrations = new ArrayList<>();

    private UIRegistry() {}

    public static <T, H extends HostUI> void register(Class<T> screenType, HostUIFactory<H> factory) {
        registrations.add(new Registration<>(screenType, factory));
    }

    public static @Nullable HostUI resolve(Object screen) {
        if (screen == null) return null;
        final Class<?> screenClass = screen.getClass();

        for (Registration<?> reg : registrations) {
            if (reg.screenType == screenClass) {
                return reg.factory.create(screen);
            }
        }

        for (Class<?> c = screenClass.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Registration<?> reg : registrations) {
                if (reg.screenType == c) {
                    return reg.factory.create(screen);
                }
            }
        }

        return null;
    }

    public static boolean isRegistered(Class<?> screenType) {
        for (Registration<?> reg : registrations) {
            if (reg.screenType == screenType) return true;
        }
        return false;
    }

    public static boolean unregister(Class<?> screenType) {
        return registrations.removeIf(r -> r.screenType == screenType);
    }

    public static void clear() {
        registrations.clear();
    }

    private record Registration<T> (Class<T> screenType, HostUIFactory<?> factory) {}
}
