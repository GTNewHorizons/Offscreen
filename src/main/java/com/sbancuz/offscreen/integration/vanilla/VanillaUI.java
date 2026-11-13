package com.sbancuz.offscreen.integration.vanilla;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;

import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.mixins.early.GuiContainerAccessor;
import com.sbancuz.offscreen.scope.Scope;
import com.sbancuz.offscreen.scope.ScopePipeline;
import com.sbancuz.offscreen.scope.ScreenScope;

/** Hosts any {@link GuiScreen}; input goes through the screen's own {@code handleInput()}. */
public class VanillaUI implements HostUI {

    private final GuiScreen screen;
    private final Scope scope;

    public VanillaUI(GuiScreen screen) {
        this.screen = screen;
        this.scope = ScopePipeline.builder()
            .always(new ScreenScope(this::getGuiScreen))
            .build();
    }

    @Override
    public Scope scope() {
        return scope;
    }

    @Override
    public void dispose() {

    }

    @Override
    public void update() {
        if (screen.width <= 0) return;
        screen.updateScreen();
    }

    @Override
    public GuiScreen getGuiScreen() {
        return screen;
    }

    @Override
    public void onResize(final int width, final int height) {
        if (screen instanceof GuiContainer container) {
            final GuiContainerAccessor accessor = (GuiContainerAccessor) container;
            accessor.setGuiTop((height - accessor.getYSize()) / 2);
            accessor.setGuiLeft((width - accessor.getXSize()) / 2);
        }
    }

    @Override
    public void draw(Minecraft mc, final int mouseX, final int mouseY, float partialTicks, long now) {
        screen.drawScreen(mouseX, mouseY, partialTicks);
    }
}
