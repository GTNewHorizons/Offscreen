package com.sbancuz.offscreen.container.reopen;

import javax.annotation.Nullable;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;

/** A right click on an entity. */
final class EntityRightClick extends HeldItemUse {

    private final TrackedEntity target;

    EntityRightClick(final EntityClientPlayerMP player, final Entity target) {
        super(player, player.getHeldItem(), true);
        this.target = new TrackedEntity(target);
    }

    @Override
    boolean targetReachable() {
        return target.alive();
    }

    @Override
    boolean goneInDimension() {
        return target.gone();
    }

    @Override
    Packet usePacket(@Nullable final ItemStack stack) {
        return new C02PacketUseEntity(target.find(), C02PacketUseEntity.Action.INTERACT);
    }

    @Override
    public String toString() {
        return "USE_ENTITY";
    }
}
