package com.sbancuz.offscreen.mixins.early;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sbancuz.offscreen.core.Driver;

/** Routes the main window's input through the main window. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @WrapOperation(
        method = "runTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;handleInput()V"))
    private void offscreen$gateScreenInput(final GuiScreen screen, final Operation<Void> original) {
        Driver.INSTANCE.mainWindow.onHandleInput(screen, () -> original.call(screen));
    }
}
