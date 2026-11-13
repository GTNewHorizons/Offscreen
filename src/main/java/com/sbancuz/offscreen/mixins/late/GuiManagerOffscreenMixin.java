package com.sbancuz.offscreen.mixins.late;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.PacketBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cleanroommc.modularui.api.UIFactory;
import com.cleanroommc.modularui.factory.GuiManager;
import com.sbancuz.offscreen.integration.mui2.Mui2Adapter;

/** Records the network id of every ModularUI2 GUI the server opens. Placing it is the multiplexer's job. */
@Mixin(value = GuiManager.class, remap = false)
public abstract class GuiManagerOffscreenMixin {

    private static final String OPEN_FROM_CLIENT = "openFromClient(IILcom/cleanroommc/modularui/api/UIFactory;"
        + "Lnet/minecraft/network/PacketBuffer;Lnet/minecraft/client/entity/EntityPlayerSP;)V";

    @Inject(method = OPEN_FROM_CLIENT, at = @At("HEAD"), remap = false)
    private static void offscreen$arrivalHead(final int windowId, final int networkId, final UIFactory factory,
        final PacketBuffer data, final EntityPlayerSP player, final CallbackInfo ci) {
        Mui2Adapter.onOpenFromClient(networkId);
    }

    @Inject(method = OPEN_FROM_CLIENT, at = @At("RETURN"), remap = false)
    private static void offscreen$arrivalReturn(final int windowId, final int networkId, final UIFactory factory,
        final PacketBuffer data, final EntityPlayerSP player, final CallbackInfo ci) {
        Mui2Adapter.onOpenFromClient(-1);
    }
}
