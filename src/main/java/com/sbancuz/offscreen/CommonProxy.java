package com.sbancuz.offscreen;

import com.sbancuz.offscreen.compat.ModCompat;
import com.sbancuz.offscreen.compat.Mui2Compat;
import com.sbancuz.offscreen.config.Configs;
import com.sbancuz.offscreen.config.OffscreenDebugConfig;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Configs.registerAll();
    }

    public void init(FMLInitializationEvent event) {
        if (OffscreenDebugConfig.enableTestUIs && ModCompat.MODULAR_UI2.isLoaded()) {
            Mui2Compat.initCommon();
        }
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}
}
