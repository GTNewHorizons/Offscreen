package com.sbancuz.offscreen.integration.mui2;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.screen.ClientScreenHandler;
import com.cleanroommc.modularui.screen.GuiContainerWrapper;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.scope.MuiScope;
import com.sbancuz.offscreen.scope.Scope;
import com.sbancuz.offscreen.scope.ScopePipeline;
import com.sbancuz.offscreen.scope.ScreenScope;

import me.eigenraven.lwjgl3ify.api.InputEvents;

public class Mui2UI implements HostUI {

    private final ModularScreen screen;
    private final GuiScreen wrapper;
    private final Scope scope;

    public Mui2UI(ModularScreen screen) {
        this.screen = screen;
        if (screen.getScreenWrapper() != null) {
            this.wrapper = screen.getScreenWrapper()
                .getGuiScreen();
        } else {
            ModularContainer container = new ModularContainer();
            container.constructClientOnly();
            this.wrapper = new GuiContainerWrapper(container, screen).getGuiScreen();
        }

        this.scope = ScopePipeline.builder()
            .always(new ScreenScope(() -> this.wrapper))
            .always(new MuiScope(this.screen))
            .build();
    }

    protected final ModularScreen screen() {
        return screen;
    }

    @Override
    public Scope scope() {
        return scope;
    }

    @Override
    public void update() {
        screen.getContext()
            .tick();
        screen.onUpdate();
    }

    @Override
    public void dispose() {

    }

    @Override
    public GuiScreen getGuiScreen() {
        return wrapper;
    }

    @Override
    public void clearComponentFocus() {
        screen.getContext()
            .removeFocus();
    }

    @Override
    public void draw(Minecraft mc, final int mouseX, final int mouseY, float partialTicks, long now) {
        ClientScreenHandler.drawScreen(screen, wrapper, mouseX, mouseY, partialTicks);
        screen.onFrameUpdate();
    }

    public void onResize(final int width, final int height) {
        screen.onResize(width, height);
    }

    @Override
    public void onHoverUpdate(int mouseX, int mouseY, float partialTicks) {
        int mx = Math.max(mouseX, 0);
        int my = Math.max(mouseY, 0);
        screen.getContext()
            .updateState(mx, my, partialTicks);
        screen.getContext()
            .reset();
    }

    @Override
    public void onMouseClicked(int mouseX, int mouseY, int button) {
        screen.onMousePressed(button);
    }

    @Override
    public void onMouseReleased(int mouseX, int mouseY, int button) {
        screen.onMouseRelease(button);
    }

    @Override
    public void onMouseDrag(int mouseX, int mouseY, int button, long heldMs) {
        screen.onMouseDrag(button, heldMs);
    }

    @Override
    public void onMouseScroll(int mouseX, int mouseY, int scroll) {
        screen.onMouseScroll(scroll > 0 ? UpOrDown.UP : UpOrDown.DOWN, 1);
    }

    @Override
    public void onKeyTyped(char typedChar, int keyCode) {
        screen.onKeyPressed(typedChar, keyCode);
    }

    @Override
    public void onKeyReleased(char typedChar, int keyCode) {
        screen.onKeyRelease(typedChar, keyCode);
    }

    @Override
    public void onTextInput(String text) {
        InputEvents.injectTextEvent(new InputEvents.TextEvent(text));
    }
}
