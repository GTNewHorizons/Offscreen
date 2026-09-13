package com.sbancuz.offscreen.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.screen.ClientScreenHandler;
import com.cleanroommc.modularui.screen.ModularScreen;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

@Mixin(value = ClientScreenHandler.class, remap = false)
public interface ClientScreenHandlerAccessor {

    @Accessor("currentScreen")
    static ModularScreen getCurrentScreenMui() {
        throw new AssertionError();
    }

    @Accessor("currentScreen")
    static void setCurrentScreenMui(final ModularScreen screen) {
        throw new AssertionError();
    }

    @Accessor("lastMui")
    static IMuiScreen getLastMui() {
        throw new AssertionError();
    }

    @Accessor("lastMui")
    static void setLastMui(final IMuiScreen mui) {
        throw new AssertionError();
    }

    @Accessor("muiStack")
    static ObjectArrayList<IMuiScreen> getMuiStack() {
        throw new AssertionError();
    }
}
