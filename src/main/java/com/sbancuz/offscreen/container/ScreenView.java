package com.sbancuz.offscreen.container;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;

import com.sbancuz.offscreen.container.ContainerAdapter.PayloadEffect;

/**
 * A screen in a place: the main window ({@link com.sbancuz.offscreen.window.MainWindow}),
 * or an entry of a hosted window's stack ({@link com.sbancuz.offscreen.window.HostedScreen}).
 * Nothing about a view is stored: whether it shows a container, a free screen or a child is derived from the
 * screen's container and the place around it every time, so the model cannot go stale. A view
 * is also a place a GUI the server opens can be shown in.
 */
public interface ScreenView extends ArrivalTarget {

    /** The screen the view shows; null only for the main window while it shows nothing. */
    @Nullable
    GuiScreen screen();

    /**
     * The container that gates the view: its own, or for a screen without one that of the container screen under it.
     */
    @Nullable
    ServerContainer gatingContainer();

    /** The top screen of the view's place: what inbound packet handlers see as the current screen. */
    @Nullable
    GuiScreen topScreenOfPlace();

    /** Removes the view and the screens stacked on it from its place, without telling the server or the multiplexer. */
    void removeFromPlace();

    boolean samePlace(ScreenView other);

    /** The view's GUI called {@code displayGuiScreen(s)}; {@code vanilla} runs it. */
    void onDisplayRequested(@Nullable GuiScreen s, Consumer<GuiScreen> vanilla);

    /** The view's GUI closed its screen. {@code packet == false}: closeScreenNoPacket. */
    void onCloseRequested(boolean packet);

    /** The view closes and its container is told. {@code packet == false}: nothing is sent to the server. */
    default void close(final boolean packet) {
        final ServerContainer own = ServerContainerMultiplexer.INSTANCE.containerOf(screen());
        removeFromPlace();
        if (own != null) ServerContainerMultiplexer.INSTANCE.viewClosed(own, packet);
    }

    /** Whether the view shows the active container, or sits on one that does, and the multiplexer is idle. */
    default boolean isActiveView() {
        return ServerContainerMultiplexer.INSTANCE.isIdle()
            && gatingContainer() == ServerContainerMultiplexer.INSTANCE.activeContainer();
    }

    /** Whether the view receives input and updates: free screens always, container screens only when active. */
    default boolean receivesInput() {
        return gatingContainer() == null || isActiveView();
    }

    /** For mod payloads no adapter knows: a container view acts on the open container, a free one may open GUIs. */
    default PayloadEffect effectOfUnknownPayload() {
        if (gatingContainer() == null) return PayloadEffect.OPENS_GUI;
        return PayloadEffect.ACTS_ON_OPEN_CONTAINER;
    }

    /** {@code openContainer} while the view runs: its own container, else the gating one, else the inventory. */
    default Container containerForGlobals() {
        if (screen() instanceof GuiContainer gc) return gc.inventorySlots;
        if (gatingContainer() instanceof ServerContainer b) return b.clientContainer;
        return Minecraft.getMinecraft().thePlayer.inventoryContainer;
    }
}
