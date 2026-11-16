package com.sbancuz.offscreen.config;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.sbancuz.offscreen.Offscreen;

/** Options of the container multiplexer. */
@Config(modid = Offscreen.MODID, category = "containers")
@Config.LangKey("offscreen.config.category.containers")
public class ContainerProtocolConfig {

    @Config.Comment("Keep a container active while slots the mod cannot classify hold items")
    @Config.DefaultBoolean(true)
    public static boolean pinUnknownSlots = true;

    @Config.Comment("Container class name patterns (regex) whose non-player slots survive a close")
    @Config.DefaultStringList({})
    public static String[] persistentContainers = new String[0];

    @Config.Comment("Use tab-complete packets as ordering barrier. Off: a watchdog recovers from lost opens instead")
    @Config.DefaultBoolean(true)
    public static boolean barrier = true;

    @Config.Comment("Resync contents with a deliberately rejected click after every swap")
    @Config.DefaultBoolean(true)
    public static boolean refill = true;

    @Config.Comment("Client ticks without progress before a reset, used only when no barrier is available")
    @Config.DefaultInt(200)
    @Config.RangeInt(min = 20, max = 6000)
    public static int watchdogTicks = 200;

    @Config.Comment("Log state changes, and a stack trace for every reset")
    @Config.DefaultBoolean(false)
    public static boolean debugLog = false;
}
