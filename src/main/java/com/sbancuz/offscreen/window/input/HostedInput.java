package com.sbancuz.offscreen.window.input;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.client.Minecraft;

import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.scope.Scope;

import me.eigenraven.lwjgl3ify.api.InputEvents;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import me.eigenraven.lwjgl3ify.client.TextFieldHandler;

/**
 * A hosted window's lwjgl input: its own {@link Mouse} event queue, pointer and held buttons, and its own
 * {@link Keyboard} event queue. The window records its SDL events here the way lwjgl3ify records the main window's;
 * inside the scope these are the live lwjgl state, so a hosted screen's own {@code handleInput()} (and the hooks
 * MUI2 and NEI patch into it), and anything polling {@code Mouse}/{@code Keyboard} while drawing, sees this window
 * exactly as a main-window screen sees the main window.
 * <p>
 * lwjgl3ify keeps the mouse in private statics of a class no transformer may touch, so they are swapped through
 * reflection, resolved and checked once.
 */
@Lwjgl3Aware
public final class HostedInput implements Scope {

    /** The mouse statics that make up one window's input; the pointer comes first. */
    private static final String[] MOUSE_FIELD_NAMES = { "x", "y", "latestX", "latestY", "lastEventX", "lastEventY",
        "queue", "buttonEvents", "buttonEventStates", "xEvents", "yEvents", "wheelEvents", "lastxEvents", "lastyEvents",
        "nanoTimeEvents", "dx", "dy", "dxAccum", "dyAccum", "dxFloat", "dyFloat", "dwheel", "fractionalWheelPosition",
        "sdlMouseButtonFlags" };
    private static final int X = 0, Y = 1, NEW_X = 2, NEW_Y = 3;
    private static final int KEY_QUEUE_CAPACITY = 256;

    /** Null when lwjgl3ify's Mouse has another layout: hosted windows then get keyboard input only. */
    private static final Field[] MOUSE_FIELDS = resolveMouseFields();

    private final Minecraft mc = Minecraft.getMinecraft();

    private final Object[] windowMouse;
    private final Object[] mainMouse = new Object[MOUSE_FIELD_NAMES.length];
    private Queue<Keyboard.KeyEvent> windowKeys = newKeyQueue();
    private Queue<Keyboard.KeyEvent> mainKeys;

    /** lwjgl3ify hands key and text events to its listeners when it records them; hosted ones wait for the scope. */
    private final ArrayDeque<Object> listenerEvents = new ArrayDeque<>();

    private int heldButtons;
    private int depth;

    public HostedInput() {
        windowMouse = MOUSE_FIELDS == null ? null : emptyMouseState();
    }

    /** Runs {@code recording} with this window's input live, so lwjgl3ify's own methods record into it. */
    public void record(final Runnable recording) {
        enter();
        try {
            recording.run();
        } finally {
            restore();
        }
    }

