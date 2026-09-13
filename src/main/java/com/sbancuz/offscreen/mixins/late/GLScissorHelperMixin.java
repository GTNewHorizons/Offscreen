package com.sbancuz.offscreen.mixins.late;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sbancuz.offscreen.window.input.MouseShadow;

@Pseudo
@Mixin(targets = "com.mitchej123.hodgepodge.mixins.hooks.GLScissorHelper", remap = false)
public class GLScissorHelperMixin {

    @Inject(method = "glScissorByGuiCoords", at = @At("HEAD"), cancellable = true)
    private static void offscreen$adjustScissor(Minecraft mc, int left, int top, int width, int height,
        CallbackInfo ci) {
        if (!MouseShadow.isActive()) return;

        // Offscreen framebuffer has its own pixel dimensions and scaled resolution.
        // Hodgepodge's helper uses mc.displayWidth/Height which is wrong for the
        // secondary SDL window. Recompute using the override set in HostedStack.runScoped.
        ScaledResolution res = new ScaledResolution(mc, MouseShadow.getPixelWidth(), MouseShadow.getPixelHeight());
        double scaleW = (double) MouseShadow.getPixelWidth() / res.getScaledWidth_double();
        double scaleH = (double) MouseShadow.getPixelHeight() / res.getScaledHeight_double();

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
            (int) (left * scaleW),
            (int) (MouseShadow.getPixelHeight() - (top + height) * scaleH),
            (int) (width * scaleW),
            (int) (height * scaleH));
        ci.cancel();
    }
}
