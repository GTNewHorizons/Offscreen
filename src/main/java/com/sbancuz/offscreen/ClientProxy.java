package com.sbancuz.offscreen;

import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.api.UIRegistry;
import com.sbancuz.offscreen.compat.ModCompat;
import com.sbancuz.offscreen.compat.Mui2Compat;
import com.sbancuz.offscreen.compat.NeiCompat;
import com.sbancuz.offscreen.config.OffscreenDebugConfig;
import com.sbancuz.offscreen.core.Driver;
import com.sbancuz.offscreen.debug.DebugKeys;
import com.sbancuz.offscreen.integration.vanilla.VanillaUI;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(final FMLInitializationEvent event) {
        super.init(event);

        FMLCommonHandler.instance()
            .bus()
            .register(Driver.INSTANCE);

        UIRegistry.register(GuiScreen.class, screen -> {
            final HostUI base = new VanillaUI((GuiScreen) screen);
            // Wrap GuiContainers with NEI handling so vanilla UIs don't need bespoke NEI code.
            if (ModCompat.NEI.isLoaded()) {
                return NeiCompat.wrapIfNeeded(base);
            }
            return base;
        });

        if (ModCompat.MODULAR_UI2.isLoaded()) {
            Mui2Compat.registerClient();
        }

        if (OffscreenDebugConfig.enableTestUIs) {
            DebugKeys.init();
            FMLCommonHandler.instance()
                .bus()
                .register(this);
        }
    }

    @SubscribeEvent
    public void onKeyInput(final InputEvent.KeyInputEvent event) {
        DebugKeys.onKeyInput(event);
    }
}
