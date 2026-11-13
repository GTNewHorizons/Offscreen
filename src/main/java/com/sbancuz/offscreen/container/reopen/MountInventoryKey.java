package com.sbancuz.offscreen.container.reopen;

import java.util.function.Consumer;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0BPacketEntityAction;

/** The inventory key while riding (C0B action 7). */
final class MountInventoryKey extends DimensionBoundInteraction {

    private final TrackedEntity mount;

    MountInventoryKey(final EntityClientPlayerMP player, final Entity mount) {
        super(player);
        this.mount = new TrackedEntity(mount);
    }

    @Override
    boolean reachableInDimension() {
        return mount.alive() && mount.find() == mc.thePlayer.ridingEntity;
    }

    @Override
    boolean goneInDimension() {
        return mount.gone();
    }

    @Override
    void replayPackets(final Consumer<Packet> send) {
        send.accept(new C0BPacketEntityAction(mc.thePlayer, 7));
    }

    @Override
    public String toString() {
        return "HORSE_KEY";
    }
}
