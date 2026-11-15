package com.sbancuz.offscreen.window;

import java.util.ArrayDeque;
import java.util.Deque;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;

/**
 * The globals every GUI reads: {@code Minecraft.currentScreen}, {@code thePlayer.openContainer} and Minecraft's
 * display size. They describe the main window; a run for another screen (a hosted entry, an inbound handler, a
 * container's close logic) has them lent and gives them back when it ends. While any run has them, the main
 * window's own screen is kept here.
 */
public final class GuiGlobals {

    /** What the globals were when a run took them. */
    private record Loan(EntityClientPlayerMP player, Container open, @Nullable GuiScreen screen, int width,
        int height) {}

    private final Minecraft mc = Minecraft.getMinecraft();

    /** The runs that have the globals, innermost first. */
    private Deque<Loan> loans = new ArrayDeque<>();
    /** The loans taken away by {@link #suspendLoans}, each under the globals of the run that was innermost. */
    private final Deque<Deque<Loan>> suspended = new ArrayDeque<>();
    /** The real main-window screen while any run has the globals. */
    private @Nullable GuiScreen mainScreen;

    /** Lends the screen and the open container to a run until {@link #giveBack}. */
    public void lend(@Nullable final GuiScreen screen, final Container open) {
        lend(screen, open, mc.displayWidth, mc.displayHeight);
    }

    /**
     * {@link #lend} for a hosted window's screen: the display size is the window's pixel size too, so code sizing
     * itself from it ({@code ScaledResolution}, scissor math, vanilla's mouse conversion) sizes itself for that
     * window.
     */
    public void lend(@Nullable final GuiScreen screen, final Container open, final int pixelWidth,
        final int pixelHeight) {
        final Loan loan = currentGlobals();
        if (loans.isEmpty()) mainScreen = loan.screen;
        loans.push(loan);
        apply(screen, open, pixelWidth, pixelHeight, loan.player);
    }

    /** The innermost run ends. A handler may have replaced the player: the new one is left alone. */
    public void giveBack() {
        final Loan loan = loans.pop();
        apply(loan.screen, loan.open, loan.width, loan.height, loan.player);
        // the outermost run ends: the globals describe the main window again
        if (!loans.isEmpty()) return;
        mc.currentScreen = mainScreen;
        mainScreen = null;
        restoreMainWindowContainer();
    }

    /**
     * Suspends every loan, so that code changing the main window's screen runs against the real main-window state.
     * Returns false when nothing was lent; otherwise {@link #resumeLoans} lends the globals again as they were.
     */
    public boolean suspendLoans() {
        if (loans.isEmpty()) return false;
        final Loan outermost = loans.peekLast();
        loans.push(currentGlobals());
        suspended.push(loans);
        loans = new ArrayDeque<>();

        mc.currentScreen = mainScreen;
        mc.displayWidth = outermost.width;
        mc.displayHeight = outermost.height;
        restoreMainWindowContainer();
        return true;
    }

    /** Whatever the main window shows now is its screen; the innermost run gets the globals back. */
    public void resumeLoans() {
        loans = suspended.pop();
        mainScreen = mc.currentScreen;
        final Loan innermost = loans.pop();
        apply(innermost.screen, innermost.open, innermost.width, innermost.height, innermost.player);
    }

    private Loan currentGlobals() {
        final EntityClientPlayerMP player = mc.thePlayer;
        return new Loan(player, player.openContainer, mc.currentScreen, mc.displayWidth, mc.displayHeight);
    }

    private void apply(@Nullable final GuiScreen screen, final Container open, final int width, final int height,
        final EntityClientPlayerMP player) {
        mc.currentScreen = screen;
        mc.displayWidth = width;
        mc.displayHeight = height;
        if (mc.thePlayer == player) player.openContainer = open;
    }

    /** The screen of the main window, also while the globals are lent. */
    public @Nullable GuiScreen mainScreen() {
        if (loans.isEmpty()) return mc.currentScreen;
        return mainScreen;
    }

    public boolean areLent() {
        return !loans.isEmpty();
    }

    /** Outside every run the open container is the main screen's container or the inventory. */
    public void restoreMainWindowContainer() {
        // a disconnect inside a handler leaves no player behind
        if (areLent() || mc.thePlayer == null) return;
        mc.thePlayer.openContainer = mc.thePlayer.inventoryContainer;
        if (mc.currentScreen instanceof GuiContainer gc && gc.inventorySlots != null) {
            mc.thePlayer.openContainer = gc.inventorySlots;
        }
    }
}
