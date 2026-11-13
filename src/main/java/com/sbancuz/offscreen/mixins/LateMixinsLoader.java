package com.sbancuz.offscreen.mixins;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;
import com.sbancuz.offscreen.compat.ModCompat;

import cpw.mods.fml.relauncher.FMLLaunchHandler;

@LateMixin
public class LateMixinsLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.offscreen.late.json";
    }

    @Nonnull
    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> mixins = new ArrayList<>();
        if (!FMLLaunchHandler.side()
            .isClient()) {
            return mixins;
        }

        if (loadedMods.contains(ModCompat.MODULAR_UI2.modId())) {
            mixins.add("ClientScreenHandlerAccessor");
            mixins.add("GuiManagerOffscreenMixin");
            mixins.add("ModularNetworkSideInvoker");
            mixins.add("ClientScreenHandlerMixin");
            mixins.add("NetworkHandlerMixin");

            // the container multiplexer, and the render state of hosted screens
            mixins.add("Mui2ServerCloseMixin");
            mixins.add("OverlayStackAccessor");
            mixins.add("StencilAccessor");
        }

        if (loadedMods.contains(ModCompat.NEI.modId())) {
            mixins.add("BookmarkPanelAccessor");
            mixins.add("GuiDrawMixin");
            mixins.add("ItemPanelsAccessor");
            mixins.add("LayoutManagerAccessor");
            mixins.add("SearchFieldMixin");
        }
        if (loadedMods.contains(ModCompat.HODGEPODGE.modId())) {
            mixins.add("GLScissorHelperMixin");
        }
        return mixins;
    }
}
