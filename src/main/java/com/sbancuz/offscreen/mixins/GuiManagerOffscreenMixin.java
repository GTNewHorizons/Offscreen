package com.sbancuz.offscreen.mixins;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.network.PacketBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cleanroommc.modularui.api.MCHelper;
import com.cleanroommc.modularui.api.UIFactory;
import com.cleanroommc.modularui.factory.GuiManager;
import com.sbancuz.offscreen.integration.mui2.MuiSyncedSession;

/**
 * Diverts factory-requested MUI2 screens into offscreen windows. The redirect
 * cannot see method arguments, so a head inject captures them first (thread-local).
 */
@Mixin(value = GuiManager.class, remap = false)
public abstract class GuiManagerOffscreenMixin {

    @Inject(
        method = "openFromClient(IILcom/cleanroommc/modularui/api/UIFactory;"
            + "Lnet/minecraft/network/PacketBuffer;Lnet/minecraft/client/entity/EntityPlayerSP;)V",
        at = @At("HEAD"),
        remap = false)
    private static void offscreen$captureArrival(final int windowId, final int networkId, final UIFactory factory,
        final PacketBuffer data, final EntityPlayerSP player, final CallbackInfo ci) {
        MuiSyncedSession.onArriving(factory, networkId);
    }

    @Redirect(
        method = "openFromClient(IILcom/cleanroommc/modularui/api/UIFactory;"
            + "Lnet/minecraft/network/PacketBuffer;Lnet/minecraft/client/entity/EntityPlayerSP;)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/cleanroommc/modularui/api/MCHelper;displayScreen(Lnet/minecraft/client/gui/GuiScreen;)Z"),
        remap = false)
    private static boolean offscreen$divertArrival(final GuiScreen screen) {
        if (MuiSyncedSession.tryDivert(screen)) return true;
        return MCHelper.displayScreen(screen);
    }
}
