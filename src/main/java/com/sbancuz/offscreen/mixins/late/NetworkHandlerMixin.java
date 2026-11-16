package com.sbancuz.offscreen.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cleanroommc.modularui.network.IPacket;
import com.cleanroommc.modularui.network.NetworkHandler;
import com.sbancuz.offscreen.integration.mui2.Mui2Adapter;

/** Exposes the ModularUI2 packet object to the outbound payload classification. */
@Mixin(value = NetworkHandler.class, remap = false)
public abstract class NetworkHandlerMixin {

    @Inject(method = "sendToServer", at = @At("HEAD"), remap = false)
    private static void offscreen$head(final IPacket packet, final CallbackInfo ci) {
        Mui2Adapter.sending(packet);
    }

    @Inject(method = "sendToServer", at = @At("RETURN"), remap = false)
    private static void offscreen$tail(final IPacket packet, final CallbackInfo ci) {
        Mui2Adapter.sending(null);
    }
}
