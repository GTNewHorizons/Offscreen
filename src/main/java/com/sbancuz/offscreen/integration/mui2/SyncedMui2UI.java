package com.sbancuz.offscreen.integration.mui2;

import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.value.sync.ModularSyncManager;
import com.sbancuz.offscreen.Offscreen;

/**
 * {@link Mui2UI} for a server-synced screen: reuses its wrapper and ticks the sync
 * manager. Lifetime is owned by {@link MuiSyncedSession}; MUI close paths target
 * the main window, never call them here.
 */
public class SyncedMui2UI extends Mui2UI {

    private volatile Runnable disposeHook = () -> {};

    public SyncedMui2UI(final ModularScreen screen) {
        super(screen);
        if (screen.getScreenWrapper() == null || screen.getScreenWrapper()
            .getGuiScreen() == null) {
            throw new IllegalStateException("Synced MUI screen has no wrapper; use Mui2UI for client-only screens");
        }
    }

    void setDisposeHook(final Runnable disposeHook) {
        this.disposeHook = disposeHook;
    }

    /** Whether the screen is server-bound and needs this host instead of {@link Mui2UI}. */
    public static boolean isServerBound(final ModularScreen screen) {
        if (screen.getScreenWrapper() == null || screen.getScreenWrapper()
            .getGuiScreen() == null) {
            return false;
        }
        try {
            final ModularContainer container = screen.getContainer();
            return container != null && !container.isClientOnly() && container.getSyncManager() != null;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    @Override
    public void update() {
        super.update();
        try {
            final ModularContainer container = screen().getContainer();
            final ModularSyncManager msm = container.getSyncManager();
            if (msm != null && !msm.isClosed() && !msm.isDisposed()) {
                container.onUpdate();
            }
        } catch (final Throwable t) {
            Offscreen.LOG.warn("[Offscreen] synced MUI container tick failed", t);
        }
    }

    @Override
    public void dispose() {
        disposeHook.run();
    }
}
