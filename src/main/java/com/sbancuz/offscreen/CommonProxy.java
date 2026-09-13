package com.sbancuz.offscreen;

import com.sbancuz.offscreen.integration.mui2.TestSyncedGui;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
    }

    public void init(FMLInitializationEvent event) {
        try {
            Class.forName("com.cleanroommc.modularui.factory.GuiManager");
            TestSyncedGui.init();
        } catch (ClassNotFoundException ignored) {
            // MUI2 absent: synced test GUI (and only it) is unavailable.
        }
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}
}
