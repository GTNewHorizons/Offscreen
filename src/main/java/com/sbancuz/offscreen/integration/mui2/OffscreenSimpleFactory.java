package com.sbancuz.offscreen.integration.mui2;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.factory.SimpleGuiFactory;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.sbancuz.offscreen.api.OffscreenAPI;
import com.sbancuz.offscreen.window.Window;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * A {@link SimpleGuiFactory} opening in offscreen windows with working sync
 * handlers. Needs {@code allowC2S()} on both sides; one slot GUI at a time.
 * Windows close through the plain {@link OffscreenAPI#close}.
 */
public class OffscreenSimpleFactory extends SimpleGuiFactory {

    public OffscreenSimpleFactory(final String name, final String modId, final PanelBuilder builder) {
        super(name, holder(modId, builder));
    }

    /** Opens this GUI in a new offscreen window, or null. */
    public @Nullable Window openOffscreen() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return null;
        final Window window = new Window(getFactoryName());
        if (!window.isOpen()) return null;
        if (new MuiSyncedSession(window, this, new GuiData(mc.thePlayer)).isClosed()) return null;
        return window;
    }

    /** Closes the live window for this factory, or opens it. */
    public @Nullable Window toggleOffscreen() {
        final MuiSyncedSession existing = MuiSyncedSession.findActive(this);
        if (existing != null) {
            // While the open is still in flight the window is blank; key repeats from holding
            // the toggle key would otherwise open and instantly dispose it (flicker + close).
            if (existing.isOpening()) return existing.window();
            existing.dispose();
            return null;
        }
        return openOffscreen();
    }

    /** Builds the main panel on both sides; no client-only references. */
    @FunctionalInterface
    public interface PanelBuilder {

        ModularPanel build(GuiData data, PanelSyncManager syncManager, UISettings settings);
    }

    private static IGuiHolder<GuiData> holder(final String modId, final PanelBuilder builder) {
        return new IGuiHolder<>() {

            @Override
            @SideOnly(Side.CLIENT)
            public ModularScreen createScreen(final GuiData data, final ModularPanel mainPanel) {
                return new ModularScreen(modId, mainPanel);
            }

            @Override
            public ModularPanel buildUI(final GuiData data, final PanelSyncManager syncManager,
                final UISettings settings) {
                return builder.build(data, syncManager, settings);
            }
        };
    }
}
