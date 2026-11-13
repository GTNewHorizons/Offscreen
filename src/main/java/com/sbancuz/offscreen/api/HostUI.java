package com.sbancuz.offscreen.api;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.scope.Scope;

public interface HostUI {

    default Scope scope() {
        return Scope.NOP;
    }

    default void update() {}

    void dispose();

    default void onResize(final int width, final int height) {}

    GuiScreen getGuiScreen();

    void draw(Minecraft mc, int mouseX, int mouseY, float partialTicks, long now);

    /**
     * Handles the window's pending input. It runs inside the window's scopes, where lwjgl's {@code Mouse} and
     * {@code Keyboard} hold the hosted window's events, so the screen's own {@code handleInput()} handles them exactly
     * as in the main window, along with the hooks mods patch into it.
     */
    default void handleInput() {
        if (getGuiScreen().width < 0) return;
        getGuiScreen().handleInput();
    }
}
