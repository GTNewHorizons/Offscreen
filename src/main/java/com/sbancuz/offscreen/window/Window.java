package com.sbancuz.offscreen.window;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_CommonEvent;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryUtil;
import org.lwjglx.input.KeyCodes;
import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;
import org.lwjglx.opengl.ContextGL;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.DrawableGL;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.core.Driver;
import com.sbancuz.offscreen.mixins.early.MinecraftAccessor;
import com.sbancuz.offscreen.scope.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.window.input.HostedInput;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import me.eigenraven.lwjgl3ify.api.InputEvents;
import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import me.eigenraven.lwjgl3ify.client.MainThreadExec;

@Lwjgl3Aware
public final class Window {

    public static final int DEFAULT_WIDTH = 1280;
    public static final int DEFAULT_HEIGHT = 800;
    private static final int RING_CAPACITY = 256;

    @Getter
    private volatile int id;
    private long sdlPtr;
    private ContextGL sharedContext;

    @Getter
    private final String title;

    private final Renderer renderer = new Renderer();
    /** The lwjgl input the hosted screens read; SDL events are recorded into it as they are drained. */
    private final HostedInput input = new HostedInput();
    private final HostedStack screenStack = new HostedStack(this, input);

    @Getter
    private int pixelWidth = DEFAULT_WIDTH;
    @Getter
    private int pixelHeight = DEFAULT_HEIGHT;

    private int guiWidth = 0;
    private int guiHeight = 0;

    private final SDL_Event.Buffer ring = SDL_Event.calloc(RING_CAPACITY);
    private int ringHead;
    private int ringTail;

    @Setter
    private volatile boolean focusState;

    /** The pointer in GUI coordinates, -1 before it entered the window. */
    private int mouseX = -1, mouseY = -1;
    private boolean closeRequested, moveRequested;

    public Window(final String title) {
        this.title = title;

        if (!MainThreadExec.runOnMainThread(() -> SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO))) {
            Offscreen.LOG.error("[Offscreen] SDL_Init failed: {}", sdlError());
            return;
        }

        Display.glContextMutex.lock();
        try {
            if (!(Display.getDrawable() instanceof DrawableGL drawable)) {
                Offscreen.LOG.error(
                    "[Offscreen] unexpected drawable type {}",
                    Display.getDrawable()
                        .getClass());
                return;
            }
            sharedContext = drawable.createSharedContext();
        } finally {
            Display.glContextMutex.unlock();
        }

        restoreMcContext();

        sdlPtr = MainThreadExec.runOnMainThread(
            () -> SDLVideo.SDL_CreateWindow(
                title,
                DEFAULT_WIDTH,
                DEFAULT_HEIGHT,
                SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_RESIZABLE | SDLVideo.SDL_WINDOW_HIDDEN));
        restoreMcContext();

        if (sdlPtr == 0L) {
            Offscreen.LOG.error("[Offscreen] SDL_CreateWindow failed: {}", sdlError());
            return;
        }

        id = SDLVideo.SDL_GetWindowID(sdlPtr);

        if (!SDLVideo.SDL_GL_MakeCurrent(sdlPtr, sharedContext.sdlContext)) {
            Offscreen.LOG.error("[Offscreen] SDL_GL_MakeCurrent failed: {}", sdlError());
            return;
        }
        SDLVideo.SDL_GL_SetSwapInterval(0);
        restoreMcContext();

        probeDrawableSize();

        Driver.INSTANCE.trackWindow(this);
        MainThreadExec.runOnMainThread(() -> {
            if (!SDLVideo.SDL_ShowWindow(sdlPtr)) {
                Offscreen.LOG.warn("[Offscreen] SDL_ShowWindow failed: {}", sdlError());
            }
            focusState = (SDLVideo.SDL_GetWindowFlags(sdlPtr) & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0L;
        });
        restoreMcContext();

        if (!MainThreadExec.runOnMainThread(() -> SDLKeyboard.SDL_StartTextInput(sdlPtr))) {
            Offscreen.LOG.warn("[Offscreen] SDL_StartTextInput failed: {}", sdlError());
        }

    }

    public boolean isOpen() {
        return sdlPtr != 0L;
    }

