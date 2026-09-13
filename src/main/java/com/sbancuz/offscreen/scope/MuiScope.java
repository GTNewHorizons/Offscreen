package com.sbancuz.offscreen.scope;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.sbancuz.offscreen.mixins.late.ClientScreenHandlerAccessor;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

public class MuiScope implements Scope {

    private final ModularScreen screen;
    private ModularScreen savedCurrentScreen;
    private IMuiScreen savedLastMui;
    private ObjectArrayList<IMuiScreen> savedMuiStack;

    public MuiScope(ModularScreen screen) {
        this.screen = screen;
    }

    @Override
    public void enter() {
        savedCurrentScreen = ClientScreenHandlerAccessor.getCurrentScreenMui();
        savedLastMui = ClientScreenHandlerAccessor.getLastMui();
        savedMuiStack = new ObjectArrayList<>(ClientScreenHandlerAccessor.getMuiStack());

        ClientScreenHandlerAccessor.setCurrentScreenMui(screen);
        ClientScreenHandlerAccessor.setLastMui(screen.getScreenWrapper());
        final ObjectArrayList<IMuiScreen> stack = ClientScreenHandlerAccessor.getMuiStack();
        stack.clear();
        stack.add(screen.getScreenWrapper());
    }

    @Override
    public void restore() {
        ClientScreenHandlerAccessor.setCurrentScreenMui(savedCurrentScreen);
        ClientScreenHandlerAccessor.setLastMui(savedLastMui);
        final ObjectArrayList<IMuiScreen> stack = ClientScreenHandlerAccessor.getMuiStack();
        stack.clear();
        stack.addAll(savedMuiStack);

        savedCurrentScreen = null;
        savedLastMui = null;
        savedMuiStack = null;
    }
}
