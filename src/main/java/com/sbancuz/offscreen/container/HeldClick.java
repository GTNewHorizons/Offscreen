package com.sbancuz.offscreen.container;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.sbancuz.offscreen.mixins.early.GuiContainerAccessor;

/**
 * A mouse press made on a gated view, kept while the view waits for its container to become the server's open
 * one. It is given to the view afterwards only if a press where the pointer is by then would mean the same: the
 * same slot with the same stack (or the same spot off the slots), the same cursor stack, the same modifier keys.
 * Stack comparisons include item, count, damage and NBT.
 */
public final class HeldClick {

    /** How far the pointer may have moved off the slots, in GUI pixels, for the press to mean the same. */
    private static final int POINTER_DRIFT = 2;
    private static final int NO_SLOT = -1;

    final ScreenView view;
    private final int x, y, button;
    private final int slotNumber;
    private final @Nullable ItemStack under;
    private final @Nullable ItemStack cursor;
    private final boolean shift, ctrl;

    /** Its switch succeeded: it is queued once its view receives input. */
    boolean armed;
    /** Its button went up while it waited. */
    private boolean released;

    HeldClick(final ScreenView view, final int x, final int y, final int button) {
        this.view = view;
        this.x = x;
        this.y = y;
        this.button = button;

        final Slot slot = slotAt(view.screen(), x, y);
        this.slotNumber = slot != null ? slot.slotNumber : NO_SLOT;
        this.under = slot != null ? ItemStack.copyItemStack(slot.getStack()) : null;
        this.cursor = ItemStack.copyItemStack(cursorStack());
        this.shift = GuiScreen.isShiftKeyDown();
        this.ctrl = GuiScreen.isCtrlKeyDown();
    }

    public int button() {
        return button;
    }

    public boolean released() {
        return released;
    }

    void onButtonReleased(final int b) {
        if (b == button) released = true;
    }

    /**
     * Whether a press at the pointer's GUI position on {@code s}, which replaced the pressed screen, means the same.
     */
    boolean meansTheSameAt(@Nullable final GuiScreen s, final int pointerX, final int pointerY) {
        final Slot slot = slotAt(s, pointerX, pointerY);
        if (slot == null) {
            if (slotNumber != NO_SLOT) return false;
            if (Math.abs(pointerX - x) > POINTER_DRIFT || Math.abs(pointerY - y) > POINTER_DRIFT) return false;
        } else if (slot.slotNumber != slotNumber || !ItemStack.areItemStacksEqual(slot.getStack(), under)) {
            return false;
        }

        if (!ItemStack.areItemStacksEqual(cursorStack(), cursor)) return false;
        return GuiScreen.isShiftKeyDown() == shift && GuiScreen.isCtrlKeyDown() == ctrl;
    }

    private @Nullable Slot slotAt(@Nullable final GuiScreen s, final int guiX, final int guiY) {
        if (s instanceof GuiContainerAccessor gc && gc.offscreen$getSlotAtPosition(guiX, guiY) instanceof Slot slot) {
            return slot;
        }
        return null;
    }

    private @Nullable ItemStack cursorStack() {
        return Minecraft.getMinecraft().thePlayer.inventory.getItemStack();
    }
}
