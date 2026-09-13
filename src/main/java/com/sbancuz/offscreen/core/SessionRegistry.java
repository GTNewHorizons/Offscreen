package com.sbancuz.offscreen.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.api.Session;
import com.sbancuz.offscreen.window.Window;

/**
 * Live {@link Session}s by owning window. Sessions dispose and untrack
 * themselves; only world unload disposes from here.
 */
public final class SessionRegistry {

    public static final SessionRegistry INSTANCE = new SessionRegistry();

    private final Map<Window, Session> sessions = new ConcurrentHashMap<>();

    private SessionRegistry() {}

    public void track(final Session session) {
        sessions.put(session.window(), session);
    }

    public void untrack(final Session session) {
        sessions.remove(session.window(), session);
    }

    public @Nullable Session get(final @Nullable Window window) {
        if (window == null) return null;
        return sessions.get(window);
    }

    public @Nullable Session find(final @Nullable Predicate<Session> filter) {
        if (filter == null) return null;
        for (final Session session : sessions.values()) {
            if (filter.test(session)) return session;
        }
        return null;
    }

    public void tickAll() {
        for (final Session session : sessions.values()) {
            try {
                session.tick();
            } catch (final Throwable t) {
                Offscreen.LOG.error("[Offscreen] session tick failed", t);
            }
        }
    }

    public void disposeAll() {
        for (final Session session : sessions.values()) {
            try {
                session.dispose();
            } catch (final Throwable t) {
                Offscreen.LOG.error("[Offscreen] session dispose failed", t);
            }
        }
        sessions.clear();
    }
}
