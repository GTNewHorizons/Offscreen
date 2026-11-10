package com.sbancuz.offscreen.window;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.sdl.SDLVideo;
import org.lwjglx.opengl.ContextGL;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.DrawableGL;

import com.sbancuz.offscreen.Offscreen;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
final class GlPresentPath implements PresentPath {

    private ContextGL sharedContext;

    private int presentationFramebuffer;
    private long attachedGeneration = -1;
    private volatile long presentationFence;

    @Override
    public boolean prepare() {
        Display.glContextMutex.lock();
        try {
            if (!(Display.getDrawable() instanceof DrawableGL drawable)) {
                Offscreen.LOG.error(
                    "[Offscreen] unexpected drawable type {}",
                    Display.getDrawable()
                        .getClass());
                return false;
            }
            sharedContext = drawable.createSharedContext();
            return true;
        } finally {
            Display.glContextMutex.unlock();
        }
    }

    @Override
    public long windowFlags() {
        return SDLVideo.SDL_WINDOW_OPENGL;
    }

    @Override
    public int colorInternalFormat() {
        return GL30C.GL_SRGB8_ALPHA8;
    }

    @Override
    public int fpsCap() {
        return 0;
    }

    @Override
    public boolean beforeRender() {
        awaitPresentation();
        return true;
    }

    @Override
    public boolean attach(final long window) {
        if (!SDLVideo.SDL_GL_MakeCurrent(window, sharedContext.sdlContext)) {
            Offscreen.LOG.error("[Offscreen] SDL_GL_MakeCurrent failed: {}", Window.sdlError());
            return false;
        }
        SDLVideo.SDL_GL_SetSwapInterval(0);
        return true;
    }

    @Override
    public void present(final long window, final Renderer source, final int pixelWidth, final int pixelHeight) {
        if (!source.hasTarget()) return;
        long rendered = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL11.glFlush();
        try {
            if (!SDLVideo.SDL_GL_MakeCurrent(window, sharedContext.sdlContext)) {
                Offscreen.LOG.error("[Offscreen] present MakeCurrent failed");
                return;
            }
            GL32C.glWaitSync(rendered, 0, GL32C.GL_TIMEOUT_IGNORED);
            blit(source, pixelWidth, pixelHeight);
            presentationFence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11.glFlush();

            if (!SDLVideo.SDL_GL_SwapWindow(window)) {
                Offscreen.LOG.warn("[Offscreen] SDL_GL_SwapWindow failed");
            }
        } finally {
            GL32C.glDeleteSync(rendered);
        }
    }

    @Override
    public void awaitPresentation() {
        if (presentationFence == 0L) return;
        GL32C.glWaitSync(presentationFence, 0, GL32C.GL_TIMEOUT_IGNORED);
        GL32C.glDeleteSync(presentationFence);
        presentationFence = 0L;
    }

    @Override
    public void detach(final long window) {
        if (sharedContext == null) return;
        sharedContext.destroy();
        sharedContext = null;
    }

    private void blit(final Renderer source, final int pixelWidth, final int pixelHeight) {
        if (presentationFramebuffer == 0) presentationFramebuffer = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, presentationFramebuffer);
        if (attachedGeneration != source.targetGeneration()) {
            // Framebuffers belong to a context. Only the color texture is shared with Minecraft.
            GL30C.glFramebufferTexture2D(
                GL30C.GL_READ_FRAMEBUFFER,
                GL30C.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D,
                source.colorTexture(),
                0);
            attachedGeneration = source.targetGeneration();
        }
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, 0);
        final int srcW = source.targetWidth();
        final int srcH = source.targetHeight();
        final int dw = Math.max(pixelWidth, 1);
        final int dh = Math.max(pixelHeight, 1);
        GL11.glViewport(0, 0, dw, dh);
        GL32C.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, dw, dh, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            Offscreen.LOG.warn(
                "[Offscreen] present blit GL error 0x{} fbo={} src={}x{} dst={}x{}",
                Integer.toHexString(err),
                source.framebuffer(),
                srcW,
                srcH,
                dw,
                dh);
            GL11.glClearColor(1f, 0f, 1f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        }
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, 0);
        GL11.glFlush();
    }
}
