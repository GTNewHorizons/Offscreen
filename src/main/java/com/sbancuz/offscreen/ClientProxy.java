package com.sbancuz.offscreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.screen.ModularScreen;
import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.api.OffscreenAPI;
import com.sbancuz.offscreen.api.UIRegistry;
import com.sbancuz.offscreen.core.Driver;
import com.sbancuz.offscreen.integration.mui2.Mui2UI;
import com.sbancuz.offscreen.integration.mui2.SyncedMui2UI;
import com.sbancuz.offscreen.integration.mui2.TestMui2ScreenNoNEI;
import com.sbancuz.offscreen.integration.mui2.TestMui2ScreenWithNEI;
import com.sbancuz.offscreen.integration.mui2.TestSyncedGui;
import com.sbancuz.offscreen.integration.nei.NeiWrapperUI;
import com.sbancuz.offscreen.integration.vanilla.VanillaUI;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

public class ClientProxy extends CommonProxy {

    private static final KeyBinding debugToggleScreen = new KeyBinding(
        "key.offscreen.open",
        Keyboard.KEY_M,
        "key.offscreen");

    private static final KeyBinding debugMui2NEI = new KeyBinding(
        "key.offscreen.mui2_nei",
        Keyboard.KEY_N,
        "key.offscreen");

    private static final KeyBinding debugMui2NoNEI = new KeyBinding(
        "key.offscreen.mui2_nonei",
        Keyboard.KEY_B,
        "key.offscreen");

    private static final KeyBinding debugMui2Synced = new KeyBinding(
        "key.offscreen.mui2_synced",
        Keyboard.KEY_O,
        "key.offscreen");

    private static final KeyBinding debugMui2NEIMain = new KeyBinding(
        "key.offscreen.mui2_nei_main",
        Keyboard.KEY_J,
        "key.offscreen");

    /** Set when MUI2 is present; toggles the synced test GUI offscreen. */
    private static Runnable syncedTestToggle;

    @Override
    public void init(final FMLInitializationEvent event) {
        super.init(event);

        ClientRegistry.registerKeyBinding(debugToggleScreen);
        ClientRegistry.registerKeyBinding(debugMui2NEI);
        ClientRegistry.registerKeyBinding(debugMui2NoNEI);
        // ClientRegistry.registerKeyBinding(debugMui2NEIMain);

        FMLCommonHandler.instance()
            .bus()
            .register(this);

        FMLCommonHandler.instance()
            .bus()
            .register(Driver.INSTANCE);

        UIRegistry.register(GuiScreen.class, screen -> {
            final GuiScreen gs = (GuiScreen) screen;
            final HostUI base = new VanillaUI(gs);
            // Wrap GuiContainers with NEI handling so vanilla UIs don't need bespoke NEI code.
            if (gs instanceof GuiContainer && Loader.isModLoaded("NotEnoughItems")) {
                return new NeiWrapperUI(base);
            }
            return base;
        });
        try {
            final Class<?> modularScreen = Class.forName("com.cleanroommc.modularui.screen.ModularScreen");
            UIRegistry.register(modularScreen, screen -> {
                final ModularScreen ms = (ModularScreen) screen;
                final HostUI base;
                if (SyncedMui2UI.isServerBound(ms)) {
                    Offscreen.LOG.warn(
                        "[Offscreen] server-bound screen hosted without a session; use the factory open overload");
                    base = new SyncedMui2UI(ms);
                } else {
                    base = new Mui2UI(ms);
                }
                if (base.getGuiScreen() instanceof GuiContainer && Loader.isModLoaded("NotEnoughItems")) {
                    return new NeiWrapperUI(base);
                }
                return base;
            });
            ClientRegistry.registerKeyBinding(debugMui2Synced);
            syncedTestToggle = TestSyncedGui.FACTORY::toggleOffscreen;
        } catch (final ClassNotFoundException ignored) {}
    }

    @SubscribeEvent
    public void onKeyInput(final InputEvent.KeyInputEvent event) {
        if (debugToggleScreen.isPressed()) {
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null) return;
            OffscreenAPI.open(() -> new GuiInventory(Minecraft.getMinecraft().thePlayer));
        }

        if (debugMui2NEI.isPressed()) {
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null) return;
            OffscreenAPI.open(TestMui2ScreenWithNEI::new);
        }

        if (debugMui2NoNEI.isPressed()) {
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null) return;
            OffscreenAPI.open(TestMui2ScreenNoNEI::new);
        }

        if (syncedTestToggle != null && debugMui2Synced.isPressed()) {
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null) return;
            syncedTestToggle.run();
        }

        // if (debugMui2NEIMain.isPressed()) {
        // Minecraft mc = Minecraft.getMinecraft();
        // if (mc.theWorld == null || mc.thePlayer == null) return;
        // ModularContainer container = new ModularContainer();
        // container.constructClientOnly();
        // ModularScreen screen = new TestMui2ScreenWithNEI();
        // mc.displayGuiScreen(new GuiContainerWrapper(container, screen).getGuiScreen());
        // }
    }
}
