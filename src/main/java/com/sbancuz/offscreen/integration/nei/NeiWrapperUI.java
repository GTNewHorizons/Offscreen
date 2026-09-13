package com.sbancuz.offscreen.integration.nei;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;

import org.lwjglx.input.KeyCodes;
import org.lwjglx.input.Keyboard;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.scope.Scope;
import com.sbancuz.offscreen.window.input.KeyEvent;

import codechicken.nei.LayoutManager;
import codechicken.nei.guihook.GuiContainerManager;

public final class NeiWrapperUI implements HostUI {

    private static final Keyboard.KeyEvent[] PRESERVED_KEYS = new Keyboard.KeyEvent[256];

    private final HostUI delegate;

    public NeiWrapperUI(final HostUI delegate) {
        this.delegate = delegate;
    }

    @Override
    public Scope scope() {
        return delegate.scope();
    }

    @Override
    public void dispose() {
        delegate.dispose();
    }

    @Override
    public void onResize(final int width, final int height) {
        delegate.onResize(width, height);
    }

    @Override
    public GuiScreen getGuiScreen() {
        return delegate.getGuiScreen();
    }

    @Override
    public void draw(final Minecraft mc, final int mouseX, final int mouseY, final float partialTicks, final long now) {
        delegate.draw(mc, mouseX, mouseY, partialTicks, now);
    }

    @Override
    public void update() {
        delegate.update();
    }

    @Override
    public void onHoverUpdate(int mouseX, int mouseY, float partialTicks) {
        delegate.onHoverUpdate(mouseX, mouseY, partialTicks);
    }

    @Override
    public void onMouseClicked(int mouseX, int mouseY, int button) {
        if (withNeiBool(mgr -> mgr.mouseClicked(mouseX, mouseY, button))) {
            delegate.clearComponentFocus();
            return;
        }
        LayoutManager.setInputFocused(null);
        delegate.onMouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onMouseReleased(int mouseX, int mouseY, int button) {
        if (withNeiBool(mgr -> mgr.overrideMouseUp(mouseX, mouseY, button))) return;
        delegate.onMouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onMouseDrag(int mouseX, int mouseY, int button, long heldMs) {
        withNei(mgr -> mgr.mouseDragged(mouseX, mouseY, button, heldMs));
        delegate.onMouseDrag(mouseX, mouseY, button, heldMs);
    }

    @Override
    public void onMouseScroll(int mouseX, int mouseY, int scroll) {
        if (withNeiBool(mgr -> {
            final int scrolled = scroll > 0 ? 120 : -120;
            final GuiContainer gc = (GuiContainer) delegate.getGuiScreen();
            for (var h : GuiContainerManager.inputHandlers) h.onMouseScrolled(gc, mouseX, mouseY, scrolled);
            for (var h : GuiContainerManager.inputHandlers)
                if (h.mouseScrolled((GuiContainer) delegate.getGuiScreen(), mouseX, mouseY, scrolled)) return true;
            return false;
        })) return;
        delegate.onMouseScroll(mouseX, mouseY, scroll);
    }

    @Override
    public void onKeyPressed(KeyEvent key) {
        if (key.pressed()) {
            withNei(mgr -> {
                final var queue = Keyboard.eventQueue;
                int preservedCount = 0;
                Keyboard.KeyEvent pending;
                while (preservedCount < PRESERVED_KEYS.length && (pending = queue.poll()) != null) {
                    PRESERVED_KEYS[preservedCount++] = pending;
                }
                try {
                    Keyboard.addRawKeyEvent(
                        new Keyboard.KeyEvent(
                            key.keyCode(),
                            key.keyCode(),
                            key.character(),
                            Keyboard.KeyState.PRESS,
                            System.nanoTime()));
                    final ByteBuffer keyArray = Keyboard.sdlKeyPressedArray;
                    final int sdlScancode = key.sdlScanCode() != 0 ? key.sdlScanCode()
                        : KeyCodes.lwjglToSdlScancode(key.keyCode());
                    final byte prev = (keyArray != null && sdlScancode > 0 && sdlScancode < keyArray.limit())
                        ? keyArray.get(sdlScancode)
                        : 0;
                    if (keyArray != null && sdlScancode > 0 && sdlScancode < keyArray.limit())
                        keyArray.put(sdlScancode, (byte) 1);
                    try {
                        mgr.handleKeyboardInput();
                    } finally {
                        if (keyArray != null && sdlScancode > 0 && sdlScancode < keyArray.limit())
                            keyArray.put(sdlScancode, prev);

                        queue.poll();
                    }
                } finally {
                    for (int i = 0; i < preservedCount; i++) {
                        queue.add(PRESERVED_KEYS[i]);
                        PRESERVED_KEYS[i] = null;
                    }
                }
            });
        }
        delegate.onKeyPressed(key);
    }

    @Override
    public void onKeyReleased(char typedChar, int keyCode) {
        delegate.onKeyReleased(typedChar, keyCode);
    }

    @Override
    public void onTextInput(String text) {
        delegate.onTextInput(text);
    }

    private void withNei(Consumer<GuiContainerManager> action) {
        final GuiScreen gs = delegate.getGuiScreen();
        if (!(gs instanceof GuiContainer gc)) return;
        final GuiContainerManager mgr = NeiScope.getManager(gc);
        if (mgr == null) {
            Offscreen.LOG.warn(
                "[Offscreen] NeiWrapper has no manager for {}",
                gc.getClass()
                    .getSimpleName());
            return;
        }
        if (Minecraft.getMinecraft().theWorld == null) return;
        try {
            action.accept(mgr);
        } catch (Throwable t) {
            Offscreen.LOG.warn("[Offscreen] NeiWrapper failed", t);
        }
    }

    private boolean withNeiBool(Function<GuiContainerManager, Boolean> action) {
        final GuiScreen gs = delegate.getGuiScreen();
        if (!(gs instanceof GuiContainer gc)) return false;
        final GuiContainerManager mgr = NeiScope.getManager(gc);
        if (mgr == null || Minecraft.getMinecraft().theWorld == null) return false;
        try {
            return action.apply(mgr);
        } catch (Throwable t) {
            Offscreen.LOG.warn("[Offscreen] NeiWrapper failed", t);
            return false;
        }
    }
}
