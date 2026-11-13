package com.sbancuz.offscreen.container;

import java.util.Optional;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.sbancuz.offscreen.container.ServerContainer.State;

/**
 * Switches the server's open container from F to T: park the cursor stack, close F, reopen T,
 * settle what opened, unpark.
 * One runs at a time; the multiplexer holds it while it runs.
 */
final class ContainerSwitch {

    private final ServerContainerMultiplexer multiplexer;
    final ServerContainer from;
    /** The target; replaced by the container the server opens for it. */
    ServerContainer to;
    private final boolean keepParked;
    private final Consumer<Boolean> done;
    private int parkIndex;
    /** The parked cursor stack; null when the cursor was empty. */
    private @Nullable ItemStack parked;

    ContainerSwitch(final ServerContainerMultiplexer multiplexer, final ServerContainer to, final boolean keepParked,
        final Consumer<Boolean> done) {
        this.multiplexer = multiplexer;
        this.from = multiplexer.activeContainer();
        this.to = to;
        this.keepParked = keepParked;
        this.done = done;
    }

    /** A loaded cursor is parked in a free slot first, and the server must accept it before anything closes. */
    void start() {
        final ItemStack cursor = multiplexer.cursorStack();
        if (cursor == null) {
            releaseAndReopen();
            return;
        }

        final Slot park = multiplexer.findParkingSlot(from, cursor)
            .orElseThrow(); // checked before the switch started
        final ItemStack stack = cursor.copy();
        parkIndex = park.getSlotIndex();
        multiplexer.clickSlot(from, park.slotNumber, ok -> afterPark(ok, stack));
    }

    private void afterPark(final boolean ok, final ItemStack stack) {
        if (ok) {
            parked = stack;
            releaseAndReopen();
            return;
        }
        // the server resynced; F keeps the active container
        multiplexer.switchEnded();
        to.showHint(OverlayHint.PARK_FAILED);
        notifyDone(false);
    }

    /**
     * Closes F, replays T's opening interaction and asks whether the inventory is open, in one
     * batch. Then exactly one of three
     * things is observed: T opens, which voids the question; the inventory is open (the
     * server did not reopen T); or it is not (something opened that was not shown).
     */
    private void releaseAndReopen() {
        if (!from.isPlayerInventory()) from.adapter.closeOnServer(from, multiplexer, !from.canReopen());
        to.openedBy.replay(multiplexer::sendAsMultiplexer);
        multiplexer.probes()
            .checkOpen(0, this::landedAtPlayerInventory, multiplexer::resetToPlayerInventory);
    }

    /** The server has the inventory open. Unless T is the inventory, its reopen failed and it stays paused. */
    private void landedAtPlayerInventory() {
        multiplexer.pauseContainer(from);
        multiplexer.setActive(multiplexer.playerInventory());
        final boolean ok = to.isPlayerInventory();
        if (!ok) to.showHint(OverlayHint.NOT_REOPENED);
        multiplexer.resyncActiveThen(() -> unpark(ok));
    }

    /** The server opened T as {@code b}; a probe of its window confirms it. */
    void onTargetOpened(final ServerContainer b) {
        final ServerContainer old = to;
        b.openedBy = old.openedBy;
        to = b;
        multiplexer.pauseContainer(from);
        multiplexer.setActive(b);
        multiplexer.closeContainer(old);

        // a second open supersedes the first: only the probe of the current one counts
        multiplexer.probes()
            .checkOpen(
                b.windowId,
                () -> { if (multiplexer.activeContainer() == b) unpark(true); },
                () -> { if (multiplexer.activeContainer() == b) multiplexer.resetToPlayerInventory(); });
    }

    /** Picks the parked stack up again through the active container's slot for the same inventory index. */
    private void unpark(final boolean ok) {
        if (parked == null) {
            settle(ok);
            return;
        }

        final ServerContainer f = multiplexer.activeContainer();
        final Optional<Slot> slot = unparkSlot(f);
        if (slot.isEmpty()) {
            f.showHint(OverlayHint.HELD_ITEM_LEFT);
            settle(ok);
            return;
        }

        multiplexer.clickSlot(f, slot.get().slotNumber, accepted -> {
            if (!accepted) f.showHint(OverlayHint.HELD_ITEM_LEFT);
            settle(ok);
        });
    }

    /** The active container's slot for the parked inventory index, if it still holds the parked stack. */
    private Optional<Slot> unparkSlot(final ServerContainer f) {
        if (keepParked || multiplexer.cursorStack() != null) return Optional.empty();
        final InventoryPlayer inventory = multiplexer.mc.thePlayer.inventory;
        return f.slots()
            .stream()
            .filter(s -> s.inventory == inventory && s.getSlotIndex() == parkIndex)
            .filter(s -> ItemStack.areItemStacksEqual(s.getStack(), parked))
            .reduce((first, last) -> last);
    }

    /** The switch is over and the multiplexer is idle again. */
    private void settle(final boolean ok) {
        final ServerContainer f = multiplexer.activeContainer();
        if (!f.isPlayerInventory()) multiplexer.setState(f, State.OPEN);
        multiplexer.switchEnded();
        notifyDone(ok);
    }

    /** Ends the transaction; a target that was not reached is paused again. */
    void notifyDone(final boolean ok) {
        if (to.state == State.REOPENING) multiplexer.setState(to, State.PAUSED);
        done.accept(ok);
    }
}
