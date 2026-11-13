package com.sbancuz.offscreen.mixins.late;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.cleanroommc.modularui.overlay.OverlayStack;
import com.cleanroommc.modularui.screen.ModularScreen;

@Mixin(value = OverlayStack.class, remap = false)
public interface OverlayStackAccessor {

    @Accessor(value = "overlay", remap = false)
    static List<ModularScreen> offscreen$getOverlay() {
        throw new AssertionError();
    }
}
