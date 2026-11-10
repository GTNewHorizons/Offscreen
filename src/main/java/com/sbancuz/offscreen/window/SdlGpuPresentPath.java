package com.sbancuz.offscreen.window;

import java.nio.IntBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLGPU;
import org.lwjgl.sdl.SDLSurface;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;
import org.lwjgl.sdl.SDL_GPUBlitInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import com.gtnewhorizons.angelica.sdlgpu.device.Device;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;
import com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread;
import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.mixins.early.SDLGPURenderBackendAccessor;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;
import me.eigenraven.lwjgl3ify.client.MainThreadExec;

@Lwjgl3Aware
final class SdlGpuPresentPath implements PresentPath {

    private static final int[] NON_BLOCKING_MODES = { SDLGPU.SDL_GPU_PRESENTMODE_MAILBOX,
        SDLGPU.SDL_GPU_PRESENTMODE_IMMEDIATE };

    private static final long SKIP_PRESENT_FLAGS = SDLVideo.SDL_WINDOW_HIDDEN | SDLVideo.SDL_WINDOW_MINIMIZED
        | SDLVideo.SDL_WINDOW_OCCLUDED;

    private long device;
    private ResourceManager resources;
    private boolean fenceQueryInverted;
    private int fpsCap;
    private volatile boolean claimed;

    private final Semaphore inFlight = new Semaphore(1);
    private volatile boolean dropped;
    private volatile boolean warned;

    // Render thread only.
    private boolean staged;
    private long stagedWindow;
    private int stagedTexture;
    private int stagedWidth;
    private int stagedHeight;

    @Override
    public boolean prepare() {
        if (!(BackendManager.RENDER_BACKEND instanceof SDLGPURenderBackendAccessor accessor)) {
            Offscreen.LOG.error("[Offscreen] SDLGPURenderBackendAccessor was not applied; cannot present");
            return false;
        }
        final Device gpu = SDLGPUGate.device();
        device = gpu.getDevice();
        if (device == 0L) {
            Offscreen.LOG.error("[Offscreen] Angelica's SDL GPU backend has no device");
            return false;
        }
        resources = accessor.offscreen$getResourceManager();
        fenceQueryInverted = gpu.isFenceQueryInverted();
        return true;
    }

    @Override
    public long windowFlags() {
        return Platform.get() == Platform.MACOSX ? SDLVideo.SDL_WINDOW_METAL : 0L;
    }

    @Override
    public int colorInternalFormat() {
        // Same as Minecraft's framebuffer, so Angelica's existing pipelines are reused.
        return GL11.GL_RGBA8;
    }

    @Override
    public boolean attach(final long window) {
        final Callable<String> claim = () -> {
            if (!SDLGPU.SDL_ClaimWindowForGPUDevice(device, window)) return SDLError.SDL_GetError();
            claimed = true;
            choosePresentMode(window);
            return null;
        };
        final String error = MainThreadExec.runOnMainThread(claim);
        if (error != null) {
            Offscreen.LOG.error("[Offscreen] SDL_ClaimWindowForGPUDevice failed: {}", error);
            return false;
        }
        return true;
    }

    private void choosePresentMode(final long window) {
        for (final int mode : NON_BLOCKING_MODES) {
            if (!SDLGPU.SDL_WindowSupportsGPUPresentMode(device, window, mode)) continue;
            if (SDLGPU.SDL_SetGPUSwapchainParameters(device, window, SDLGPU.SDL_GPU_SWAPCHAINCOMPOSITION_SDR, mode)) {
                Offscreen.LOG.info("[Offscreen] presenting with {}", presentModeName(mode));
                return;
            }
        }
        fpsCap = refreshRate(window);
        Offscreen.LOG.info("[Offscreen] presenting with VSYNC, fps capped at {}", fpsCap > 0 ? fpsCap : "config");
    }

    private static int refreshRate(final long window) {
        final int display = SDLVideo.SDL_GetDisplayForWindow(window);
        if (display == 0) return 0;
        final SDL_DisplayMode mode = SDLVideo.SDL_GetCurrentDisplayMode(display);
        if (mode == null) return 0;
        return Math.max((int) mode.refresh_rate(), 0);
    }

    private static String presentModeName(final int mode) {
        return mode == SDLGPU.SDL_GPU_PRESENTMODE_MAILBOX ? "MAILBOX" : "IMMEDIATE";
    }

    @Override
    public int fpsCap() {
        return fpsCap;
    }

