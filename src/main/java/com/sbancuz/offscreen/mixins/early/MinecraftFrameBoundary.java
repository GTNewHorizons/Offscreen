package com.sbancuz.offscreen.mixins.early;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sbancuz.offscreen.window.FrameBoundary;

@Mixin(value = Minecraft.class, priority = 900)
public abstract class MinecraftFrameBoundary {

    // Angelica submits the frame at HEAD, so RETURN is the first point where offscreen draws are on the GPU queue.
    @Inject(method = "func_147120_f", at = @At("RETURN"))
    private void offscreen$afterFrameSubmitted(final CallbackInfo ci) {
        FrameBoundary.onFrameSubmitted();
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
    private void offscreen$drainPresents(final CallbackInfo ci) {
        FrameBoundary.onShutdown();
    }
}
