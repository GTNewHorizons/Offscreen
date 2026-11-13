package com.sbancuz.offscreen.window;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
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
import org.lwjglx.opengl.ContextGL;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.DrawableGL;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.api.UIRegistry;
import com.sbancuz.offscreen.container.ServerContainer;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.container.ServerProbes;
import com.sbancuz.offscreen.core.Driver;
import com.sbancuz.offscreen.integration.vanilla.VanillaUI;
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
    private ContextGL sharedContext;

    @Getter
    private final String title;

    private final Renderer renderer = new Renderer();
    private final HostedStack screenStack = new HostedStack();
    /** The stack may have become empty; checked on the next frame or tick. */
    private boolean maybeEmpty;

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
        // the keyboard shadow is released on every exit path.
        Driver.INSTANCE.releaseKeyShadow(this);
        MainThreadExec.runOnMainThread(() -> { SDLKeyboard.SDL_StopTextInput(sdlPtr); });

        // the GL context and the SDL window go on the main thread, after the last frame is presented
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
        disposeStack();
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

    // ================================================================== the stack as the container multiplexer sees it

    /** API or window close of one entry: its view closes as if the player closed it. */
    public void closeHosted(final HostedScreen<?> e) {
        if (!screenStack.contains(e)) return;
        if (ServerContainerMultiplexer.INSTANCE.isInWorld()) e.close(true);
        else removeFrom(e);
    }

    /** SDL close request: every view closes as if the player closed it, then the window is destroyed. */
    public void close() {
        while (!screenStack.isEmpty()) closeHosted(screenStack.top());
        if (isOpen()) destroy();
    }

    /** Pushes the entry for {@code s}; every screen has a host UI, vanilla's at least. */
    public HostedScreen<?> pushScreen(final GuiScreen s) {
        final HostedScreen<?> e = new HostedScreen<>(this, hostFor(s));
        screenStack.pushEntry(e);
        return e;
    }

    private HostUI hostFor(final GuiScreen s) {
        if (UIRegistry.resolve(s) instanceof HostUI ui) return ui;
        return new VanillaUI(s);
    }

    /** Pushes {@code s} and lays it out now: initGui must run before the packet handler writes the window id. */
    public HostedScreen<?> pushNow(final GuiScreen s) {
        final HostedScreen<?> e = pushScreen(s);
        layoutNow(e);
        return e;
    }

    /**
     * The new container screen {@code e} replaces the container entry it was pushed over, and that entry's children.
     */
    public void replace(final HostedScreen<?> over, final HostedScreen<?> e) {
        if (!screenStack.contains(over) || !screenStack.contains(e)) return;
        screenStack.remove(e);
        removeFrom(over);
        screenStack.pushEntry(e);
    }

    /**
     * A GUI the server opened is pushed over {@code entry}; once known it replaces {@code entry} if that shows a
     * container too.
     */
    public Runnable placeArrivalOver(final HostedScreen<?> entry, final GuiScreen s) {
        final ServerContainerMultiplexer multiplexer = ServerContainerMultiplexer.INSTANCE;
        if (!isOpen()) return multiplexer.mainWindow.place(s);
        final HostedScreen<?> e = pushNow(s);
        if (multiplexer.containerOf(entry.screen()) == null) return ServerProbes.NOTHING;
        return () -> replace(entry, e);
    }

    /**
     * The GUI of {@code entry} displayed {@code incoming}: back to a screen of the stack, a child, or a replacement.
     */
    void applyDisplayRequest(final HostedScreen<?> entry, final GuiScreen incoming) {
        final HostedScreen<?> existing = screenStack.findEntry(incoming);
        if (existing != null) {
            // back to a screen of this stack: pop to it
            final int above = screenStack.indexOf(existing) + 1;
            if (above < screenStack.size()) removeFrom(screenStack.get(above));
            return;
        }
        final ServerContainer old = entry.gatingContainer();
        final ServerContainer now = ServerContainerMultiplexer.INSTANCE.containerOf(incoming);
        if (!screenStack.contains(entry) || old == null || now == null || now == old) {
            pushScreen(incoming); // a child of the entry
            return;
        }
        // the GUI replaced itself by one of another container (Baubles back to the inventory)
        removeFrom(screenStack.boundBaseOf(entry));
        pushScreen(incoming);
        ServerContainerMultiplexer.INSTANCE.viewClosed(old, false);
    }

    /** Removes {@code e} and everything above it. Bindings that lose their view this way are left to the caller. */
    public void removeFrom(final HostedScreen<?> e) {
        while (screenStack.contains(e)) {
            final HostedScreen<?> top = screenStack.top();
            screenStack.remove(top);
            // disposed inside its scope: onGuiClosed runs, the container's close logic runs at most once
            screenStack.runScopedOn(
                top,
                false,
                s -> s.screen()
                    .onGuiClosed());
            top.dispose();
        }

        // an empty hosted window is destroyed on its next frame
        if (screenStack.isEmpty()) maybeEmpty = true;
        else screenStack.top()
            .requestResize();
    }

    private boolean destroyIfEmpty() {
        if (!maybeEmpty) return false;
        maybeEmpty = false;
        if (!screenStack.isEmpty()) return false;
        destroy();
        return true;
    }

    /** Lays an entry out immediately so that initGui runs before any packet reaches it. */
    private void layoutNow(final HostedScreen<?> e) {
        probeDrawableSize();
        final ScaledResolution resolution = new ScaledResolution(Minecraft.getMinecraft(), pixelWidth, pixelHeight);
        final int gw = resolution.getScaledWidth();
        final int gh = resolution.getScaledHeight();
        screenStack.runScopedOn(e, false, s -> s.resize(gw, gh));
    }

    public void tryRenderFrame() {
        if (!isOpen() || destroyIfEmpty()) return;
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

        drainFrameEvent();

        if (frameEvent.closeRequested) {
            frameEvent.closeRequested = false;
            close();
            return;
        }

        renderer.beginFrame(pixelWidth, pixelHeight, guiWidth, guiHeight);
        try {
            final long now = System.currentTimeMillis();
            final float partialTicks = ((MinecraftAccessor) mc).getTimer().renderPartialTicks;
            final HostedScreen<?> top = screenStack.top();

            screenStack.runScoped(frameEvent, pixelWidth, pixelHeight, top, true, s -> {
                if (s.needsResize(guiWidth, guiHeight)) {
                    s.resize(guiWidth, guiHeight);
                }
                // the entry gates its own input
                s.deliverInput(frameEvent, partialTicks);
                s.draw(mc, frameEvent.mouseX, frameEvent.mouseY, partialTicks, now);
                ServerContainerMultiplexer.INSTANCE.overlay.draw(s, guiWidth, guiHeight);
            });
        } finally {
            renderer.endFrame(mc);
        }
        if (!isOpen()) return;

        renderer.present(sdlPtr, sharedContext, pixelWidth, pixelHeight);
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

                    shadowKey(sdlScanCode, key.down());
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

    /** Replaces the window's content with {@code ui}; existing views close through 8.3. */
    public void setUI(HostUI ui) {
        final HostedScreen<?> e = new HostedScreen<>(this, ui);
        while (!screenStack.isEmpty()) {
            final int before = screenStack.size();
            closeHosted(screenStack.get(0));
            if (screenStack.size() >= before) break;
        }
        if (!isOpen()) return;
        screenStack.pushEntry(e);
    }

    public void push(HostUI ui) {
        screenStack.pushEntry(new HostedScreen<>(this, ui));
    }

    /** Closes the top view through 8.3. The last view is not popped. */
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

    public void update() {
        if (!isOpen() || destroyIfEmpty() || screenStack.isEmpty()) return;
        final HostedScreen<?> top = screenStack.top();
        // no updateScreen for a view that is gated
        if (!top.receivesUpdates()) return;
        screenStack.runScoped(HostedScreen::update);
    }

    /** Disposes every hosted UI top-down without telling the multiplexer. */
    private void disposeStack() {
        screenStack.disposeAll();
    }
}
