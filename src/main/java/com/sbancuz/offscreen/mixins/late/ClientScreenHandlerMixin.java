package com.sbancuz.offscreen.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.cleanroommc.modularui.network.ModularNetwork;
import com.cleanroommc.modularui.screen.ClientScreenHandler;
import com.sbancuz.offscreen.integration.mui2.Mui2Adapter;

/** A main-window close must not close every ModularUI2 session. */
@Mixin(value = ClientScreenHandler.class, remap = false)
public abstract class ClientScreenHandlerMixin {

    @Redirect(
        method = "onGuiChanged",
        at = @At(value = "INVOKE", target = "Lcom/cleanroommc/modularui/network/ModularNetwork$Client;closeAll()V"),
        remap = false)
    private static void offscreen$containCloseAll(final ModularNetwork.Client client) {
        Mui2Adapter.containCloseAll();
    }
}
