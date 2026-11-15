package com.sbancuz.offscreen.scope;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Container;

import com.sbancuz.offscreen.window.MainWindow;

/**
 * Minecraft's GUI globals while code runs for a screen that is not the one the main window shows:
 * {@code Minecraft.currentScreen}, {@code thePlayer.openContainer} and the display size are that screen's from
 * {@link #enter} to {@link #restore}. Scopes nest, and the main window knows the innermost one, so it can tell its
 * own screen meanwhile.
 */
public final class ScreenScope implements Scope {

    /** What the globals hold. */
    private record Globals(@Nullable GuiScreen screen, @Nullable Container openContainer, int displayWidth,
        int displayHeight) {}

    private final Minecraft mc = Minecraft.getMinecraft();
    private final MainWindow mainWindow;
    /** What the globals are inside the scope; null for the main window's own. */
    private final @Nullable Globals inside;

    private @Nullable ScreenScope enclosing;
    /** What the globals were when the scope was entered, and the player whose container that was. */
    private Globals found;
    private EntityClientPlayerMP player;

    /**
     * For a screen in a window of the given pixel size: code sizing itself from Minecraft's display size
     * ({@code ScaledResolution}, scissor math, vanilla's mouse conversion) sizes itself for that window.
     */
    public ScreenScope(final MainWindow mainWindow, @Nullable final GuiScreen screen, final Container openContainer,
        final int pixelWidth, final int pixelHeight) {
        this.mainWindow = mainWindow;
        this.inside = new Globals(screen, openContainer, pixelWidth, pixelHeight);
    }

    /** For a screen at the display size the running code already has. */
    public ScreenScope(final MainWindow mainWindow, @Nullable final GuiScreen screen, final Container openContainer) {
        this(
            mainWindow,
            screen,
            openContainer,
            Minecraft.getMinecraft().displayWidth,
            Minecraft.getMinecraft().displayHeight);
    }

    /** The main window's own globals again, for code that changes its screen while another screen has them. */
    public ScreenScope(final MainWindow mainWindow) {
        this.mainWindow = mainWindow;
        this.inside = null;
    }

    @Override
    public void enter() {
        player = mc.thePlayer;
        final Container openContainer = player != null ? player.openContainer : null;
        found = new Globals(mc.currentScreen, openContainer, mc.displayWidth, mc.displayHeight);
        enclosing = mainWindow.screenScope;
        mainWindow.screenScope = this;

        if (inside != null) apply(inside);
        else if (!enteredFromMainWindow()) {
            apply(enclosing.borrower().found);
            mainWindow.syncOpenContainer();
        }
    }

    @Override
    public void restore() {
        mainWindow.screenScope = enclosing;
        if (inside == null) {
            // the globals were the main window's all along: what the code made of them stays
            if (enteredFromMainWindow()) return;
            enclosing.borrower()
                .mainWindowShows(mc.currentScreen);
        }

        apply(found);
        if (enteredFromMainWindow()) mainWindow.syncOpenContainer();
    }

    /** Whether the globals describe another screen than the main window's inside this scope. */
    public boolean describesAnotherScreen() {
        return inside != null;
    }

    /** The main window's screen while this is the innermost scope. */
    public @Nullable GuiScreen mainWindowScreen() {
        if (inside == null) return mc.currentScreen;
        return borrower().found.screen;
    }

    /** Whether the globals were the main window's own when this scope was entered. */
    private boolean enteredFromMainWindow() {
        return enclosing == null || enclosing.inside == null;
    }

    /** The scope, this one or an enclosing one, that took the globals from the main window. */
    private ScreenScope borrower() {
        if (enteredFromMainWindow()) return this;
        return enclosing.borrower();
    }

    /** The main window's screen changed while this scope has the globals: that one is put back. */
    private void mainWindowShows(@Nullable final GuiScreen screen) {
        found = new Globals(screen, found.openContainer, found.displayWidth, found.displayHeight);
    }

    private void apply(final Globals globals) {
        mc.currentScreen = globals.screen;
        mc.displayWidth = globals.displayWidth;
        mc.displayHeight = globals.displayHeight;
        // a handler may have replaced the player: the new one is left alone
        if (player != null && mc.thePlayer == player) player.openContainer = globals.openContainer;
    }
}
