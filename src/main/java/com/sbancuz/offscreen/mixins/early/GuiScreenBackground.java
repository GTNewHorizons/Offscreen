package com.sbancuz.offscreen.mixins.early;

import net.minecraft.client.gui.GuiScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sbancuz.offscreen.window.HostedScreen;

@Mixin(GuiScreen.class)
public class GuiScreenBackground {

    @Inject(method = "drawDefaultBackground", at = @At("HEAD"), cancellable = true)
    private void offscreen$suppressForeignDim(final CallbackInfo ci) {
        if (HostedScreen.isSuppressingBgFor((GuiScreen) (Object) this)) {
            ci.cancel();
        }
    }
}