    public void destroy() {
        if (sdlPtr == 0L) return;
        // the keyboard shadow is released on every exit path
        Driver.INSTANCE.releaseKeyShadow(this);
        MainThreadExec.runOnMainThread(() -> { SDLKeyboard.SDL_StopTextInput(sdlPtr); });
        restoreMcContext();
        renderer.awaitPresentation();
        renderer.resetGLContext();
        MainThreadExec.runOnMainThread(() -> {
            sharedContext.destroy();
            SDLVideo.SDL_DestroyWindow(sdlPtr);
        });
        sharedContext = null;
        sdlPtr = 0L;
        freeRing();
        restoreMcContext();
        Driver.INSTANCE.removeWindow(this);
        screenStack.disposeAll();
    }

    public static String sdlError() {
        return SDLError.SDL_GetError();
    }

    @SneakyThrows
    static void restoreMcContext() {
        Display.getDrawable()
            .makeCurrent();
    }

    public HostedStack stack() {
        return screenStack;
    }

    public void tryRenderFrame() {
        if (!isOpen() || destroyIfEmptied()) return;
        renderer.setFocused(focusState);
        if (!renderer.isTimeToRender()) return;

        try {
            renderFrame();
        } finally {
            restoreMcContext();
        }
    }

    private void renderFrame() {
        renderer.awaitPresentation();
        probeDrawableSize();

        final Minecraft mc = Minecraft.getMinecraft();

        final ScaledResolution resolution = new ScaledResolution(mc, pixelWidth, pixelHeight);
        guiWidth = resolution.getScaledWidth();
        guiHeight = resolution.getScaledHeight();

        if (!renderer.ensureCorrectSize(pixelWidth, pixelHeight)) return;
        if (screenStack.isEmpty()) return;

        input.run(this::drainEvents);

        if (closeRequested) {
            closeRequested = false;
            close();
            return;
        }

        // the move key: the top screen moves to the main window before it sees this frame
        if (moveRequested) {
            moveRequested = false;
            Driver.INSTANCE.mainWindow.moveFromWindow(this);
            return;
        }

        renderer.beginFrame(pixelWidth, pixelHeight, guiWidth, guiHeight);
        try {
            final long now = System.currentTimeMillis();
            final float partialTicks = ((MinecraftAccessor) mc).getTimer().renderPartialTicks;

            screenStack.runScoped(s -> {
                if (s.needsResize(guiWidth, guiHeight)) {
                    s.resize(guiWidth, guiHeight);
                }
                s.draw(mc, mouseX, mouseY, partialTicks, now);
            });
        } finally {
            renderer.endFrame(mc);
        }
        // a screen may have closed its own window
        if (!isOpen()) return;

        renderer.present(sdlPtr, sharedContext, pixelWidth, pixelHeight);
    }

    /** The close button: every screen closes top-down as if the player closed it, then the window goes. */
    public void close() {
        while (!screenStack.isEmpty()) closeHosted(screenStack.top());
        destroy();
    }

    /** One entry and the ones above it close as if the player closed them; a container is told it lost its view. */
    private void closeHosted(final HostedScreen<?> e) {
        if (ServerContainerMultiplexer.INSTANCE.isInWorld()) e.close(true);
        else screenStack.removeFrom(e);
    }

    /**
     * Runs {@code action} as the window's own screen runs: inside its scopes, so {@code mc.currentScreen}, lwjgl's
     * input and the display size are the window's. For code reaching a hosted screen from outside it, such as
     * commands, keybinds or links. Client thread only; false when the window shows nothing.
     */
    public boolean runInScope(final Consumer<HostUI> action) {
        if (!isOpen() || screenStack.isEmpty()) return false;
        screenStack.runScoped(s -> action.accept(s.ui()));
        return true;
    }

    /** A window whose last screen closed goes, as the main window shows the game again. */
    private boolean destroyIfEmptied() {
        if (!screenStack.wasEmptied()) return false;
        destroy();
        return true;
    }

    /** Whether the window has keyboard focus. */
    public boolean isFocused() {
        return focusState;
    }

    public void capture(final long eventPtr) {
        MemoryUtil.memCopy(eventPtr, ring.address(ringTail), SDL_Event.SIZEOF);
        ringTail = (ringTail + 1) % RING_CAPACITY;
        if (ringTail == ringHead) {
            ringHead = (ringHead + 1) % RING_CAPACITY;
        }
    }

