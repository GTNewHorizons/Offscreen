package com.sbancuz.offscreen.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.sbancuz.offscreen.integration.nei.NeiScope;

import codechicken.nei.SearchField;

@Mixin(value = SearchField.class, remap = false)
public class SearchFieldMixin {

    @Inject(method = "onTextChange", at = @At("HEAD"), cancellable = true)
    private void offscreen$onTextChange(String oldText, CallbackInfo ci) {
        SearchField self = (SearchField) (Object) this;
        if (NeiScope.isOffscreenSearchField(self)) {
            ci.cancel();
            NeiScope.onOffscreenSearchChanged(self);
        }
    }
}
