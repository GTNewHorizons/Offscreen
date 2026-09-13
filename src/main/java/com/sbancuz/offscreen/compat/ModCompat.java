package com.sbancuz.offscreen.compat;

import cpw.mods.fml.common.Loader;

/**
 * Single place for optional-mod presence checks. Results are cached after the
 * first lookup so mod initialization never pays for repeated
 * {@link Loader#isModLoaded} calls and never hardcodes class names.
 */
public enum ModCompat {

    MODULAR_UI2("modularui2"),
    NEI("NotEnoughItems"),
    WAILA("Waila"),
    HODGEPODGE("hodgepodge");

    private final String modId;
    private volatile Boolean loaded;

    ModCompat(final String modId) {
        this.modId = modId;
    }

    public String modId() {
        return modId;
    }

    public boolean isLoaded() {
        Boolean cached = loaded;
        if (cached == null) {
            cached = Loader.isModLoaded(modId);
            loaded = cached;
        }
        return cached;
    }
}
