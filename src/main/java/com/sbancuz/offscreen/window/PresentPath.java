package com.sbancuz.offscreen.window;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;

interface PresentPath {

    static PresentPath create() {
        return SDLGPUGate.isActive() ? new SdlGpuPresentPath() : new GlPresentPath();
    }

    boolean prepare();

    long windowFlags();

    int colorInternalFormat();

    boolean attach(long window);

    /** 0 for no cap. */
    int fpsCap();

    /** Returns false to skip this frame. */
    boolean beforeRender();

    void present(long window, Renderer source, int pixelWidth, int pixelHeight);

    void awaitPresentation();

    /** Main thread, before {@code SDL_DestroyWindow}. {@code window} is 0 if creation failed before it existed. */
    void detach(long window);
}
