package com.sbancuz.offscreen.window;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.container.GatedViewInput;
import com.sbancuz.offscreen.container.HeldClick;
import com.sbancuz.offscreen.container.ScreenView;
import com.sbancuz.offscreen.container.ServerContainer;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.container.context.ExecutionContext;
import com.sbancuz.offscreen.scope.Scope;
import com.sbancuz.offscreen.window.input.FrameEvent;

/** One entry of a hosted window's stack: a host UI, and the view it is for the container multiplexer. */
public final class HostedScreen<T extends HostUI> implements ScreenView {

    private static GuiScreen bgSuppressed;

    public static boolean isSuppressingBgFor(final GuiScreen screen) {
        return screen == bgSuppressed;
    }

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

    public void draw(Minecraft mc, int mouseX, int mouseY, float partialTicks, long now) {
        bgSuppressed = screen();
        ui.draw(mc, mouseX, mouseY, partialTicks, now);
        bgSuppressed = null;
    }

    public void requestResize() {
        resizeRequested = true;
    }

    public void update() {
        ui.update();
    }

    // ================================================================== input and updates

    /** Whether the entry gets {@code updateScreen}. */
    public boolean receivesUpdates() {
        return !ServerContainerMultiplexer.INSTANCE.isInWorld() || receivesInput();
    }

    /** Inside the entry's scope: a gated entry gets hover only, one receiving input its held click first. */
    public void deliverInput(final FrameEvent ev, final float partialTicks) {
        final ServerContainerMultiplexer multiplexer = ServerContainerMultiplexer.INSTANCE;
        if (!multiplexer.isInWorld()) {
            ui.onInput(ev, partialTicks);
            return;
        }
        pollMoveKey(ev);

        if (!receivesInput()) {
            deliverGatedFrame(ev, partialTicks);
            return;
        }
        multiplexer.gatedInput.deliverHeldClick(this, h -> replayHeldClick(ev, h));
        ui.onInput(ev, partialTicks);
    }

    /** The move key: the container view and its children move to the main window after the run. */
    private void pollMoveKey(final FrameEvent ev) {
        final ServerContainerMultiplexer multiplexer = ServerContainerMultiplexer.INSTANCE;
        for (int i = 0; i < ev.keyCount; i++) {
            if (!ev.keys[i].pressed() || !multiplexer.mainWindow.isMoveKey(ev.keys[i].keyCode())) continue;
            multiplexer.contexts.current()
                .runAfterScope(() -> multiplexer.mainWindow.moveFromWindow(window));
            ev.keyCount = 0;
        }
    }

    /** The held click becomes this frame's press. */
    private void replayHeldClick(final FrameEvent ev, final HeldClick h) {
        ev.mouseX = h.x();
        ev.mouseY = h.y();
        ev.pressButton = h.button();
        if (h.released() && ev.releaseButton == -1) ev.releaseButton = h.button();
    }

    /** Hover only; presses ask for activation, keys may close. Nothing else of the frame reaches the UI. */
    private void deliverGatedFrame(final FrameEvent ev, final float partialTicks) {
        final GatedViewInput gatedInput = ServerContainerMultiplexer.INSTANCE.gatedInput;
        ui.onHoverUpdate(ev.mouseX, ev.mouseY, partialTicks);
        if (ev.pressButton != -1) gatedInput.onPress(this, ev.mouseX, ev.mouseY, ev.pressButton);
        if (ev.releaseButton != -1) gatedInput.onRelease(ev.releaseButton);
        for (int i = 0; i < ev.keyCount; i++) {
            if (ev.keys[i].pressed() && gatedInput.onKey(this, ev.keys[i].keyCode())) break;
        }

        ev.pressButton = ev.releaseButton = -1;
        ev.wheelDelta = 0f;
        ev.keyCount = 0;
        ev.text.setLength(0);
    }

    // ================================================================== the entry as a view

    @Override
    public @Nullable ServerContainer gatingContainer() {
        final HostedScreen<?> base = window.stack()
            .boundBaseOf(this);
        return ServerContainerMultiplexer.INSTANCE.containerOf(base.screen());
    }

    @Override
    public GuiScreen topScreenOfPlace() {
        return window.stack()
            .top()
            .screen();
    }

    @Override
    public void removeFromPlace() {
        window.removeFrom(this);
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
        return window.placeArrivalOver(this, s);
    }

    /** Vanilla is cancelled: the GUI sees its change at once, the stack after the run. */
    @Override
    public void onDisplayRequested(@Nullable final GuiScreen s, final Consumer<GuiScreen> vanilla) {
        mc.currentScreen = s;
        final ExecutionContext run = ServerContainerMultiplexer.INSTANCE.contexts.current();
        if (s == null) run.runAfterScope(() -> close(true));
        else run.runAfterScope(() -> window.applyDisplayRequest(this, s));
    }

    @Override
    public void onCloseRequested(final boolean packet) {
        ServerContainerMultiplexer.INSTANCE.contexts.current()
            .runAfterScope(() -> close(packet));
    }

    @Override
    public String toString() {
        return screen().getClass()
            .getSimpleName();
    }
}
