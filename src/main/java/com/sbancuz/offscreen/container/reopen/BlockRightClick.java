package com.sbancuz.offscreen.container.reopen;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.tileentity.TileEntity;

/** A right click on a block. */
final class BlockRightClick extends HeldItemUse {

    private final int x, y, z, face;
    private final float hitX, hitY, hitZ;
    private final Block block;
    private final @Nullable Class<?> tile;

    BlockRightClick(final EntityClientPlayerMP player, final C08PacketPlayerBlockPlacement c) {
        super(player, c.func_149574_g(), true);
        x = c.func_149576_c();
        y = c.func_149571_d();
        z = c.func_149570_e();
        face = c.func_149568_f();

        // what the target looked like, to tell a replaced block
        hitX = c.func_149573_h();
        hitY = c.func_149569_i();
        hitZ = c.func_149575_j();
        block = mc.theWorld.getBlock(x, y, z);
        tile = tileClass();
    }

    /** The class of the tile entity at the position, null for none. */
    private @Nullable Class<?> tileClass() {
        if (mc.theWorld.getTileEntity(x, y, z) instanceof TileEntity te) return te.getClass();
        return null;
    }

    private boolean chunkLoaded() {
        return mc.theWorld.getChunkProvider()
            .chunkExists(x >> 4, z >> 4);
    }

    private boolean sameBlock() {
        return mc.theWorld.getBlock(x, y, z) == block && tileClass() == tile;
    }

    @Override
    boolean targetReachable() {
        return chunkLoaded() && sameBlock();
    }

    @Override
    boolean goneInDimension() {
        return chunkLoaded() && !sameBlock();
    }

    @Override
    Packet usePacket(@Nullable final ItemStack stack) {
        return new C08PacketPlayerBlockPlacement(x, y, z, face, stack, hitX, hitY, hitZ);
    }

    @Override
    public String toString() {
        return "USE_BLOCK(" + x + "," + y + "," + z + ")";
    }
}
