package com.sbancuz.offscreen.window.input;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_CommonEvent;
import org.lwjgl.sdl.SDL_EventFilter;
import org.lwjgl.sdl.SDL_WindowEvent;
import org.lwjglx.input.Keyboard;

import com.sbancuz.offscreen.window.Window;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public final class InputRouter {

    private final Map<Integer, Window> windows;

    private ByteBuffer savedKeyArray;
    private final List<Short> heldScancodes = new ArrayList<>();
    private Window keyShadowOwner;
    private ByteBuffer shadowKeyArray;

    public InputRouter(final Map<Integer, Window> windows) {
        this.windows = windows;
        final SDL_EventFilter filter = SDL_EventFilter.create(this::onSdlEvent);
        SDLEvents.SDL_SetEventFilter(filter, 0L);
    }

    private void acquireKeyStateShadow(final Window owner) {
        if (keyShadowOwner == owner) return;
        if (keyShadowOwner != null) {
            releaseKeyStateShadow();
        }
        final ByteBuffer real = Keyboard.sdlKeyPressedArray;
        if (real == null) return;
        if (shadowKeyArray == null || shadowKeyArray.capacity() != real.capacity()) {
            shadowKeyArray = ByteBuffer.allocateDirect(real.capacity());
        }
        savedKeyArray = real;
        shadowKeyArray.put(0, real, 0, real.capacity());
        Keyboard.sdlKeyPressedArray = shadowKeyArray;
        keyShadowOwner = owner;
    }

    private void releaseKeyStateShadow() {
        if (savedKeyArray == null) return;
        Keyboard.sdlKeyPressedArray = savedKeyArray;
        savedKeyArray = null;
        heldScancodes.clear();
        keyShadowOwner = null;
    }

    private void updateGlobalKeyState(final short scancode, final boolean down) {
        if (savedKeyArray == null) return;
        final ByteBuffer shadow = org.lwjglx.input.Keyboard.sdlKeyPressedArray;
        if (shadow == null || scancode < 0 || scancode >= shadow.capacity()) return;
        shadow.put(scancode, (byte) (down ? 1 : 0));
        if (down) {
            if (!heldScancodes.contains(scancode)) heldScancodes.add(scancode);
        } else {
            heldScancodes.remove((Short) scancode);
        }
    }

    public void releaseIfOwner(final Window owner) {
        if (keyShadowOwner == owner) {
            owner.setFocusState(false);
            releaseKeyStateShadow();
        }
    }

    private boolean onSdlEvent(final long userdata, final long eventPtr) {
        if (windows.isEmpty()) return true;

        final int type = SDL_CommonEvent.ntype(eventPtr);

        final boolean relevant = (type >= SDLEvents.SDL_EVENT_WINDOW_FIRST && type <= SDLEvents.SDL_EVENT_WINDOW_LAST)
            || type == SDLEvents.SDL_EVENT_KEY_DOWN
            || type == SDLEvents.SDL_EVENT_KEY_UP
            || type == SDLEvents.SDL_EVENT_TEXT_INPUT
            || type == SDLEvents.SDL_EVENT_MOUSE_MOTION
            || type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN
            || type == SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP
            || type == SDLEvents.SDL_EVENT_MOUSE_WHEEL;
        if (!relevant) return true;

        final int evWindowId = SDL_WindowEvent.nwindowID(eventPtr);
        final Window window = windows.get(evWindowId);
        if (window == null) return true;

        if (type == SDLEvents.SDL_EVENT_WINDOW_FOCUS_GAINED) {
            window.setFocusState(true);
            acquireKeyStateShadow(window);
        } else if (type == SDLEvents.SDL_EVENT_WINDOW_FOCUS_LOST) {
            window.setFocusState(false);
            if (keyShadowOwner == window) releaseKeyStateShadow();
        }

        window.capture(eventPtr);
        return false;
    }

    public static int sdlToGuiButton(final int sdlButton) {
        return switch (sdlButton) {
            case 1 -> 0;
            case 3 -> 1;
            case 2 -> 2;
            default -> sdlButton - 1;
        };
    }
}
