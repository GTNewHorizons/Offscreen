package com.sbancuz.offscreen.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cleanroommc.modularui.network.ModularNetworkSide;
import com.sbancuz.offscreen.integration.mui2.MuiSyncedSession;

/** Flags server-driven container closes so sessions never heal them. */
@Mixin(value = ModularNetworkSide.class, remap = false)
public abstract class ModularNetworkOffscreenMixin {

    @Inject(method = "deactivate(IZ)V", at = @At("HEAD"), remap = false)
    private void offscreen$onDeactivate(final int networkId, final boolean dispose, final CallbackInfo ci) {
        MuiSyncedSession.markServerClosed(networkId);
    }
}
