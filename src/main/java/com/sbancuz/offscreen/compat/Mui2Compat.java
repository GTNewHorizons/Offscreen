package com.sbancuz.offscreen.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.screen.ModularScreen;
import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.api.OffscreenAPI;
import com.sbancuz.offscreen.api.UIRegistry;
import com.sbancuz.offscreen.integration.mui2.Mui2UI;
import com.sbancuz.offscreen.integration.mui2.SyncedMui2UI;
import com.sbancuz.offscreen.integration.mui2.TestMui2ScreenNoNEI;
import com.sbancuz.offscreen.integration.mui2.TestMui2ScreenWithNEI;
import com.sbancuz.offscreen.integration.mui2.TestSyncedGui;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * MUI2 integration. Never touch anything here unless
 * {@link ModCompat#MODULAR_UI2} is loaded.
 */
public final class Mui2Compat {

    private static KeyBinding testNei;
    private static KeyBinding testNoNei;
    private static KeyBinding testSynced;

    private Mui2Compat() {}

    /** Common-safe: registers the synced test factory on both sides. */
    public static void initCommon() {
        TestSyncedGui.init();
    }

    /** Client: maps ModularScreen to its offscreen host. */
    @SideOnly(Side.CLIENT)
    public static void registerClient() {
        UIRegistry.register(ModularScreen.class, screen -> {
            final ModularScreen ms = (ModularScreen) screen;
            final HostUI base;
            if (SyncedMui2UI.isServerBound(ms)) {
                Offscreen.LOG
                    .warn("[Offscreen] server-bound screen hosted without a session; use the factory open overload");
                base = new SyncedMui2UI(ms);
            } else {
                base = new Mui2UI(ms);
            }
            if (ModCompat.NEI.isLoaded()) {
                return NeiCompat.wrapIfNeeded(base);
            }
            return base;
        });
    }

    /** Client: registers the MUI2 test keybinds. Debug-gated by the caller. */
    @SideOnly(Side.CLIENT)
    public static void registerTestKeys() {
        testNei = new KeyBinding("key.offscreen.mui2_nei", Keyboard.KEY_N, "key.offscreen");
        testNoNei = new KeyBinding("key.offscreen.mui2_nonei", Keyboard.KEY_B, "key.offscreen");
        testSynced = new KeyBinding("key.offscreen.mui2_synced", Keyboard.KEY_O, "key.offscreen");
        ClientRegistry.registerKeyBinding(testNei);
        ClientRegistry.registerKeyBinding(testNoNei);
        ClientRegistry.registerKeyBinding(testSynced);
    }

    /** Client: handles the MUI2 test keybinds. Debug-gated by the caller. */
    @SideOnly(Side.CLIENT)
    public static void onTestKeyInput() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        if (testNei != null && testNei.isPressed()) {
            OffscreenAPI.open(TestMui2ScreenWithNEI::new);
        }
        if (testNoNei != null && testNoNei.isPressed()) {
            OffscreenAPI.open(TestMui2ScreenNoNEI::new);
        }
        if (testSynced != null && testSynced.isPressed()) {
            TestSyncedGui.FACTORY.toggleOffscreen();
        }
    }
}
