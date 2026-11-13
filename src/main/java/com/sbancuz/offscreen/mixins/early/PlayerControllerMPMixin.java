package com.sbancuz.offscreen.mixins.early;

import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/** A click for a container that is not the server's open one never reaches the client-side prediction. */
@Mixin(PlayerControllerMP.class)
public abstract class PlayerControllerMPMixin {

    @Inject(method = "windowClick", at = @At("HEAD"), cancellable = true)
    private void offscreen$allowClick(final int windowId, final int slot, final int button, final int mode,
        final EntityPlayer player, final CallbackInfoReturnable<ItemStack> cir) {
        if (!ServerContainerMultiplexer.INSTANCE.allowsClick(windowId)) cir.setReturnValue(null);
    }

    @Inject(method = "sendEnchantPacket", at = @At("HEAD"), cancellable = true)
    private void offscreen$allowEnchant(final int windowId, final int button, final CallbackInfo ci) {
        if (!ServerContainerMultiplexer.INSTANCE.allowsClick(windowId)) ci.cancel();
    }
}
