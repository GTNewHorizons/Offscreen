package com.sbancuz.offscreen.window;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;

/**
 * The two globals every GUI reads, {@code Minecraft.currentScreen} and
 * {@code thePlayer.openContainer}. They describe the main window; a run for another
 * screen (a hosted entry, an inbound handler, a container's close logic) has them lent and they are
 * given back when it ends. While any run has them, the main window's own screen is kept here.
 */
public final class GuiGlobals {

    private final Minecraft mc = Minecraft.getMinecraft();

    /** How many runs have the globals lent, and the real main-window screen while any does. */
    private int lent;
    private @Nullable GuiScreen mainScreen;

    /** Runs {@code body} with {@code screen} as the current screen; the open container is left alone. */
    public void runWithScreen(@Nullable final GuiScreen screen, final Runnable body) {
        callWithScreen(screen, () -> {
            body.run();
            return Boolean.TRUE;
        });
    }

    /** Runs {@code body} with both globals lent. */
    public void runWith(@Nullable final GuiScreen screen, final Container open, final Runnable body) {
        callWith(screen, open, () -> {
            body.run();
            return Boolean.TRUE;
        });
    }

    /** {@link #runWith} for a body with a result. A handler may replace the player: the new one is left alone. */
    public <T> T callWith(@Nullable final GuiScreen screen, final Container open, final Supplier<T> body) {
        final EntityClientPlayerMP player = mc.thePlayer;
        final Container savedOpen = player.openContainer;
        player.openContainer = open;
        try {
            return callWithScreen(screen, body);
        } finally {
            // the outermost run already restored the main window's container
            if (lent > 0 && mc.thePlayer == player) player.openContainer = savedOpen;
        }
    }

    private <T> T callWithScreen(@Nullable final GuiScreen screen, final Supplier<T> body) {
        final GuiScreen savedScreen = mc.currentScreen;
        if (lent++ == 0) mainScreen = savedScreen;
        mc.currentScreen = screen;

        try {
            return body.get();
        } finally {
            if (--lent == 0) giveBackToMainWindow();
            else mc.currentScreen = savedScreen;
        }
    }

    /** The outermost run ends: the globals describe the main window again. */
    private void giveBackToMainWindow() {
        mc.currentScreen = mainScreen;
        restoreMainWindowContainer();
    }

    /** The screen of the main window, also while the globals are lent. */
    public @Nullable GuiScreen mainScreen() {
        if (lent == 0) return mc.currentScreen;
        return mainScreen;
    }

    public boolean areLent() {
        return lent > 0;
    }

    /** Outside every run the open container is the main screen's container or the inventory. */
    public void restoreMainWindowContainer() {
        // a disconnect inside a handler leaves no player behind
        if (lent != 0 || mc.thePlayer == null) return;
        mc.thePlayer.openContainer = mc.thePlayer.inventoryContainer;
        if (mc.currentScreen instanceof GuiContainer gc && gc.inventorySlots != null) {
            mc.thePlayer.openContainer = gc.inventorySlots;
        }
    }

    /**
     * Runs {@code action} against the real main-window state, then lends the globals again as
     * they were. Whatever the action leaves as current screen becomes the main screen.
     */
    public void runOnMainWindowState(final Runnable action) {
        if (lent == 0) {
            action.run();
            return;
        }
        final GuiScreen screen = mc.currentScreen;
        final EntityClientPlayerMP player = mc.thePlayer;
        final Container open = player.openContainer;

        // the loan is suspended while the action runs
        final int depth = lent;
        lent = 0;
        mc.currentScreen = mainScreen;
        restoreMainWindowContainer();

        try {
            action.run();
        } finally {
            lent = depth;
            mainScreen = mc.currentScreen;
            mc.currentScreen = screen;
            if (mc.thePlayer == player) player.openContainer = open;
        }
    }
}
