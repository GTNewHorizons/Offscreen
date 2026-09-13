package com.sbancuz.offscreen.mixins.late;

import java.awt.Dimension;
import java.awt.Point;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.sbancuz.offscreen.window.input.MouseShadow;

import codechicken.lib.gui.GuiDraw;

@Mixin(value = GuiDraw.class, remap = false)
public class GuiDrawMixin {

    @Inject(method = "getMousePosition()Ljava/awt/Point;", at = @At("HEAD"), cancellable = true)
    private static void offscreen$getMousePosition(final CallbackInfoReturnable<Point> cir) {
        if (MouseShadow.isActive()) {
            cir.setReturnValue(new Point(MouseShadow.getX(), MouseShadow.getY()));
        }
    }

    @Inject(method = "displaySize", at = @At("HEAD"), cancellable = true)
    private static void offscreen$displaySize(final CallbackInfoReturnable<Dimension> cir) {
        if (MouseShadow.isActive()) {
            Minecraft mc = Minecraft.getMinecraft();
            ScaledResolution res = new ScaledResolution(mc, MouseShadow.getPixelWidth(), MouseShadow.getPixelHeight());
            cir.setReturnValue(new Dimension(res.getScaledWidth(), res.getScaledHeight()));
        }
    }

    @Inject(method = "displayRes", at = @At("HEAD"), cancellable = true)
    private static void offscreen$displayRes(final CallbackInfoReturnable<Dimension> cir) {
        if (MouseShadow.isActive()) {
            cir.setReturnValue(new Dimension(MouseShadow.getPixelWidth(), MouseShadow.getPixelHeight()));
        }
    }
}
