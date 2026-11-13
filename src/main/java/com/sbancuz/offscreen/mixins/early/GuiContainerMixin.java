package com.sbancuz.offscreen.mixins.early;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/** Runs a container's close logic only once, and hides the cursor stack where it does not belong. */
@Mixin(GuiContainer.class)
public abstract class GuiContainerMixin {

    @WrapWithCondition(
        method = "onGuiClosed",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/inventory/Container;onContainerClosed(Lnet/minecraft/entity/player/EntityPlayer;)V"))
    private boolean offscreen$runCloseLogicOnce(final Container container, final EntityPlayer player) {
        return ServerContainerMultiplexer.INSTANCE.allowsClientCloseLogic(container);
    }

    @WrapOperation(
        method = "drawScreen",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/InventoryPlayer;getItemStack()Lnet/minecraft/item/ItemStack;"))
    private ItemStack offscreen$hideCursor(final InventoryPlayer inventory, final Operation<ItemStack> original) {
        if (!ServerContainerMultiplexer.INSTANCE.overlay.cursorVisible((GuiContainer) (Object) this)) return null;
        return original.call(inventory);
    }
}
