package com.sbancuz.offscreen.window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.scope.Scope;
import com.sbancuz.offscreen.window.input.FrameEvent;

public final class HostedScreen<T extends HostUI> {

    private static GuiScreen bgSuppressed;

    public static boolean isSuppressingBgFor(final GuiScreen screen) {
        return screen == bgSuppressed;
    }

    private final T screen;
    private final Minecraft mc;

    private int width = -1;
    private int height = -1;
    private boolean resizeRequested = false;

    public HostedScreen(T screen) {
        this.screen = screen;
        this.mc = Minecraft.getMinecraft();
    }

    public T screen() {
        return screen;
    }

    public Scope scope() {
        return screen.scope();
    }

    public GuiScreen getGuiScreen() {
        return screen.getGuiScreen();
    }

    public void resize(final int guiWidth, final int guiHeight) {
        this.width = guiWidth;
        this.height = guiHeight;
        this.resizeRequested = false;

        screen.getGuiScreen()
            .setWorldAndResolution(mc, guiWidth, guiHeight);
        screen.onResize(width, height);
    }

    public void dispose() {
        screen.dispose();
    }

    public boolean needsResize(final int width, final int height) {
        return resizeRequested || this.width != width || this.height != height;
    }

    public void draw(Minecraft mc, int mouseX, int mouseY, float partialTicks, long now) {
        bgSuppressed = screen.getGuiScreen();
        screen.draw(mc, mouseX, mouseY, partialTicks, now);
        bgSuppressed = null;
    }

    public void requestResize() {
        resizeRequested = true;
    }

    public void update() {
        screen.update();
    }

    public void dispatchInput(FrameEvent event, float partialTicks) {
        screen.onInput(event, partialTicks);
    }
}
