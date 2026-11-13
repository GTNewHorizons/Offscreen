package com.sbancuz.offscreen.mixins.late;

import net.minecraft.client.network.NetHandlerPlayClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.cleanroommc.modularui.network.IPacket;
import com.cleanroommc.modularui.network.packets.CloseAllGuiPacket;
import com.cleanroommc.modularui.network.packets.CloseGuiPacket;
import com.cleanroommc.modularui.network.packets.ReopenGuiPacket;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/**
 * Server-sent ModularUI2 close and reopen packets change the open container without an S2E or
 * S2D: the client checks what is open after the handler.
 */
@Mixin(value = { CloseGuiPacket.class, CloseAllGuiPacket.class, ReopenGuiPacket.class }, remap = false)
public abstract class Mui2ServerCloseMixin {

    @Inject(method = "executeClient", at = @At("RETURN"), remap = false)
    private void offscreen$reconcile(final NetHandlerPlayClient handler, final CallbackInfoReturnable<IPacket> cir) {
        ServerContainerMultiplexer.INSTANCE.requestReconcile();
    }
}
