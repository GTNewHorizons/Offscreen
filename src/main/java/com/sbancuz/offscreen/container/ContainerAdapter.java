package com.sbancuz.offscreen.container;

import java.lang.reflect.Method;
import java.util.regex.PatternSyntaxException;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ContainerEnchantment;
import net.minecraft.inventory.ContainerHorseInventory;
import net.minecraft.inventory.ContainerRepair;
import net.minecraft.inventory.InventoryCraftResult;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.InventoryMerchant;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.inventory.SlotMerchantResult;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.tileentity.TileEntity;

import com.sbancuz.offscreen.config.ContainerProtocolConfig;

/**
 * Knowledge about one UI family where the generic rules are not enough. Every method but
 * {@link #handles} has a generic default, which is all vanilla needs. Third parties
 * register adapters the same way the built-in integrations do.
 */
public interface ContainerAdapter {

    /** What an outbound mod payload does on the server. */
    enum PayloadEffect {
        /** acts on the server's open container: only the active view may send it */
        ACTS_ON_OPEN_CONTAINER,
        /** makes the server open or switch a GUI: what opens goes to the view that sent it */
        OPENS_GUI,
        /** makes the server close the open container: the client checks what is open afterwards */
        CLOSES_GUI,
        /** addressed by session, coordinates or entity, or without effect on the server slot */
        NEUTRAL,
        /** must not leave the client in the current state */
        DROP,
        /** the adapter does not know the packet: the code that sends it decides */
        UNKNOWN
    }

    /** What closing the container on the server does to the stack in a slot. */
    enum SlotOnClose {
        /** the server drops it at the player's feet */
        DROPS_ITEMS,
        /** it survives: player, derived and persistent slots */
        KEEPS_ITEMS,
        /** no rule applies; {@code pinUnknownSlots} decides */
        UNKNOWN
    }

    ContainerAdapter VANILLA = screen -> true;

    /** Whether this adapter handles the arriving or hosted screen. */
    boolean handles(GuiScreen screen);

    /** The generic rules, in order: derived from the player inventory, dropping, persistent, configured. */
    default SlotOnClose slotOnClose(final Container clientContainer, final Slot slot) {
        if (slot instanceof SlotCrafting || slot instanceof SlotMerchantResult) return SlotOnClose.KEEPS_ITEMS;
        if (slot.inventory instanceof InventoryCraftResult) return SlotOnClose.KEEPS_ITEMS;
        if (slot.inventory instanceof InventoryCrafting || slot.inventory instanceof InventoryMerchant) {
            return SlotOnClose.DROPS_ITEMS;
        }
        if (clientContainer instanceof ContainerRepair || clientContainer instanceof ContainerEnchantment)
            return SlotOnClose.DROPS_ITEMS;

        // persistent: the stack lives in a tile entity or an entity
        if (slot.inventory instanceof TileEntity || slot.inventory instanceof Entity) return SlotOnClose.KEEPS_ITEMS;
        if (clientContainer instanceof ContainerChest || clientContainer instanceof ContainerHorseInventory)
            return SlotOnClose.KEEPS_ITEMS;
        return configuredSlotOnClose(clientContainer);
    }

    /** {@code persistentContainers}: container classes whose non-player slots survive a close. */
    private SlotOnClose configuredSlotOnClose(final Container clientContainer) {
        final String name = clientContainer.getClass()
            .getName();

        try {
            for (final String p : ContainerProtocolConfig.persistentContainers) {
                if (name.matches(p)) return SlotOnClose.KEEPS_ITEMS;
            }
        } catch (final PatternSyntaxException e) {
            ServerContainerMultiplexer.LOG.warn("[Containers] bad persistentContainers pattern", e);
        }
        return SlotOnClose.UNKNOWN;
    }

    /**
     * Whether a class from {@code cls} up to, but not including, {@code stopAt} declares a method
     * with one of {@code names}: the class-level close-hook rule of 10.1.
     */
    default boolean declaresMethod(final Class<?> cls, final Class<?> stopAt, final String... names) {
        for (Class<?> c = cls; c != null && c != stopAt; c = c.getSuperclass()) {
            try {
                for (final Method m : c.getDeclaredMethods()) {
                    for (final String n : names) if (m.getName()
                        .equals(n)) return true;
                }
            } catch (final LinkageError e) {
                // a signature names a class that does not exist on the client: nothing to see here
            }
        }
        return false;
    }

    /**
     * Whether the container class has close logic that can drop
     * items. Cached per class.
     */
    default boolean hasCloseLogic(final Container clientContainer) {
        return declaresMethod(clientContainer.getClass(), Container.class, "onContainerClosed", "func_75134_a");
    }

    /** Classification of an outbound mod payload. */
    default PayloadEffect classifyPayload(final Packet packet) {
        return PayloadEffect.UNKNOWN;
    }

    /**
     * Closes the container on the server. {@code finalClose}: the server side
     * will not be reopened (the container closes, or it cannot be reopened).
     */
    default void closeOnServer(final ServerContainer container, final ServerContainerMultiplexer multiplexer,
        final boolean finalClose) {
        multiplexer.sendAsMultiplexer(new C0DPacketCloseWindow(container.windowId));
    }

    /** Cleanup when a container closes for good. */
    default void onContainerClosed(final ServerContainer container) {}

    /** The screen moves to a hosted window: take it out of the main window without displayGuiScreen side effects. */
    default void detachFromMainWindow(final GuiScreen screen) {}

    /** Runs once the player inventory is the active container again after a respawn. */
    default void afterRespawn(final ServerContainerMultiplexer multiplexer) {}
}
