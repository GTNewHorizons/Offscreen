package com.sbancuz.offscreen.mixins.late;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import codechicken.nei.BookmarkPanel;
import codechicken.nei.ItemPanel;
import codechicken.nei.ItemPanels;

@Mixin(value = ItemPanels.class, remap = false)
public interface ItemPanelsAccessor {

    @Accessor("itemPanel")
    @Mutable
    @Final
    static ItemPanel getItemPanel() {
        throw new AssertionError();
    }

    @Accessor("itemPanel")
    @Mutable
    @Final
    static void setItemPanel(final ItemPanel panel) {
        throw new AssertionError();
    }

    @Accessor("bookmarkPanel")
    @Mutable
    @Final
    static BookmarkPanel getBookmarkPanel() {
        throw new AssertionError();
    }

    @Accessor("bookmarkPanel")
    @Mutable
    @Final
    static void setBookmarkPanel(final BookmarkPanel panel) {
        throw new AssertionError();
    }
}
