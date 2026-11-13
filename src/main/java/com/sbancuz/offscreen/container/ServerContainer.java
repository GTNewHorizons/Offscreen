package com.sbancuz.offscreen.container;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;

import com.sbancuz.offscreen.config.ContainerProtocolConfig;
import com.sbancuz.offscreen.container.ContainerAdapter.SlotOnClose;
import com.sbancuz.offscreen.container.reopen.OpeningInteraction;

/**
 * Client record of one server-backed container: the player inventory (window id 0, never
 * closed) or a container the server opened. A reopened container is a new record that takes the
 * place and the opening interaction of the one it replaces.
 */
public final class ServerContainer {

    /** 7.2. ACTIVE means "holds the active container". */
    public enum State {
        /** open on the server: the active container */
        OPEN,
        /** closed on the server, its views kept as a snapshot until it is reopened */
        PAUSED,
        /** its opening interaction was replayed; waiting for the server to open it again */
        REOPENING,
        /** gone for good */
        CLOSED
    }

    /** Whether a container class has close logic that can drop items, per class. */
    private static final Map<Class<?>, Boolean> CLOSE_LOGIC = new WeakHashMap<>();

    public final Container clientContainer;
    public final int windowId;
    public final ContainerAdapter adapter;
    /** The world interaction that opened it; replayed to reopen it. */
    OpeningInteraction openedBy;
    State state = State.OPEN;
    private OverlayHint hint = OverlayHint.NONE;
    private long hintUntil;

    ServerContainer(final Container clientContainer, final int windowId, final ContainerAdapter adapter,
        final OpeningInteraction openedBy) {
        this.clientContainer = clientContainer;
        this.windowId = windowId;
        this.adapter = adapter;
        this.openedBy = openedBy;
    }

    /** The player inventory: always there, nothing to replay. */
    static ServerContainer playerInventory(final Container inventoryContainer) {
        return new ServerContainer(inventoryContainer, 0, ContainerAdapter.VANILLA, OpeningInteraction.INVENTORY);
    }

    /** The client container's slots; 1.7.10 keeps them in a raw list. */
    @SuppressWarnings("unchecked")
    List<Slot> slots() {
        return clientContainer.inventorySlots;
    }

    public boolean isPlayerInventory() {
        return windowId == 0;
    }

    public State state() {
        return state;
    }

    /**
     * Whether a closed container can come back: its opening interaction is replayed.
     * One without is still only paused when closed, but closed for good on the server.
     */
    public boolean canReopen() {
        return openedBy.canEverReopen();
    }

    /** Whether the container can be reopened right now. */
    boolean canReopenNow() {
        return openedBy.canReplayNow();
    }

    void showHint(final OverlayHint h) {
        if (!h.shown) return;
        hint = h;
        hintUntil = System.currentTimeMillis() + 2500;
    }

    /** The hint shown over the views now, for 2.5 seconds after it was given. */
    Optional<OverlayHint> shownHint() {
        if (System.currentTimeMillis() >= hintUntil) return Optional.empty();
        return Optional.of(hint);
    }

    /**
     * Closing the container on the server would drop items, because a dropping slot
     * holds a stack, or an unclassified one does and {@code pinUnknownSlots} is set. A container
     * class without close logic is never pinned.
     */
    boolean wouldDropItemsOnClose() {
        if (!CLOSE_LOGIC.computeIfAbsent(clientContainer.getClass(), c -> adapter.hasCloseLogic(clientContainer)))
            return false;

        final InventoryPlayer inventory = Minecraft.getMinecraft().thePlayer.inventory;
        for (final Slot slot : slots()) {
            if (!slot.getHasStack() || slot.inventory == inventory) continue;
            final SlotOnClose kind = adapter.slotOnClose(clientContainer, slot);
            if (kind == SlotOnClose.DROPS_ITEMS) return true;
            if (kind == SlotOnClose.UNKNOWN && ContainerProtocolConfig.pinUnknownSlots) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        if (isPlayerInventory()) return "Inventory[" + state + "]";
        final String name = clientContainer.getClass()
            .getSimpleName();
        return String.format("%s#%d[%s]", name, windowId, state);
    }
}
