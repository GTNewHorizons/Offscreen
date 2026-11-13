package com.sbancuz.offscreen.container.reopen;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;

import com.sbancuz.offscreen.container.ArrivalCause;

/**
 * The world interaction that opened a container: recorded from the outgoing packet, replayed
 * as packets to reopen it.
 * {@link #NONE} and {@link #INVENTORY} stand for containers that need no world interaction.
 */
public abstract class OpeningInteraction implements ArrivalCause {

    final Minecraft mc = Minecraft.getMinecraft();

    /** Cannot be reopened: closing it on the server is final. */
    public static final OpeningInteraction NONE = new NoInteraction(false, "NONE");
    /** The player inventory: always reachable, nothing to send. */
    public static final OpeningInteraction INVENTORY = new NoInteraction(true, "INVENTORY");

    /** Records the world interaction {@code p} carries; NONE when it cannot be replayed. */
    public static OpeningInteraction fromPacket(final Packet p) {
        final Minecraft mc = Minecraft.getMinecraft();
        final EntityClientPlayerMP player = mc.thePlayer;
        if (p instanceof C08PacketPlayerBlockPlacement c && c.func_149568_f() != 255)
            return new BlockRightClick(player, c);
        if (p instanceof C08PacketPlayerBlockPlacement c && c.func_149574_g() != null) {
            return new ItemRightClick(player, c.func_149574_g());
        }
        if (p instanceof C08PacketPlayerBlockPlacement) return NONE;

        // an entity the client does not know cannot be replayed
        if (p instanceof C02PacketUseEntity c && c.func_149564_a(mc.theWorld) instanceof Entity target) {
            return new EntityRightClick(player, target);
        }
        if (p instanceof C02PacketUseEntity || !(player.ridingEntity instanceof Entity mount)) return NONE;
        return new MountInventoryKey(player, mount);
    }

    /** Whether a container opened this way can come back after it was closed. */
    public boolean canEverReopen() {
        return true;
    }

    /** Whether the client can see that a replay would still reach the same target. */
    public abstract boolean canReplayNow();

    /**
     * Whether the target is observably gone for good (block replaced, entity dead or replaced,
     * other dimension). What may come back (an unloaded chunk, the item moved out of the hotbar,
     * an entity out of tracking range) only makes {@link #canReplayNow} false.
     */
    public abstract boolean targetGoneForGood();

    /** The replay sequence of 10.2. */
    abstract void replayPackets(Consumer<Packet> send);

    /** Sends the replay sequence as one batch, if the static conditions still hold. */
    public final void replay(final Consumer<Packet> send) {
        if (canReplayNow()) replayPackets(send);
    }
}