    private void freeRing() {
        ringHead = 0;
        ringTail = 0;
        ring.free();
    }

    /** Records the window's SDL events into its lwjgl input, as lwjgl3ify records the main window's. */
    private void drainEvents() {
        while (ringHead != ringTail) {
            final long evAddr = ring.address(ringHead);
            ringHead = (ringHead + 1) % RING_CAPACITY;
            final var event = SDL_Event.create(evAddr);

            switch (SDL_CommonEvent.ntype(evAddr)) {
                case SDLEvents.SDL_EVENT_MOUSE_MOTION -> {
                    // lwjgl's pixel, y up; the GUI position follows from it as in GuiScreen.handleMouseInput
                    final var motion = event.motion();
                    final int pixelX = (int) motion.x(), pixelY = pixelHeight - 1 - (int) motion.y();
                    input.pointerMoved(pixelX, pixelY);
                    mouseX = pixelX * guiWidth / pixelWidth;
                    mouseY = guiHeight - pixelY * guiHeight / pixelHeight - 1;
                }
                case SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN, SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP -> input.buttonChanged(
                    Mouse.sdlToLwjglMouseButton(
                        event.button()
                            .button()),
                    event.button()
                        .down());
                case SDLEvents.SDL_EVENT_MOUSE_WHEEL -> input.wheelScrolled(
                    event.wheel()
                        .y());
                case SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED -> closeRequested = true;
                // SDL releases the held keys itself; a drag would never see its button come up
                case SDLEvents.SDL_EVENT_WINDOW_FOCUS_LOST -> input.releaseButtons();
                case SDLEvents.SDL_EVENT_KEY_DOWN, SDLEvents.SDL_EVENT_KEY_UP -> {
                    final var key = event.key();
                    if (key.repeat() && !key.down()) break;
                    final int sdlKeyCode = key.key();
                    final int sdlScanCode = key.scancode();
                    final int lwjglKey = KeyCodes.sdlKeycodeToLwjgl(sdlKeyCode);
                    // Use sdlKeyCode directly for char (respects layout, avoids scancode->keycode roundtrip
                    // which can be off by one if struct layout mismatches). PlanNH does the same
                    // scancode->keycode via SDL_GetKeyFromScancode, but we keep both for comparison
                    // and prefer sdlKeyCode when it is printable.
                    final int rawKeyCode = SDLKeyboard.SDL_GetKeyFromScancode(sdlScanCode, key.mod(), false);
                    char c = Character.MIN_VALUE;
                    int charSource;
                    // Prefer sdlKeyCode if it is printable, else fall back to raw
                    if (sdlKeyCode >= SDLKeycode.SDLK_SPACE && sdlKeyCode <= SDLKeycode.SDLK_TILDE) {
                        charSource = sdlKeyCode;
                    } else if (rawKeyCode >= SDLKeycode.SDLK_SPACE && rawKeyCode <= SDLKeycode.SDLK_TILDE) {
                        charSource = rawKeyCode;
                    } else {
                        charSource = -1;
                    }
                    if (charSource != -1) {
                        c = (char) charSource;
                        if ((key.mod() & SDLKeycode.SDL_KMOD_CTRL) != 0) {
                            c = (char) (sdlKeyCode & 0x1f);
                        }
                        if (rawKeyCode != sdlKeyCode && rawKeyCode >= 32
                            && rawKeyCode <= 126
                            && sdlKeyCode >= 32
                            && sdlKeyCode <= 126) {
                            Offscreen.LOG.debug(
                                "[Offscreen] keycode mismatch scancode {} -> raw {} ('{}') vs sdlKey {} ('{}') lwjgl {}",
                                sdlScanCode,
                                rawKeyCode,
                                (char) rawKeyCode,
                                sdlKeyCode,
                                (char) sdlKeyCode,
                                lwjglKey);
                        }
                    }
                    final int scanKey = KeyCodes.sdlScancodeToLwjgl(sdlScanCode);
                    if (Driver.INSTANCE.mainWindow.isMoveKey(scanKey)) {
                        moveRequested |= key.down() && !key.repeat();
                        break;
                    }
                    final InputEvents.KeyAction action = !key.down() ? InputEvents.KeyAction.RELEASED
                        : key.repeat() ? InputEvents.KeyAction.REPEATED : InputEvents.KeyAction.PRESSED;
                    final Keyboard.KeyState state = !key.down() ? Keyboard.KeyState.RELEASE
                        : key.repeat() ? Keyboard.KeyState.REPEAT : Keyboard.KeyState.PRESS;
                    input.keyChanged(
                        new InputEvents.KeyEvent(
                            lwjglKey,
                            scanKey,
                            sdlKeyCode,
                            sdlScanCode,
                            rawKeyCode,
                            action,
                            key.mod(),
                            0L),
                        new Keyboard.KeyEvent(scanKey, lwjglKey, c, state, System.nanoTime()));

                    shadowKey(sdlScanCode, key.down());
                }
                case SDLEvents.SDL_EVENT_TEXT_INPUT -> {
                    final String text = event.text()
                        .textString();
                    if (text != null) input.textEntered(text);
                }
                default -> {}
            }
        }
    }

