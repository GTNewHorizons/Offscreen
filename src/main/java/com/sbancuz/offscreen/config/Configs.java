package com.sbancuz.offscreen.config;

import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;

/**
 * Registers every Offscreen config with GTNHLib, mirroring how Galaxia registers
 * its configs from a single place.
 */
public final class Configs {

    private Configs() {}

    public static void registerAll() {
        try {
            ConfigurationManager.registerConfig(OffscreenConfig.class);
            ConfigurationManager.registerConfig(OffscreenDebugConfig.class);
        } catch (Exception ignored) {}
    }
}
