package com.sbancuz.offscreen.mixins.early;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/** Routes screen changes, the main window's input and updates, and right clicks through the multiplexer. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @WrapMethod(method = "displayGuiScreen")
    private void offscreen$routeDisplayGuiScreen(final GuiScreen screen, final Operation<Void> original) {
        ServerContainerMultiplexer.INSTANCE.onDisplayGuiScreen(screen, s -> original.call(s));
    }

    @WrapOperation(
        method = "runTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;handleInput()V"))
    private void offscreen$gateScreenInput(final GuiScreen screen, final Operation<Void> original) {
        ServerContainerMultiplexer.INSTANCE.mainWindow.onHandleInput(screen, () -> original.call(screen));
    }

    @WrapOperation(
        method = "runTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;updateScreen()V"))
    private void offscreen$gateScreenUpdate(final GuiScreen screen, final Operation<Void> original) {
        ServerContainerMultiplexer.INSTANCE.mainWindow.onUpdateScreen(screen, () -> original.call(screen));
    }

    @Inject(method = "func_147121_ag", at = @At("HEAD"), cancellable = true)
    private void offscreen$routeRightClick(final CallbackInfo ci) {
        if (ServerContainerMultiplexer.INSTANCE.onWorldUse(false)) ci.cancel();
    }
}
