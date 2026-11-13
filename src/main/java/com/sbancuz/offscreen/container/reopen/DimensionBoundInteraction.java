package com.sbancuz.offscreen.container.reopen;

import net.minecraft.client.entity.EntityClientPlayerMP;

/** An interaction in one dimension: in any other its target is gone. */
abstract class DimensionBoundInteraction extends OpeningInteraction {

    private final int dimension;

    DimensionBoundInteraction(final EntityClientPlayerMP player) {
        this.dimension = player.dimension;
    }

    abstract boolean reachableInDimension();

    abstract boolean goneInDimension();

    @Override
    public final boolean canReplayNow() {
        return mc.thePlayer.dimension == dimension && reachableInDimension();
    }

    @Override
    public final boolean targetGoneForGood() {
        return mc.thePlayer.dimension != dimension || goneInDimension();
    }
}
