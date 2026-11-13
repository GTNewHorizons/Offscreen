package com.sbancuz.offscreen.container;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.sbancuz.offscreen.mixins.early.GuiContainerAccessor;

/**
 * The press on a gated view that requested its activation, held until the view delivers
 * again. It is replayed only if it still means what it meant when it was made: the
 * same stack under it (or no slot at all), the same cursor stack, the same shift and control.
 * Stacks are compared by item, count and damage.
 */
public final class HeldClick {

    final ScreenView view;
    private final int x, y, button;

    private final boolean onSlot;
    private final @Nullable ItemStack under;
    private final @Nullable ItemStack cursor;
    private final boolean shift, ctrl;

    /** Its transfer succeeded: it is delivered once its view delivers. */
    boolean armed;
    /** Its button went up while it waited. */
    private boolean released;

    HeldClick(final ScreenView view, final int x, final int y, final int button) {
        this.view = view;
        this.x = x;
        this.y = y;
        this.button = button;

        final Slot slot = slotAt(view.screen());
        this.onSlot = slot != null;
        if (slot != null) this.under = ItemStack.copyItemStack(slot.getStack());
        else this.under = null;
        this.cursor = ItemStack.copyItemStack(cursorStack());
        this.shift = GuiScreen.isShiftKeyDown();
        this.ctrl = GuiScreen.isCtrlKeyDown();
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
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

    /** Whether a click at the same position on {@code s} would mean the same now. */
    boolean meansTheSameOn(@Nullable final GuiScreen s) {
        final Slot slot = slotAt(s);
        if ((slot != null) != onSlot) return false;
        if (slot != null && !sameStack(slot.getStack(), under)) return false;
        if (!sameStack(cursorStack(), cursor)) return false;
        return GuiScreen.isShiftKeyDown() == shift && GuiScreen.isCtrlKeyDown() == ctrl;
    }

    private @Nullable Slot slotAt(@Nullable final GuiScreen s) {
        if (s instanceof GuiContainerAccessor gc && gc.offscreen$getSlotAtPosition(x, y) instanceof Slot slot) {
            return slot;
        }
        return null;
    }

    private @Nullable ItemStack cursorStack() {
        return Minecraft.getMinecraft().thePlayer.inventory.getItemStack();
    }

    private boolean sameStack(@Nullable final ItemStack a, @Nullable final ItemStack b) {
        if (a == null || b == null) return a == b;
        return a.getItem() == b.getItem() && a.stackSize == b.stackSize && a.getItemDamage() == b.getItemDamage();
    }
}
