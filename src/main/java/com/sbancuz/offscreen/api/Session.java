package com.sbancuz.offscreen.api;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import net.minecraft.client.Minecraft;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.core.SessionRegistry;
import com.sbancuz.offscreen.window.Window;

/**
 * Lifecycle of a server-bound UI in an offscreen {@link Window}: off-thread
 * handoff, per-tick health check, close handshake. The base class runs the state
 * machine on the main client thread; frameworks fill the hooks below.
 */
public abstract class Session {

    private static final int DEFAULT_OPEN_TIMEOUT_TICKS = 200;

    private final Window window;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private boolean awaiting;
    private int awaitingTicks;
    private int timeoutTicks = DEFAULT_OPEN_TIMEOUT_TICKS;

    protected Session(final Window window) {
        this.window = window;
    }

    /** The owning window. Sessions and windows are always created 1:1. */
    public final @Nonnull Window window() {
        return window;
    }

    public final boolean isClosed() {
        return closed.get();
    }

    /** Main-thread maintenance, called once per client tick by the driver. */
    public final void tick() {
        if (closed.get()) return;
        poll();
        if (closed.get()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (!window.isOpen() || mc.theWorld == null || mc.thePlayer == null) {
            dispose();
            return;
        }
        if (isAlive()) return;
        if (!awaiting && shouldHeal()) {
            try {
                heal();
            } catch (final Throwable t) {
                Offscreen.LOG.error("[Offscreen] session heal failed", t);
                dispose();
            }
            return;
        }
        if (awaiting && ++awaitingTicks <= timeoutTicks) return;
        dispose();
    }

    /** Idempotent teardown: untracks, releases the binding, destroys the window. */
    public final void dispose() {
        if (!closed.compareAndSet(false, true)) return;
        awaiting = false;
        awaitingTicks = 0;
        SessionRegistry.INSTANCE.untrack(this);
        try {
            onRelease();
        } catch (final Throwable e) {
            Offscreen.LOG.error("[Offscreen] failed to release session", e);
        } finally {
            try {
                if (window.isOpen()) OffscreenAPI.close(window);
            } catch (final Throwable e) {
                Offscreen.LOG.error("[Offscreen] failed to close session window", e);
            }
        }
    }

    /** Attaches anything that arrived off-thread. Default no-op. */
    protected void poll() {}

    /** Whether the server binding is currently healthy. */
    protected abstract boolean isAlive();

    /** Binding died while not awaiting: heal it or drop the session. */
    protected abstract boolean shouldHeal();

    /** Re-establishes the binding into the same window. */
    protected abstract void heal();

    /** Framework teardown: server handshake and clearing framework refs. */
    protected abstract void onRelease();

    /** Starts (or restarts) the open timeout, e.g. when (re-)requesting. */
    protected final void beginAwait() {
        awaiting = true;
        awaitingTicks = 0;
    }

    protected final boolean isAwaiting() {
        return awaiting && !closed.get();
    }

    /** Consumes the awaiting flag, returning whether an answer was expected. */
    protected final boolean takeAwaiting() {
        final boolean keep = awaiting;
        awaiting = false;
        return keep;
    }
}
