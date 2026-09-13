package com.sbancuz.offscreen.config;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.sbancuz.offscreen.Offscreen;

@Config(modid = Offscreen.MODID, category = "debug")
@Config.LangKey("offscreen.config.category.debug")
public class OffscreenDebugConfig {

    @Config.LangKey("offscreen.config.debug.enable_test_uis")
    @Config.Comment("Enable Offscreen test UIs and debug keybinds. Requires a game restart to take effect.")
    @Config.DefaultBoolean(false)
    @Config.RequiresMcRestart
    public static boolean enableTestUIs;
}
