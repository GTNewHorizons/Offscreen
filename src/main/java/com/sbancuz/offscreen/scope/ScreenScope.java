package com.sbancuz.offscreen.scope;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.Offscreen;

public final class ScreenScope implements Scope {

    private final Supplier<GuiScreen> wrapperScreenSupplier;
    private GuiScreen savedScreen;
    private boolean savedFocus;

    public ScreenScope(final Supplier<GuiScreen> wrapperScreenSupplier) {
        this.wrapperScreenSupplier = wrapperScreenSupplier;
    }

    @Override
    public void enter() {
        final Minecraft mc = Minecraft.getMinecraft();
        savedFocus = mc.inGameHasFocus;
        savedScreen = mc.currentScreen;
        mc.currentScreen = wrapperScreenSupplier.get();
    }

    @Override
    public void restore() {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.currentScreen = savedScreen;
        savedScreen = null;
        if (savedFocus && mc.currentScreen == null && !mc.inGameHasFocus) {
            try {
                mc.setIngameFocus();
            } catch (final Throwable t) {
                Offscreen.LOG.warn("[Offscreen] setIngameFocus restoration failed", t);
            }
        }
    }
}
