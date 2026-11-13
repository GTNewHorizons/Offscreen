package com.sbancuz.offscreen.window;

import java.nio.ByteBuffer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import com.sbancuz.offscreen.Offscreen;
import com.sbancuz.offscreen.config.OffscreenConfig;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

@Lwjgl3Aware
public final class Renderer {

    private int targetFps = OffscreenConfig.focusedFps;
    private int step = 1000 / targetFps;
    private long nextFrameMs = 0;
    private int fpsCap;

    private final int colorInternalFormat;

    private int framebuffer;
    private int colorTexture;
    private int depthStencilBuffer;
    private long targetGeneration;

    private int bufferWidth = -1;
    private int bufferHeight = -1;

    private int savedFbo;

    private float clearR = 0.08f;
    private float clearG = 0.09f;
    private float clearB = 0.11f;
    private float clearA = 1f;

    private @Nullable Runnable background;

    public Renderer(final int colorInternalFormat) {
        this.colorInternalFormat = colorInternalFormat;
    }

    public boolean isTimeToRender() {
        final long now = System.currentTimeMillis();
        if (now < nextFrameMs) return false;
        nextFrameMs = now + step;
        return true;
    }

    void setFpsCap(final int cap) {
        fpsCap = Math.max(cap, 0);
    }

    public void setFocused(boolean focused) {
        int newFps = focused ? OffscreenConfig.focusedFps : OffscreenConfig.unfocusedFps;
        if (fpsCap > 0) newFps = Math.min(newFps, fpsCap);
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
        // Not glGetBooleanv(GL_COLOR_WRITEMASK): Angelica doesn't redirect it, so it crashes on SDL GPU.
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT);
        try {
            setUpTarget(width, height, guiWidth, guiHeight);
        } catch (final RuntimeException | Error e) {
            restoreMcState();
            throw e;
        }
    }

    private void setUpTarget(final int width, final int height, final int guiWidth, final int guiHeight) {
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
        restoreMcState();
        GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        mc.entityRenderer.setupOverlayRendering();
    }

    private void restoreMcState() {
        // Rebind first: the popped draw buffer applies to the bound framebuffer.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
        GL11.glPopAttrib();
    }

    boolean hasTarget() {
        return framebuffer != 0;
    }

    boolean targetMatches(final int width, final int height) {
        return framebuffer != 0 && bufferWidth == width && bufferHeight == height;
    }

    int framebuffer() {
        return framebuffer;
    }

    int colorTexture() {
        return colorTexture;
    }

    int targetWidth() {
        return bufferWidth;
    }

    int targetHeight() {
        return bufferHeight;
    }

    long targetGeneration() {
        return targetGeneration;
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
            colorInternalFormat,
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
        targetGeneration++;
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
