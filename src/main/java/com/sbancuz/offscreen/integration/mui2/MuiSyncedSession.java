package com.sbancuz.offscreen.integration.mui2;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.api.UIFactory;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.factory.GuiManager;
import com.cleanroommc.modularui.network.ModularNetwork;
import com.cleanroommc.modularui.network.NetworkHandler;
import com.cleanroommc.modularui.network.packets.CloseGuiPacket;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.value.sync.ModularSyncManager;
import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.api.Session;
import com.sbancuz.offscreen.core.SessionRegistry;
import com.sbancuz.offscreen.window.Window;

/**
 * Binds one offscreen {@link Window} to one server-synced MUI2 GUI. The divert
 * mixin captures the answering screen; it is attached on the next client tick.
 * Lookups run through the {@link SessionRegistry}; server closes never heal.
 */
public final class MuiSyncedSession extends Session {

    private record Arrival(UIFactory<?> factory, @Nullable ModularScreen screen, int networkId) {}

    /** In-flight arrival; HEAD fills factory, redirect adds the screen. */
    private static final ThreadLocal<Arrival> ARRIVING = new ThreadLocal<>();

    /** Binds a window to this factory and requests the server GUI. Self-disposes on failure. */
    MuiSyncedSession(final Window window, final UIFactory<?> factory, final GuiData guiData) {
        super(window);
        this.factory = factory;
        this.guiData = guiData;
        SessionRegistry.INSTANCE.track(this);
        try {
            request();
        } catch (final Throwable t) {
            Offscreen.LOG.error("[Offscreen] failed to request synced GUI '{}'", factory.getFactoryName(), t);
            dispose();
        }
    }

    /** Mixin HEAD: remember which factory this arriving open belongs to. */
    public static void onArriving(final UIFactory<?> factory, final int networkId) {
        ARRIVING.set(new Arrival(factory, null, networkId));
    }

    /** Mixin redirect: claims the screen for the awaiting session, if any. */
    public static boolean tryDivert(final GuiScreen screen) {
        final Arrival arrival = ARRIVING.get();
        ARRIVING.remove();
        if (arrival == null || !(screen instanceof IMuiScreen mui)) return false;
        final MuiSyncedSession target = findAwaiting(arrival.factory());
        if (target == null) return false;
        target.offerArrival(new Arrival(arrival.factory(), mui.getScreen(), arrival.networkId()));
        return true;
    }

    /** Mixin on container close: marks the death as server-intended. */
    public static void markServerClosed(final int networkId) {
        final Session found = SessionRegistry.INSTANCE
            .find(s -> s instanceof MuiSyncedSession session && !session.isClosed() && session.networkId == networkId);
        if (found instanceof MuiSyncedSession session) session.serverClosed = true;
    }

    /** Live session for a factory, if any. */
    static @Nullable MuiSyncedSession findActive(final UIFactory<?> factory) {
        final Session found = SessionRegistry.INSTANCE
            .find(s -> s instanceof MuiSyncedSession session && !session.isClosed() && session.factory == factory);
        return (MuiSyncedSession) found;
    }

    private static @Nullable MuiSyncedSession findAwaiting(final UIFactory<?> factory) {
        final Session found = SessionRegistry.INSTANCE.find(
            s -> s instanceof MuiSyncedSession session && !session.isClosed()
                && session.isAwaiting()
                && session.factory == factory);
        return (MuiSyncedSession) found;
    }

    private final UIFactory<?> factory;
    private final GuiData guiData;

    /** Parked off-thread, attached on tick. */
    private volatile Arrival pendingArrival;

    private volatile boolean serverClosed;
    private SyncedMui2UI host;
    private ModularSyncManager syncManager;
    private ModularContainer container;
    private int networkId = -1;

    /** Arms for the factory and asks the server to open the GUI. Initial open and heals. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void request() {
        beginAwait();
        GuiManager.openFromClient((UIFactory) factory, guiData);
    }

    void offerArrival(final Arrival arrival) {
        if (arrival.screen() == null) return;
        if (isClosed()) {
            sendClose(arrival.networkId());
            return;
        }
        pendingArrival = arrival;
    }

    @Override
    protected void poll() {
        final Arrival arrival = pendingArrival;
        if (arrival == null) return;
        pendingArrival = null;
        attach(arrival);
    }

    @Override
    protected boolean isAlive() {
        final ModularSyncManager msm = syncManager;
        return msm != null && !msm.isClosed() && !msm.isDisposed();
    }

    @Override
    protected boolean shouldHeal() {
        return !serverClosed && !(Minecraft.getMinecraft().currentScreen instanceof IMuiScreen);
    }

    @Override
    protected void heal() {
        request();
    }

    @Override
    protected void onRelease() {
        final Arrival parked = pendingArrival;
        pendingArrival = null;
        releaseBinding(container, networkId);
        if (parked != null && parked.screen() != null) {
            try {
                releaseBinding(
                    parked.screen()
                        .getContainer(),
                    parked.networkId());
            } catch (final RuntimeException e) {
                Offscreen.LOG.error("[Offscreen] parked arrival has no container", e);
                sendClose(parked.networkId());
            }
        }
        host = null;
        syncManager = null;
        container = null;
        networkId = -1;
    }

    private void attach(final Arrival arrival) {
        final boolean keep = takeAwaiting();
        final Minecraft mc = Minecraft.getMinecraft();
        final ModularScreen screen = arrival.screen();
        if (screen == null) {
            sendClose(arrival.networkId());
            dispose();
            return;
        }
        final ModularContainer arrived;
        try {
            arrived = screen.getContainer();
        } catch (final RuntimeException e) {
            Offscreen.LOG.error("[Offscreen] synced screen has no container", e);
            sendClose(arrival.networkId());
            dispose();
            return;
        }
        if (!keep || isClosed() || !window().isOpen() || mc.theWorld == null || mc.thePlayer == null) {
            releaseBinding(arrived, arrival.networkId());
            dispose();
            return;
        }
        serverClosed = false;
        container = arrived;
        syncManager = arrived.getSyncManager();
        networkId = arrival.networkId();
        try {
            if (host != null) host.setDisposeHook(() -> {});
            final SyncedMui2UI next = new SyncedMui2UI(screen);
            next.setDisposeHook(this::dispose);
            host = next;
            window().setUI(next);
        } catch (final Throwable t) {
            Offscreen.LOG.error("[Offscreen] failed to host synced screen", t);
            dispose();
        }
    }

    private void releaseBinding(final @Nullable ModularContainer owned, final int nid) {
        final EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (owned == null) return;
        final ModularSyncManager msm = owned.getSyncManager();
        if (player != null && player.openContainer == owned) {
            ModularNetwork.CLIENT.closeContainer(nid, true, player);
        } else if (!msm.isClosed() && !msm.isDisposed()) {
            sendClose(nid);
            msm.onClose();
            msm.dispose();
        }
    }

    private void sendClose(final int nid) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            NetworkHandler.sendToServer(new CloseGuiPacket(nid, true));
        }
    }
}
