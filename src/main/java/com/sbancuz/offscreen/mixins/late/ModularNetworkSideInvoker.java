package com.sbancuz.offscreen.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.cleanroommc.modularui.network.ModularNetworkSide;

@Mixin(value = ModularNetworkSide.class, remap = false)
public interface ModularNetworkSideInvoker {

    @Invoker(value = "deactivate", remap = false)
    void offscreen$deactivate(int networkId, boolean dispose);
}
