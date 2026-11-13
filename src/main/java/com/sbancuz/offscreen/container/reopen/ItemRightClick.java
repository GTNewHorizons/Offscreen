package com.sbancuz.offscreen.container.reopen;

import javax.annotation.Nullable;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;

/** A right click with an item in the air. */
final class ItemRightClick extends HeldItemUse {

    ItemRightClick(final EntityClientPlayerMP player, final ItemStack held) {
        super(player, held, false);
    }

    @Override
    boolean targetReachable() {
        return true;
    }

    @Override
    boolean goneInDimension() {
        return false;
    }

    @Override
    Packet usePacket(@Nullable final ItemStack stack) {
        return new C08PacketPlayerBlockPlacement(-1, -1, -1, 255, stack, 0f, 0f, 0f);
    }

    @Override
    public String toString() {
        return "USE_ITEM";
    }
}
