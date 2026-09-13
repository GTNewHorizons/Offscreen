package com.sbancuz.offscreen.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.sbancuz.offscreen.api.OffscreenAPI;
import com.sbancuz.offscreen.compat.ModCompat;
import com.sbancuz.offscreen.compat.Mui2Compat;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Test UIs and their keybinds. Only initialized when the debug config enables
 * them (which requires a game restart), so release builds never see these.
 */
@SideOnly(Side.CLIENT)
public final class DebugKeys {

    private static KeyBinding openVanilla;

    private DebugKeys() {}

    public static void init() {
        openVanilla = new KeyBinding("key.offscreen.open", Keyboard.KEY_M, "key.offscreen");
        ClientRegistry.registerKeyBinding(openVanilla);
        if (ModCompat.MODULAR_UI2.isLoaded()) {
            Mui2Compat.registerTestKeys();
        }
    }

    public static void onKeyInput(final InputEvent.KeyInputEvent event) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        if (openVanilla != null && openVanilla.isPressed()) {
            OffscreenAPI.open(() -> new GuiInventory(Minecraft.getMinecraft().thePlayer));
        }
        if (ModCompat.MODULAR_UI2.isLoaded()) {
            Mui2Compat.onTestKeyInput();
        }
    }
}
