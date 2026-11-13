package com.sbancuz.offscreen.integration.mui2;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.cleanroommc.modularui.screen.ClientScreenHandler;
import com.cleanroommc.modularui.screen.GuiContainerWrapper;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.scope.MuiScope;
import com.sbancuz.offscreen.scope.Scope;
import com.sbancuz.offscreen.scope.ScopePipeline;
import com.sbancuz.offscreen.scope.ScreenScope;

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

    /** MUI2 takes the pointer from its context, which the main window updates before drawing too. */
    @Override
    public void draw(Minecraft mc, final int mouseX, final int mouseY, float partialTicks, long now) {
        screen.getContext()
            .updateState(Math.max(mouseX, 0), Math.max(mouseY, 0), partialTicks);
        screen.getContext()
            .reset();
        ClientScreenHandler.drawScreen(screen, wrapper, mouseX, mouseY, partialTicks);
        screen.onFrameUpdate();
    }

    public void onResize(final int width, final int height) {
        screen.onResize(width, height);
    }
}