    /**
     * The pointer moved to GUI position {@code guiX, guiY} of a {@code guiWidth x guiHeight} screen. lwjgl's pixel is
     * chosen so that {@code GuiScreen.handleMouseInput}'s conversion with {@code mc.displayWidth/displayHeight} gives
     * that position back.
     */
    public void pointerMoved(final int guiX, final int guiY, final int guiWidth, final int guiHeight) {
        if (MOUSE_FIELDS == null) return;
        final int width = Math.max(1, guiWidth), height = Math.max(1, guiHeight);
        final int x = ceilDiv(guiX * mc.displayWidth, width);
        final int y = ceilDiv((height - 1 - guiY) * mc.displayHeight, height);
        try {
            MOUSE_FIELDS[X].setInt(null, x);
            MOUSE_FIELDS[Y].setInt(null, y);
            MOUSE_FIELDS[NEW_X].setInt(null, x);
            MOUSE_FIELDS[NEW_Y].setInt(null, y);
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        Mouse.addButtonEvent(-1, false); // a move: no button, no wheel
    }

    public void buttonChanged(final int lwjglButton, final boolean down) {
        if (MOUSE_FIELDS == null || lwjglButton < 0) return;
        final int bit = 1 << (Mouse.lwjglToSdlMouseButton(lwjglButton) - 1);
        heldButtons = down ? heldButtons | bit : heldButtons & ~bit;
        Mouse.sdlMouseButtonFlags = heldButtons;
        Mouse.addButtonEvent(lwjglButton, down);
    }

    /** Raw SDL wheel delta; lwjgl3ify applies the player's scroll settings. */
    public void wheelScrolled(final float delta) {
        if (MOUSE_FIELDS == null || delta == 0f) return;
        Mouse.addWheelEvent(delta);
    }

    /** A key event; repeats are dropped unless the screen enabled them, as in the main window. */
    public void keyChanged(final InputEvents.KeyEvent listenerEvent, final Keyboard.KeyEvent event) {
        listenerEvents.add(listenerEvent);
        Keyboard.addRawKeyEvent(event);
    }

    public void textEntered(final String text) {
        listenerEvents.add(new InputEvents.TextEvent(text));
    }

    /**
     * Hands the recorded key and text events to lwjgl3ify's listeners, inside the scope so the hosted screen is
     * {@code currentScreen}. Committed text a text field took then also arrives as key-less character events, as
     * lwjgl3ify sends it in the main window; hosted windows always have SDL text input on, the main window only
     * while a text field is focused, so text nobody took is not sent as characters.
     */
    public void deliverListenerEvents() {
        Object event;
        while ((event = listenerEvents.poll()) != null) {
            if (event instanceof InputEvents.KeyEvent key) {
                InputEvents.injectKeyEvent(key);
                continue;
            }
            final InputEvents.TextEvent text = (InputEvents.TextEvent) event;
            final int buffered = TextFieldHandler.textBuffer.length();
            InputEvents.injectTextEvent(text);
            if (TextFieldHandler.textBuffer.length() == buffered) continue;
            text.text.codePoints()
                .forEachOrdered(c -> Keyboard.addCharEvent(Keyboard.KEY_NONE, c));
        }
    }

    @Override
    public void enter() {
        if (depth++ > 0) return;
        mainKeys = Keyboard.eventQueue;
        Keyboard.eventQueue = windowKeys;
        exchangeMouse(mainMouse, windowMouse);
    }

    @Override
    public void restore() {
        if (--depth > 0) return;
        windowKeys = Keyboard.eventQueue;
        Keyboard.eventQueue = mainKeys;
        mainKeys = null;
        exchangeMouse(windowMouse, mainMouse);
    }

    /** Stores the live mouse statics into {@code into} and makes {@code from} live. */
    private static void exchangeMouse(final Object[] into, final Object[] from) {
        if (MOUSE_FIELDS == null) return;
        try {
            for (int i = 0; i < MOUSE_FIELDS.length; i++) {
                into[i] = MOUSE_FIELDS[i].get(null);
                MOUSE_FIELDS[i].set(null, from[i]);
            }
        } catch (final IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field[] resolveMouseFields() {
        try {
            final Field[] fields = new Field[MOUSE_FIELD_NAMES.length];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = Mouse.class.getDeclaredField(MOUSE_FIELD_NAMES[i]);
                fields[i].setAccessible(true);
            }
            return fields;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            Offscreen.LOG.error("[Offscreen] unexpected lwjgl3ify Mouse layout; hosted windows get no mouse input", e);
            return null;
        }
    }

    /** Empty arrays and an empty queue of the live ones' sizes, zero elsewhere. */
    private static Object[] emptyMouseState() {
        final Object[] state = new Object[MOUSE_FIELDS.length];
        try {
            for (int i = 0; i < state.length; i++) {
                final Class<?> type = MOUSE_FIELDS[i].getType();
                final Object live = MOUSE_FIELDS[i].get(null);
                if (type.isArray()) state[i] = Array.newInstance(type.getComponentType(), Array.getLength(live));
                else if (type == int.class) state[i] = 0;
                else if (type == float.class) state[i] = 0f;
                else if (type == double.class) state[i] = 0d;
                else state[i] = emptyQueueLike(live);
            }
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("lwjgl3ify Mouse state could not be copied", e);
        }
        return state;
    }

    private static Object emptyQueueLike(final Object live) throws ReflectiveOperationException {
        final Method capacity = live.getClass()
            .getDeclaredMethod("getMaxEvents");
        capacity.setAccessible(true);
        final Constructor<?> queue = live.getClass()
            .getDeclaredConstructor(int.class);
        queue.setAccessible(true);
        return queue.newInstance((int) capacity.invoke(live));
    }

    /** lwjgl3ify's key queue keeps the current event at its head, so it starts with a blank one. */
    private static Queue<Keyboard.KeyEvent> newKeyQueue() {
        final Queue<Keyboard.KeyEvent> q = new ArrayBlockingQueue<>(KEY_QUEUE_CAPACITY);
        q.add(new Keyboard.KeyEvent(0, 0, '\0', Keyboard.KeyState.RELEASE, System.nanoTime()));
        return q;
    }

    private static int ceilDiv(final int a, final int b) {
        return -Math.floorDiv(-a, b);
    }
}
