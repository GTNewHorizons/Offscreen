package com.sbancuz.offscreen.container;

/** Short feedback shown over the views of a container: why a request was refused or failed. */
public enum OverlayHint {

    /** nothing was refused */
    NONE,
    /** refused silently: the multiplexer is busy, the next press will do */
    BUSY,
    PINNED("offscreen.container.hint.pinned"),
    NO_PARK_SLOT("offscreen.container.hint.no_park_slot"),
    PARK_FAILED("offscreen.container.hint.park_failed"),
    CANNOT_REOPEN("offscreen.container.hint.cannot_reopen"),
    NOT_REOPENED("offscreen.container.hint.not_reopened"),
    HELD_ITEM_LEFT("offscreen.container.hint.held_item_left"),
    REFRESH_WOULD_LOSE("offscreen.container.hint.refresh_would_lose");

    final String translationKey;
    final boolean shown;

    OverlayHint() {
        this.translationKey = "";
        this.shown = false;
    }

    OverlayHint(final String translationKey) {
        this.translationKey = translationKey;
        this.shown = true;
    }
}