    @Override
    public boolean beforeRender() {
        if (inFlight.availablePermits() == 0) return false;
        if (dropped) {
            dropped = false;
            return false;
        }
        return true;
    }

    @Override
    public void present(final long window, final Renderer source, final int pixelWidth, final int pixelHeight) {
        if (!source.hasTarget()) return;
        if (!staged) FrameBoundary.afterSwap(this::dispatch);
        staged = true;
        stagedWindow = window;
        stagedTexture = source.colorTexture();
        stagedWidth = source.targetWidth();
        stagedHeight = source.targetHeight();
    }

    private void dispatch() {
        if (!staged) return;
        staged = false;
        if (!claimed) return;

        // Not cached: Angelica may recreate the texture behind the same GL id.
        final long texture = resources.getTextureHandle(stagedTexture);
        if (texture == 0L) return;
        if (!inFlight.tryAcquire()) return;

        final long window = stagedWindow;
        final int width = stagedWidth;
        final int height = stagedHeight;
        boolean queued = false;
        try {
            MainStartOnFirstThread.instance()
                .execute(() -> {
                    try {
                        blitToSwapchain(window, texture, width, height);
                    } catch (final Throwable t) {
                        Offscreen.LOG.error("[Offscreen] present failed", t);
                    } finally {
                        inFlight.release();
                    }
                });
            queued = true;
        } finally {
            if (!queued) inFlight.release();
        }
    }

    private void blitToSwapchain(final long window, final long source, final int srcWidth, final int srcHeight) {
        if (!claimed || SDLGPUGate.device()
            .getDevice() != device) return;
        if ((SDLVideo.SDL_GetWindowFlags(window) & SKIP_PRESENT_FLAGS) != 0L) return;

        final long commandBuffer = SDLGPU.SDL_AcquireGPUCommandBuffer(device);
        if (commandBuffer == 0L) {
            warnOnce("SDL_AcquireGPUCommandBuffer failed: {}", SDLError.SDL_GetError());
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            final PointerBuffer swapchain = stack.pointers(0L);
            final IntBuffer width = stack.ints(0);
            final IntBuffer height = stack.ints(0);
            final boolean ok = fenceQueryInverted
                ? SDLGPU.SDL_WaitAndAcquireGPUSwapchainTexture(commandBuffer, window, swapchain, width, height)
                : SDLGPU.SDL_AcquireGPUSwapchainTexture(commandBuffer, window, swapchain, width, height);
            final long target = swapchain.get(0);
            if (!ok || target == 0L) {
                if (!ok) warnOnce("SDL_AcquireGPUSwapchainTexture failed: {}", SDLError.SDL_GetError());
                // Only allowed because nothing was acquired.
                SDLGPU.SDL_CancelGPUCommandBuffer(commandBuffer);
                dropped = true;
                return;
            }

            final SDL_GPUBlitInfo blit = SDL_GPUBlitInfo.calloc(stack)
                .source(
                    r -> r.texture(source)
                        .w(srcWidth)
                        .h(srcHeight))
                .destination(
                    r -> r.texture(target)
                        .w(width.get(0))
                        .h(height.get(0)))
                .load_op(SDLGPU.SDL_GPU_LOADOP_DONT_CARE)
                .filter(SDLGPU.SDL_GPU_FILTER_LINEAR)
                // GL-convention target: row 0 is the bottom.
                .flip_mode(SDLSurface.SDL_FLIP_VERTICAL);
            SDLGPU.SDL_BlitGPUTexture(commandBuffer, blit);
        }

        if (!SDLGPU.SDL_SubmitGPUCommandBuffer(commandBuffer)) {
            warnOnce("SDL_SubmitGPUCommandBuffer failed: {}", SDLError.SDL_GetError());
        }
    }

    private void warnOnce(final String message, final String error) {
        if (warned) return;
        warned = true;
        Offscreen.LOG.warn("[Offscreen] " + message, error);
    }

    @Override
    public void awaitPresentation() {
        staged = false;
        // The blit runs on the main thread; waiting for it there would deadlock.
        if (MainThreadExec.isMainThread()) return;
        inFlight.acquireUninterruptibly();
        inFlight.release();
    }

    @Override
    public void detach(final long window) {
        if (!claimed) return;
        claimed = false;
        // Destroying the device already released every claimed window.
        if (SDLGPUGate.device()
            .getDevice() != device) return;
        SDLGPU.SDL_ReleaseWindowFromGPUDevice(device, window);
    }
}
