package com.sbancuz.offscreen.container.reopen;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0BPacketEntityAction;

/** A use with the held item, replayed from a hotbar slot that holds the same item. */
abstract class HeldItemUse extends DimensionBoundInteraction {

    private final boolean sneaking;
    /** The recorded item; null for an empty hand. */
    private final @Nullable ItemStack held;
    private final int hotbar;
    /** Without a slot holding the item, the current slot stands in (block and entity uses without sneaking). */
    private final boolean anySlot;

    HeldItemUse(final EntityClientPlayerMP player, @Nullable final ItemStack held, final boolean anySlot) {
        super(player);
        this.sneaking = player.isSneaking();
        this.held = ItemStack.copyItemStack(held);
        this.hotbar = player.inventory.currentItem;
        this.anySlot = anySlot && !sneaking;
    }

    /** The use packet, with the stack in the replay slot. */
    abstract Packet usePacket(@Nullable ItemStack stack);

    abstract boolean targetReachable();

    @Override
    final boolean reachableInDimension() {
        return replaySlot() >= 0 && targetReachable();
    }

    /**
     * Whether {@code s} is the recorded item. Count and NBT are ignored: tools and powered items
     * (an AE2 wireless terminal draining its battery) change their tag while their GUI is open.
     */
    private boolean isRecordedItem(@Nullable final ItemStack s) {
        if (held == null || s == null) return held == s;
        if (s.getItem() != held.getItem()) return false;
        return !s.getHasSubtypes() || s.getItemDamage() == held.getItemDamage();
    }

    /** The hotbar slot to replay with, or -1 when there is none. */
    private int replaySlot() {
        final InventoryPlayer playerInventory = mc.thePlayer.inventory;
        if (isRecordedItem(playerInventory.mainInventory[playerInventory.currentItem]))
            return playerInventory.currentItem;
        if (isRecordedItem(playerInventory.mainInventory[hotbar])) return hotbar;
        for (int i = 0; i < 9; i++) if (isRecordedItem(playerInventory.mainInventory[i])) return i;
        if (anySlot) return playerInventory.currentItem;
        return -1;
    }

    private Packet sneakPacket(final EntityClientPlayerMP player, final boolean down) {
        if (down) return new C0BPacketEntityAction(player, 1);
        return new C0BPacketEntityAction(player, 2);
    }

    @Override
    final void replayPackets(final Consumer<Packet> send) {
        final EntityClientPlayerMP player = mc.thePlayer;
        final int current = player.inventory.currentItem;
        final int slot = replaySlot();
        final boolean sneak = player.isSneaking();
        if (sneaking != sneak) send.accept(sneakPacket(player, sneaking));

        // always sent: the server may not have seen the client's latest hotbar selection yet
        send.accept(new C09PacketHeldItemChange(slot));
        send.accept(usePacket(player.inventory.mainInventory[slot]));
        if (slot != current) send.accept(new C09PacketHeldItemChange(current));
        if (sneaking != sneak) send.accept(sneakPacket(player, sneak));
    }
}
