package com.sbancuz.offscreen.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.gtnewhorizons.angelica.sdlgpu.SDLGPURenderBackend;
import com.gtnewhorizons.angelica.sdlgpu.resource.ResourceManager;

// Early: the backend is instantiated during display creation, before late mixins apply.
@Mixin(value = SDLGPURenderBackend.class, remap = false)
public interface SDLGPURenderBackendAccessor {

    @Accessor("resourceManager")
    ResourceManager offscreen$getResourceManager();
}
