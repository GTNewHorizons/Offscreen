package com.sbancuz.offscreen.window;

import java.nio.ByteBuffer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.sdl.SDLVideo;
import org.lwjglx.opengl.ContextGL;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.config.OffscreenConfig;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public final class Renderer {

    private int targetFps = OffscreenConfig.focusedFps;
    private int step = 1000 / targetFps;
    private long nextFrameMs = 0;

    private int framebuffer;
    private int colorTexture;
    private int depthStencilBuffer;
    private int presentationFramebuffer;
    private int presentationTexture;
    private long presentationFence;

    private int bufferWidth = -1;
    private int bufferHeight = -1;

    private int savedFbo;
    private final float[] savedClear = new float[4];
    private final ByteBuffer maskScratch = ByteBuffer.allocateDirect(4);

    private boolean savedMaskR = true;
    private boolean savedMaskG = true;
    private boolean savedMaskB = true;
    private boolean savedMaskA = true;

    private float clearR = 0.08f;
    private float clearG = 0.09f;
    private float clearB = 0.11f;
    private float clearA = 1f;

    private @Nullable Runnable background;

    public boolean isTimeToRender() {
        final long now = System.currentTimeMillis();
        if (now < nextFrameMs) return false;
        nextFrameMs = now + step;
        return true;
    }

    public void setFocused(boolean focused) {
        final int newFps = focused ? OffscreenConfig.focusedFps : OffscreenConfig.unfocusedFps;
        if (targetFps != newFps) {
            targetFps = newFps;
            step = 1000 / targetFps;
            nextFrameMs = 0;
        }
    }

    public void setClearColor(final float r, final float g, final float b, final float a) {
        clearR = r;
        clearG = g;
        clearB = b;
        clearA = a;
    }

    /** Packed {@code 0xAARRGGBB} variant of {@link #setClearColor(float, float, float, float)}. */
    public void setClearColor(final int argb) {
        setClearColor(
            ((argb >> 16) & 0xFF) / 255f,
            ((argb >> 8) & 0xFF) / 255f,
            (argb & 0xFF) / 255f,
            ((argb >>> 24) & 0xFF) / 255f);
    }

    public void setBackground(final @Nullable Runnable background) {
        this.background = background;
    }

    public void beginFrame(final int width, final int height, final int guiWidth, final int guiHeight) {
        savedFbo = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, savedClear);
        maskScratch.clear();
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, maskScratch);
        savedMaskR = maskScratch.get(0) != 0;
        savedMaskG = maskScratch.get(1) != 0;
        savedMaskB = maskScratch.get(2) != 0;
        savedMaskA = maskScratch.get(3) != 0;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL11.glViewport(0, 0, width, height);
        GL11.glClearColor(clearR, clearG, clearB, clearA);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);

        if (background != null) {
            background.run();
        }

        GL11.glColorMask(true, true, true, false);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0, guiWidth, guiHeight, 0, 1000D, 3000D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();

        GL11.glTranslatef(0f, 0f, -2000f);
    }

    public void endFrame(final Minecraft mc) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
        GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        GL11.glColorMask(savedMaskR, savedMaskG, savedMaskB, savedMaskA);
        GL11.glClearColor(savedClear[0], savedClear[1], savedClear[2], savedClear[3]);
        mc.entityRenderer.setupOverlayRendering();
    }

    public void present(long window, ContextGL context, int pixelWidth, int pixelHeight) {
        if (framebuffer == 0) return;
        long rendered = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GL11.glFlush();
        try {
            if (!SDLVideo.SDL_GL_MakeCurrent(window, context.sdlContext)) {
                Offscreen.LOG.error("[Offscreen] present MakeCurrent failed");
                return;
            }
            GL32C.glWaitSync(rendered, 0, GL32C.GL_TIMEOUT_IGNORED);
            blit(pixelWidth, pixelHeight);
            presentationFence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11.glFlush();

            if (!SDLVideo.SDL_GL_SwapWindow(window)) {
                Offscreen.LOG.warn("[Offscreen] SDL_GL_SwapWindow failed");
            }
        } finally {
            GL32C.glDeleteSync(rendered);
        }
    }

    void awaitPresentation() {
        if (presentationFence == 0L) return;
        GL32C.glWaitSync(presentationFence, 0, GL32C.GL_TIMEOUT_IGNORED);
        GL32C.glDeleteSync(presentationFence);
        presentationFence = 0L;
    }

    private void blit(int pixelWidth, int pixelHeight) {
        if (presentationFramebuffer == 0) presentationFramebuffer = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, presentationFramebuffer);
        if (presentationTexture != colorTexture) {
            // Framebuffers belong to a context. Only the color texture is shared with Minecraft.
            GL30C.glFramebufferTexture2D(
                GL30C.GL_READ_FRAMEBUFFER,
                GL30C.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D,
                colorTexture,
                0);
            presentationTexture = colorTexture;
        }
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, 0);
        final int dw = Math.max(pixelWidth, 1);
        final int dh = Math.max(pixelHeight, 1);
        GL11.glViewport(0, 0, dw, dh);
        GL32C
            .glBlitFramebuffer(0, 0, bufferWidth, bufferHeight, 0, 0, dw, dh, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            Offscreen.LOG.warn(
                "[Offscreen] present blit GL error 0x{} fbo={} src={}x{} dst={}x{}",
                Integer.toHexString(err),
                framebuffer,
                bufferWidth,
                bufferHeight,
                dw,
                dh);
            GL11.glClearColor(1f, 0f, 1f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        }
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, 0);
        GL11.glFlush();
    }

    public boolean ensureCorrectSize(int width, int height) {
        if (bufferWidth == width && bufferHeight == height && framebuffer != 0) return true;
        resetGLContext();

        bufferWidth = width;
        bufferHeight = height;

        final int fboToRestore = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        colorTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL30.GL_SRGB8_ALPHA8,
            width,
            height,
            0,
            GL12.GL_BGRA,
            GL11.GL_UNSIGNED_BYTE,
            (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            colorTexture,
            0);
        depthStencilBuffer = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthStencilBuffer);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height);
        GL30.glFramebufferRenderbuffer(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_DEPTH_STENCIL_ATTACHMENT,
            GL30.GL_RENDERBUFFER,
            depthStencilBuffer);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);

        final int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            Offscreen.LOG.error("[Offscreen] offscreen framebuffer incomplete: {}", status);
            resetGLContext();
            return false;
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboToRestore);
        return true;
    }

    void resetGLContext() {
        presentationTexture = 0;
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        if (colorTexture != 0) {
            GL11.glDeleteTextures(colorTexture);
            colorTexture = 0;
        }
        if (depthStencilBuffer != 0) {
            GL30.glDeleteRenderbuffers(depthStencilBuffer);
            depthStencilBuffer = 0;
        }
        bufferWidth = -1;
        bufferHeight = -1;
    }
}
