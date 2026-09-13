package com.sbancuz.offscreen.mixins.early;

import java.io.IOException;

import net.minecraft.client.gui.GuiScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GuiScreen.class)
public interface GuiScreenAccessor {

    @Invoker("mouseClicked")
    void invokeMouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException;

    @Invoker("mouseMovedOrUp")
    void invokeMouseMovedOrUp(int mouseX, int mouseY, int mouseButton);

    @Invoker("keyTyped")
    void invokeKeyTyped(char typedChar, int keyCode) throws IOException;
}
