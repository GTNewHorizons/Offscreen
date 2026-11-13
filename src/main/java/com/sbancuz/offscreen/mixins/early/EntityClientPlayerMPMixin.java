package com.sbancuz.offscreen.mixins.early;

import net.minecraft.client.entity.EntityClientPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/** Routes {@code closeScreen} and the horse inventory request through the multiplexer. */
@Mixin(EntityClientPlayerMP.class)
public abstract class EntityClientPlayerMPMixin {

    @Inject(method = "closeScreen", at = @At("HEAD"), cancellable = true)
    private void offscreen$routeCloseScreen(final CallbackInfo ci) {
        if (ServerContainerMultiplexer.INSTANCE.onCloseScreen(true)) ci.cancel();
    }

    @Inject(method = "closeScreenNoPacket", at = @At("HEAD"), cancellable = true)
    private void offscreen$routeCloseScreenNoPacket(final CallbackInfo ci) {
        if (ServerContainerMultiplexer.INSTANCE.onCloseScreen(false)) ci.cancel();
    }

    @Inject(method = "func_110322_i", at = @At("HEAD"), cancellable = true)
    private void offscreen$routeHorseInventory(final CallbackInfo ci) {
        if (ServerContainerMultiplexer.INSTANCE.onWorldUse(true)) ci.cancel();
    }
}
