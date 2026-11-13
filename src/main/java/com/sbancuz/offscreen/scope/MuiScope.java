package com.sbancuz.offscreen.scope;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.sbancuz.offscreen.mixins.late.ClientScreenHandlerAccessor;
import com.sbancuz.offscreen.mixins.late.OverlayStackAccessor;
import com.sbancuz.offscreen.mixins.late.StencilAccessor;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * ModularUI2 static state for a hosted view: {@code ClientScreenHandler} statics, the
 * global {@code OverlayStack} (main-window overlays are not part of a hosted view) and the
 * global {@code Stencil} stack. Enter is atomic: everything is saved before anything changes.
 */
public class MuiScope implements Scope {

    private final ModularScreen screen;

    // the global ModularUI2 state saved on enter
    private ModularScreen savedCurrentScreen;
    private IMuiScreen savedLastMui;
    private ObjectArrayList<IMuiScreen> savedMuiStack;
    private List<ModularScreen> savedOverlay;
    private List<Area> savedStencils;
    private List<Runnable> savedStencilShapes;
    private int savedStencilValue;
    private boolean entered;

    public MuiScope(ModularScreen screen) {
        this.screen = screen;
    }

    @Override
    public void enter() {
        // save everything first
        final ModularScreen cur = ClientScreenHandlerAccessor.getCurrentScreenMui();
        final IMuiScreen last = ClientScreenHandlerAccessor.getLastMui();
        final ObjectArrayList<IMuiScreen> stack = ClientScreenHandlerAccessor.getMuiStack();
        final List<ModularScreen> overlay = OverlayStackAccessor.offscreen$getOverlay();
        final ObjectArrayList<Area> stencils = StencilAccessor.offscreen$getStencils();
        final ObjectArrayList<Runnable> shapes = StencilAccessor.offscreen$getStencilShapes();

        savedCurrentScreen = cur;
        savedLastMui = last;
        savedMuiStack = new ObjectArrayList<>(stack);
        savedOverlay = new ArrayList<>(overlay);
        savedStencils = new ArrayList<>(stencils);
        savedStencilShapes = new ArrayList<>(shapes);
        savedStencilValue = StencilAccessor.offscreen$getStencilValue();
        entered = true;

        // then apply
        ClientScreenHandlerAccessor.setCurrentScreenMui(screen);
        ClientScreenHandlerAccessor.setLastMui(screen.getScreenWrapper());
        stack.clear();
        stack.add(screen.getScreenWrapper());

        // the hosted screen starts with no overlay and no stencil
        overlay.clear();
        stencils.clear();
        shapes.clear();
        StencilAccessor.offscreen$setStencilValue(0);
    }

    @Override
    public void restore() {
        if (!entered) return;
        entered = false;

        ClientScreenHandlerAccessor.setCurrentScreenMui(savedCurrentScreen);
        ClientScreenHandlerAccessor.setLastMui(savedLastMui);
        replace(ClientScreenHandlerAccessor.getMuiStack(), savedMuiStack);
        replace(OverlayStackAccessor.offscreen$getOverlay(), savedOverlay);
        replace(StencilAccessor.offscreen$getStencils(), savedStencils);
        replace(StencilAccessor.offscreen$getStencilShapes(), savedStencilShapes);
        StencilAccessor.offscreen$setStencilValue(savedStencilValue);

        savedCurrentScreen = null;
        savedLastMui = null;
        savedMuiStack = null;
        savedOverlay = null;
        savedStencils = null;
        savedStencilShapes = null;
    }

    private static <T> void replace(final List<T> live, final List<T> saved) {
        live.clear();
        live.addAll(saved);
    }
}
