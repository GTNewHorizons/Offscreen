package com.sbancuz.offscreen.config;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.sbancuz.offscreen.Offscreen;

@Config(modid = Offscreen.MODID, category = "general")
@Config.LangKey("offscreen.config.category.general")
public class OffscreenConfig {

    @Config.LangKey("offscreen.config.general.focused_fps")
    @Config.Comment("FPS limit when the offscreen window is focused")
    @Config.DefaultInt(60)
    @Config.RangeInt(min = 1, max = 240)
    public static int focusedFps;

    @Config.LangKey("offscreen.config.general.unfocused_fps")
    @Config.Comment("FPS limit when the offscreen window is not focused")
    @Config.DefaultInt(10)
    @Config.RangeInt(min = 1, max = 60)
    public static int unfocusedFps;
}
