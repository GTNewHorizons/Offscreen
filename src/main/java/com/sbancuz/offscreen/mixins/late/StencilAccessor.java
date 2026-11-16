package com.sbancuz.offscreen.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.widget.sizer.Area;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

@Mixin(value = Stencil.class, remap = false)
public interface StencilAccessor {

    @Accessor(value = "stencils", remap = false)
    static ObjectArrayList<Area> offscreen$getStencils() {
        throw new AssertionError();
    }

    @Accessor(value = "stencilShapes", remap = false)
    static ObjectArrayList<Runnable> offscreen$getStencilShapes() {
        throw new AssertionError();
    }

    @Accessor(value = "stencilValue", remap = false)
    static int offscreen$getStencilValue() {
        throw new AssertionError();
    }

    @Accessor(value = "stencilValue", remap = false)
    static void offscreen$setStencilValue(int v) {
        throw new AssertionError();
    }
}
