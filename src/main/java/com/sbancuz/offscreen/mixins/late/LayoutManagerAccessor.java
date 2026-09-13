package com.sbancuz.offscreen.mixins.late;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import codechicken.nei.LayoutManager;
import codechicken.nei.Widget;

@Mixin(value = LayoutManager.class, remap = false)
public interface LayoutManagerAccessor {

    @Accessor("drawWidgets")
    static Set<Widget> getDrawWidgets() {
        throw new AssertionError();
    }

    @Accessor("drawWidgets")
    static void setDrawWidgets(final Set<Widget> widgets) {
        throw new AssertionError();
    }

    @Accessor("controlWidgets")
    static Set<Widget> getControlWidgets() {
        throw new AssertionError();
    }

    @Accessor("controlWidgets")
    static void setControlWidgets(final Set<Widget> widgets) {
        throw new AssertionError();
    }

    @Invoker("init")
    static void invokeInit() {
        throw new AssertionError();
    }
}
