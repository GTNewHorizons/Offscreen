package com.sbancuz.offscreen.api;

import java.util.Map;
import java.util.WeakHashMap;

import com.sbancuz.offscreen.window.input.KeyEvent;
import me.eigenraven.lwjgl3ify.api.InputEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.scope.Scope;
import com.sbancuz.offscreen.window.input.FrameEvent;

public interface HostUI {

    default Scope scope() {
        return Scope.NOP;
    }

    default void update() {}

    void dispose();

    default void onResize(final int width, final int height) {}

    GuiScreen getGuiScreen();

    void draw(Minecraft mc, int mouseX, int mouseY, float partialTicks, long now);

    /**
     * Clean default dispatch for all screens.
     * Handles hover, press/release/drag/wheel/keys/text and clears the event fields so a wrapper
     * can give NEI first chance and suppress the delegate by clearing before delegating.
     * Implementations override the smaller methods (onMouseClicked etc.) instead of this.
     * Ordering matches pre-wrapper Mui2UI: hover -> press/release -> drag -> wheel -> keys -> text
     */
    default void onInput(FrameEvent event, float partialTicks) {
        onHoverUpdate(event.mouseX, event.mouseY, partialTicks);

        if (event.pressButton != -1) {
            final int button = event.pressButton;
            event.pressButton = -1;
            onMouseClicked(event.mouseX, event.mouseY, button);
            DragState.get(this)
                .startDrag(button);
        }
        if (event.releaseButton != -1) {
            final int button = event.releaseButton;
            event.releaseButton = -1;
            onMouseReleased(event.mouseX, event.mouseY, button);
            DragState.get(this)
                .stopDrag(button);
        }
        if (DragState.get(this)
            .isDragging()) {
            final DragState ds = DragState.get(this);
            onMouseDrag(event.mouseX, event.mouseY, ds.button, System.currentTimeMillis() - ds.startMs);
        }
        if (event.wheelDelta != 0f) {
            final int scroll = event.wheelDelta > 0 ? 1 : -1;
            event.wheelDelta = 0f;
            onMouseScroll(event.mouseX, event.mouseY, scroll);
        }
        // Keys before text – matches Mui2UI pre-wrapper ordering (keys then text)
        for (int i = 0; i < event.keyCount; i++) {
            final KeyEvent key = event.keys[i];
            onKeyPressed(key);
        }
        event.keyCount = 0;

        if (!event.text.isEmpty()) {
            final String text = event.text.toString();
            event.text.setLength(0);
            onTextInput(text);
        }
    }

    default void onHoverUpdate(int mouseX, int mouseY, float partialTicks) {}

    default void onMouseClicked(int mouseX, int mouseY, int button) {}

    default void onMouseReleased(int mouseX, int mouseY, int button) {}

    default void onMouseDrag(int mouseX, int mouseY, int button, long heldMs) {}

    default void onMouseScroll(int mouseX, int mouseY, int scroll) {}

    default void onKeyTyped(char typedChar, int keyCode) {}

    default void onKeyReleased(char typedChar, int keyCode) {}

    default void onKeyPressed(KeyEvent key) {
        if (key.pressed()) onKeyTyped(key.character(), key.keyCode());
        else onKeyReleased(key.character(), key.keyCode());
        InputEvents.injectKeyEvent(
            new InputEvents.KeyEvent(
                key.keyCode(),
                0,
                key.sdlKeyCode(),
                key.sdlScanCode(),
                0,
                key.pressed() ? InputEvents.KeyAction.PRESSED
                    : InputEvents.KeyAction.RELEASED,
                (short) key.sdlMod(),
                0L));
    }

    default void onTextInput(String text) {}

    /**
     * Called when another component (NEI search field, MUI2 text field, etc.) steals focus.
     * Each implementation clears its own focused widget state so focus is mutually exclusive.
     */
    default void clearComponentFocus() {}

    final class DragState {

        private int button = -1;
        private long startMs;

        private static final Map<HostUI, DragState> MAP = new WeakHashMap<>();

        static DragState get(HostUI ui) {
            return MAP.computeIfAbsent(ui, k -> new DragState());
        }

        void startDrag(int b) {
            button = b;
            startMs = System.currentTimeMillis();
        }

        void stopDrag(int b) {
            if (button == b) button = -1;
        }

        boolean isDragging() {
            return button != -1;
        }
    }
}
