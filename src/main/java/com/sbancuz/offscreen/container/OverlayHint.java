package com.sbancuz.offscreen.container;

/** Short feedback shown over the views of a container: why a request was refused or failed. */
public enum OverlayHint {

    /** nothing was refused */
    NONE,
    /** refused silently: the multiplexer is busy, the next press will do */
    BUSY,
    PINNED("The active GUI holds items that would drop"),
    NO_PARK_SLOT("No free slot for the held item"),
    PARK_FAILED("Could not park the held item"),
    CANNOT_REOPEN("Paused: it cannot be reopened from here"),
    NOT_REOPENED("The server did not reopen it"),
    HELD_ITEM_LEFT("Held item left in the inventory"),
    REFRESH_WOULD_LOSE("Refresh would pause the active GUI for good");

    final String text;
    final boolean shown;

    OverlayHint() {
        this.text = "";
        this.shown = false;
    }

    OverlayHint(final String text) {
        this.text = text;
        this.shown = true;
    }
}
