package com.sbancuz.offscreen.window;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;

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
import org.lwjglx.opengl.Display;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.core.Driver;
import com.sbancuz.offscreen.mixins.early.MinecraftAccessor;
import com.sbancuz.offscreen.window.input.FrameEvent;
import com.sbancuz.offscreen.window.input.InputRouter;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
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
    private final PresentPath presentPath = PresentPath.create();

    @Getter
    private final String title;

    // Must come after presentPath.
    private final Renderer renderer = new Renderer(presentPath.colorInternalFormat());
    private final HostedStack screenStack = new HostedStack();

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

    private final FrameEvent frameEvent = new FrameEvent();

    public Window(final String title) {
        this.title = title;

        if (!MainThreadExec.runOnMainThread(() -> SDLInit.SDL_Init(SDLInit.SDL_INIT_VIDEO))) {
            Offscreen.LOG.error("[Offscreen] SDL_Init failed: {}", sdlError());
            abandonCreation();
            return;
        }

        if (!presentPath.prepare()) {
            abandonCreation();
            return;
        }

        restoreMcContext();

        final long windowFlags = presentPath.windowFlags() | SDLVideo.SDL_WINDOW_RESIZABLE | SDLVideo.SDL_WINDOW_HIDDEN;
        sdlPtr = MainThreadExec
            .runOnMainThread(() -> SDLVideo.SDL_CreateWindow(title, DEFAULT_WIDTH, DEFAULT_HEIGHT, windowFlags));
        restoreMcContext();

        if (sdlPtr == 0L) {
            Offscreen.LOG.error("[Offscreen] SDL_CreateWindow failed: {}", sdlError());
            abandonCreation();
            return;
        }

        id = SDLVideo.SDL_GetWindowID(sdlPtr);

        if (!presentPath.attach(sdlPtr)) {
            abandonCreation();
            return;
        }
        renderer.setFpsCap(presentPath.fpsCap());
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

    private void abandonCreation() {
        final long window = sdlPtr;
        MainThreadExec.runOnMainThread(() -> {
            presentPath.detach(window);
            if (window != 0L) SDLVideo.SDL_DestroyWindow(window);
        });
        sdlPtr = 0L;
        freeRing();
        restoreMcContext();
    }

    public void destroy() {
        if (sdlPtr == 0L) return;
        MainThreadExec.runOnMainThread(() -> { SDLKeyboard.SDL_StopTextInput(sdlPtr); });
        restoreMcContext();
        presentPath.awaitPresentation();
        renderer.resetGLContext();
        MainThreadExec.runOnMainThread(() -> {
            presentPath.detach(sdlPtr);
            SDLVideo.SDL_DestroyWindow(sdlPtr);
        });
        sdlPtr = 0L;
        freeRing();
        restoreMcContext();
        Driver.INSTANCE.removeWindow(this);
        disposeStack();
    }

    void awaitPresentation() {
        if (isOpen()) presentPath.awaitPresentation();
    }

    public static String sdlError() {
        return SDLError.SDL_GetError();
    }

    @SneakyThrows
    static void restoreMcContext() {
        Display.getDrawable()
            .makeCurrent();
    }

    public void tryRenderFrame() {
        if (!isOpen()) return;
        renderer.setFocused(focusState);
        if (!renderer.isTimeToRender()) return;

        try {
            renderFrame();
        } finally {
            restoreMcContext();
        }
    }

    private void renderFrame() {
        if (!presentPath.beforeRender()) return;
        probeDrawableSize();

        final Minecraft mc = Minecraft.getMinecraft();

        final ScaledResolution resolution = new ScaledResolution(mc, pixelWidth, pixelHeight);
        guiWidth = resolution.getScaledWidth();
        guiHeight = resolution.getScaledHeight();

        if (!renderer.targetMatches(pixelWidth, pixelHeight)) presentPath.awaitPresentation();
        if (!renderer.ensureCorrectSize(pixelWidth, pixelHeight)) return;
        if (screenStack.isEmpty()) return;

        drainFrameEvent();

        if (frameEvent.closeRequested) {
            destroy();
            return;
        }

        renderer.beginFrame(pixelWidth, pixelHeight, guiWidth, guiHeight);
        try {
            final long now = System.currentTimeMillis();
            final float partialTicks = ((MinecraftAccessor) mc).getTimer().renderPartialTicks;

            screenStack.runScoped(frameEvent, pixelWidth, pixelHeight, s -> {
                if (s.needsResize(guiWidth, guiHeight)) {
                    s.resize(guiWidth, guiHeight);
                }
                s.dispatchInput(frameEvent, partialTicks);
                s.draw(mc, frameEvent.mouseX, frameEvent.mouseY, partialTicks, now);
            });
        } finally {
            renderer.endFrame(mc);
        }

        presentPath.present(sdlPtr, renderer, pixelWidth, pixelHeight);
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

    private void drainFrameEvent() {
        frameEvent.keyCount = 0;
        frameEvent.text.setLength(0);
        final float scaleX = (float) guiWidth / pixelWidth;
        final float scaleY = (float) guiHeight / pixelHeight;
        while (ringHead != ringTail) {
            final long evAddr = ring.address(ringHead);
            ringHead = (ringHead + 1) % RING_CAPACITY;
            final var event = SDL_Event.create(evAddr);

            switch (SDL_CommonEvent.ntype(evAddr)) {
                case SDLEvents.SDL_EVENT_MOUSE_MOTION -> {
                    final var motion = event.motion();
                    frameEvent.mouseX = Math.round(motion.x() * scaleX);
                    frameEvent.mouseY = Math.round(motion.y() * scaleY);
                }
                case SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN -> {
                    frameEvent.pressButton = InputRouter.sdlToGuiButton(
                        event.button()
                            .button());
                }
                case SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP -> {
                    frameEvent.releaseButton = InputRouter.sdlToGuiButton(
                        event.button()
                            .button());
                }
                case SDLEvents.SDL_EVENT_MOUSE_WHEEL -> {
                    frameEvent.wheelDelta += event.wheel()
                        .y();
                }
                case SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED -> frameEvent.closeRequested = true;
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
                        // Debug aid for a->s shift: log mismatches between scancode-derived and keycode
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
                    if (frameEvent.keyCount < frameEvent.keys.length) {
                        frameEvent.keys[frameEvent.keyCount++].set(lwjglKey, c, key.down())
                            .setSdl(sdlKeyCode, sdlScanCode, key.mod());
                    }

                    if (focusState) {
                        final ByteBuffer keyArray = Keyboard.sdlKeyPressedArray;
                        if (keyArray != null && sdlScanCode > 0 && sdlScanCode < keyArray.limit()) {
                            keyArray.put(sdlScanCode, (byte) (key.down() ? 1 : 0));
                        }
                    }
                }
                case SDLEvents.SDL_EVENT_TEXT_INPUT -> {
                    final String text = event.text()
                        .textString();
                    if (text != null) frameEvent.text.append(text);
                }
                default -> {}
            }
        }
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

    public void setUI(HostUI ui) {
        if (ui.getGuiScreen() instanceof GuiInventory) {
            Offscreen.LOG.error("[Offscreen] Inventories are not supported in a separate window");

            destroy();
            return;
        }
        disposeStack();
        screenStack.push(new HostedScreen<>(ui));
    }

    public void push(HostUI ui) {
        screenStack.push(new HostedScreen<>(ui));
    }

    public boolean pop() {
        if (screenStack.size() <= 1) return false;
        final HostedScreen<?> popped = screenStack.pop();
        popped.dispose();
        if (!screenStack.isEmpty()) {
            screenStack.top()
                .requestResize();
        }
        return true;
    }

    public HostUI getCurrentUI() {
        if (screenStack.isEmpty()) return null;
        return screenStack.top()
            .screen();
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

    public void update() {
        if (!isOpen() || screenStack.isEmpty()) return;
        screenStack.runScoped(HostedScreen::update);
    }

    /** Disposes every hosted UI top-down. Owners guard against reentry. */
    private void disposeStack() {
        while (!screenStack.isEmpty()) {
            screenStack.pop()
                .dispose();
        }
    }
}