    /** Hosted key state goes only into the shadow this window owns, never into the array SDL owns. */
    private void shadowKey(final int scancode, final boolean down) {
        final ByteBuffer keys = Keyboard.sdlKeyPressedArray;
        if (!Driver.INSTANCE.ownsKeyShadow(this) || keys == null) return;
        if (scancode > 0 && scancode < keys.limit()) keys.put(scancode, (byte) (down ? 1 : 0));
    }

    private final IntBuffer wPtr = ByteBuffer.allocateDirect(4)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer();
    private final IntBuffer hPtr = ByteBuffer.allocateDirect(4)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer();

    private void probeDrawableSize() {
        if (!MainThreadExec.runOnMainThread(() -> SDLVideo.SDL_GetWindowSizeInPixels(sdlPtr, wPtr, hPtr))) {
            return;
        }
        pixelWidth = wPtr.get(0);
        pixelHeight = hPtr.get(0);
        restoreMcContext();
    }

    /** Replaces what the window shows by {@code ui}; the screens it showed close as if the player closed them. */
    public void setUI(HostUI ui) {
        if (!screenStack.isEmpty()) closeHosted(screenStack.get(0));
        screenStack.pushUI(ui);
    }

    public void push(HostUI ui) {
        screenStack.pushUI(ui);
    }

    /** Closes the top screen as if the player closed it. The last screen is not popped. */
    public boolean pop() {
        if (screenStack.size() <= 1) return false;
        closeHosted(screenStack.top());
        return true;
    }

    public HostUI getCurrentUI() {
        if (screenStack.isEmpty()) return null;
        return screenStack.top()
            .ui();
    }

    public int getScreenDepth() {
        return screenStack.size();
    }

    public void resize(int width, int height) {
        MainThreadExec.runOnMainThread(() -> { SDLVideo.SDL_SetWindowSize(sdlPtr, width, height); });
        probeDrawableSize();
        if (!screenStack.isEmpty()) {
            screenStack.top()
                .requestResize();
        }
        restoreMcContext();
    }

    public void setTitle(String title) {
        MainThreadExec.runOnMainThread(() -> { SDLVideo.SDL_SetWindowTitle(sdlPtr, title); });
        restoreMcContext();
    }

    public void setClearColor(final float r, final float g, final float b, final float a) {
        renderer.setClearColor(r, g, b, a);
    }

    /** Packed {@code 0xAARRGGBB} variant of {@link #setClearColor(float, float, float, float)}. */
    public void setClearColor(final int argb) {
        renderer.setClearColor(argb);
    }

    public void setBackground(final @Nullable Runnable background) {
        renderer.setBackground(background);
    }

    /**
     * The client tick, as {@code Minecraft.runTick} runs the main window's screen: input, then {@code updateScreen}.
     * Input waits for the tick as in the main window, so a screen polling {@code Mouse.next()} while drawing (vanilla
     * lists scroll this way) gets the events that arrive between ticks.
     */
    public void update() {
        if (!isOpen() || destroyIfEmptied() || screenStack.isEmpty()) return;
        // lwjgl3ify pumps SDL at the end of the frame, so this tick sees the window's events as the main one sees its
        input.run(this::drainEvents);
        screenStack.runScoped(s -> {
            if (s.receivesInput()) input.deliverListenerEvents();
            else input.discardListenerEvents();
            s.handleInput();
            s.update();
        });
    }
}
