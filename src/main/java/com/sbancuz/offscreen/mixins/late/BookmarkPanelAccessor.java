package com.sbancuz.offscreen.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import codechicken.nei.BookmarkPanel;
import codechicken.nei.bookmark.BookmarkStorage;

@Mixin(value = BookmarkPanel.class, remap = false)
public interface BookmarkPanelAccessor {

    @Accessor("storage")
    BookmarkStorage getStorage();

    @Accessor("storage")
    void setStorage(BookmarkStorage storage);
}
