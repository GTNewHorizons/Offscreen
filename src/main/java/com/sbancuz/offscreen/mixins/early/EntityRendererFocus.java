package com.sbancuz.offscreen.mixins.early;

import net.minecraft.client.renderer.EntityRenderer;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.sbancuz.offscreen.core.Driver;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererFocus {

    @ModifyExpressionValue(
        method = "updateCameraAndRender",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/settings/GameSettings;pauseOnLostFocus:Z",
            opcode = Opcodes.GETFIELD))
    private boolean offscreen$keepHostedWindowsRunning(boolean pauseOnLostFocus) {
        return pauseOnLostFocus && Driver.INSTANCE.getWindowCount() == 0;
    }
}
