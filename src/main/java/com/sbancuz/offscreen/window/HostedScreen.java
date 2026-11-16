package com.sbancuz.offscreen.window;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;

import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.scope.Scope;
import com.sbancuz.offscreen.scope.ScreenScope;
import com.sbancuz.offscreen.scope.container.ScreenView;
import com.sbancuz.offscreen.scope.container.ServerContainer;
import com.sbancuz.offscreen.scope.container.ServerContainerMultiplexer;

/** One entry of a hosted window's stack: a host UI, and the view it is for the container multiplexer. */
public final class HostedScreen<T extends HostUI> implements ScreenView {

    private static GuiScreen bgSuppressed;

    public static boolean isSuppressingBgFor(final GuiScreen screen) {
        return screen == bgSuppressed;
    }

    private final ServerContainerMultiplexer multiplexer = ServerContainerMultiplexer.INSTANCE;
    private final Window window;
    private final T ui;
    private final Minecraft mc;

    private int width = -1;
    private int height = -1;
    private boolean resizeRequested = false;

    HostedScreen(final Window window, final T ui) {
        this.window = window;
        this.ui = ui;
        this.mc = Minecraft.getMinecraft();
    }

    public Window window() {
        return window;
    }

    public T ui() {
        return ui;
    }

    public Scope scope() {
        return ui.scope();
    }

    @Override
    public GuiScreen screen() {
        return ui.getGuiScreen();
    }

    public void resize(final int guiWidth, final int guiHeight) {
        this.width = guiWidth;
        this.height = guiHeight;
        this.resizeRequested = false;

        screen().setWorldAndResolution(mc, guiWidth, guiHeight);
        ui.onResize(width, height);
    }

    public void dispose() {
        ui.dispose();
    }

    public boolean needsResize(final int width, final int height) {
        return resizeRequested || this.width != width || this.height != height;
    }

    public void requestResize() {
        resizeRequested = true;
    }

    /** The screen, then what the multiplexer shows over a view. */
    public void draw(Minecraft mc, int mouseX, int mouseY, float partialTicks, long now) {
        bgSuppressed = screen();
        ui.draw(mc, mouseX, mouseY, partialTicks, now);
        bgSuppressed = null;
        multiplexer.overlay.draw(this, width, height);
    }

    /** A gated entry is a snapshot: it gets no {@code updateScreen}. */
    public void update() {
        if (receivesInput()) ui.update();
    }

    /** The screen's own input handling, behind the gate every view's input passes. */
    public void handleInput() {
        multiplexer.gatedInput.handleInput(this, ui::handleInput);
    }

    /**
     * While the entry runs, Minecraft's globals are its screen, the window's size, and as {@code openContainer} its
     * own container, else the gating one, else the inventory.
     */
    ScreenScope screenScope() {
        Container open = mc.thePlayer.inventoryContainer;
        if (screen() instanceof GuiContainer gc) open = gc.inventorySlots;
        else if (gatingContainer() instanceof ServerContainer b) open = b.clientContainer;
        return new ScreenScope(multiplexer.mainWindow, screen(), open, window.getPixelWidth(), window.getPixelHeight());
    }

    @Override
    public @Nullable ServerContainer gatingContainer() {
        final HostedScreen<?> base = window.stack()
            .boundBaseOf(this);
        return multiplexer.containerOf(base.screen());
    }

    @Override
    public GuiScreen topScreenOfPlace() {
        return window.stack()
            .top()
            .screen();
    }

    @Override
    public void removeFromPlace() {
        window.stack()
            .removeFrom(this);
    }

    @Override
    public boolean samePlace(final ScreenView other) {
        return other instanceof HostedScreen<?>h && h.window == window;
    }

    @Override
    public boolean canPlace() {
        return window.isOpen() && window.stack()
            .contains(this);
    }

    @Override
    public Runnable place(final GuiScreen s) {
        return window.stack()
            .placeArrivalOver(this, s);
    }

    /** Vanilla is cancelled: the GUI sees its change at once, the stack after the run. */
    @Override
    public void onDisplayRequested(@Nullable final GuiScreen s, final Consumer<GuiScreen> vanilla) {
        mc.currentScreen = s;
        if (s == null) onCloseRequested(true);
        else multiplexer.routing()
            .runAfterScope(
                () -> window.stack()
                    .applyDisplayRequest(this, s));
    }

    @Override
    public void onCloseRequested(final boolean packet) {
        multiplexer.routing()
            .runAfterScope(() -> close(packet));
    }

    @Override
    public String toString() {
        return screen().getClass()
            .getSimpleName();
    }
}
