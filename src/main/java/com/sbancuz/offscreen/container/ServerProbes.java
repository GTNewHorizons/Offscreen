package com.sbancuz.offscreen.container;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.client.C14PacketTabComplete;

import com.sbancuz.offscreen.config.ContainerProtocolConfig;
import com.sbancuz.offscreen.container.reopen.OpeningInteraction;

/**
 * Packets that ask the server what it has open, and their answers. A probe is a click the
 * server answers with S32 only while that window is its open container; a barrier is a C14 the
 * server answers in order, so its answer proves that older probes went unanswered. Every wait
 * ends in an answer, in the proof that it never comes, or in a reset. Callbacks of probes sent
 * before the running procedure was superseded are dropped.
 */
public final class ServerProbes {

    public static final ArrivalCause NO_CAUSE = new ArrivalCause() {};
    public static final Consumer<Boolean> IGNORE = ok -> {};
    public static final Runnable NOTHING = () -> {};
    private static final String BARRIER_TEXT = "~offscreen:barrier";

    private record PendingProbe(int windowId, short tid, long seq, int epoch, ArrivalCause cause,
        Consumer<Boolean> onAnswer, Runnable onNever) {}

    private final ServerContainerMultiplexer multiplexer;
    private final List<PendingProbe> pending = new ArrayList<>();
    /** Rejected probes waiting for the S2F(-1) that ends the server's resync. */
    private final ArrayDeque<PendingProbe> resync = new ArrayDeque<>();
    /**
     * Outstanding C14 in send order: the sequence number of a multiplexer barrier, -1 for the user's.
     */
    private final Queue<Long> c14 = new ConcurrentLinkedQueue<>();
    /** The server does not answer the barrier. */
    private boolean barrierUnsupported;
    private long seq;
    private short muxTid = 30000;
    private int epoch;

    ServerProbes(final ServerContainerMultiplexer multiplexer) {
        this.multiplexer = multiplexer;
    }

    /** The running procedure is superseded: answers to older probes are consumed but trigger nothing. */
    void abandonPending() {
        epoch++;
    }

    /** Whether an unanswered probe can be observed; without a barrier the watchdog stands in. */
    boolean barrierSupported() {
        return !barrierUnsupported && ContainerProtocolConfig.barrier;
    }

    short nextOwnTransactionId() {
        return ++muxTid;
    }

    // ================================================================== sending (6)

    /** Sends a click the server answers only while its window is open there, and waits for the answer. */
    void sendTracked(final C0EPacketClickWindow click, final ArrivalCause cause, final Consumer<Boolean> onAnswer,
        final Runnable onNever) {
        pending.add(
            new PendingProbe(click.func_149548_c(), click.func_149547_f(), ++seq, epoch, cause, onAnswer, onNever));
        multiplexer.sendAsMultiplexer(click);
    }

    /**
     * Asks whether window {@code w} is open: a click outside the GUI with an empty cursor. {@code onAnswer}
     * gets the S32 flag; {@code onNever} runs when a later answer proves that this one will not come.
     */
    public void askIfOpen(final int w, final ArrivalCause cause, final Consumer<Boolean> onAnswer,
        final Runnable onNever) {
        sendTracked(
            new C0EPacketClickWindow(w, -999, 0, 4, null, multiplexer.nextTransactionId(w)),
            cause,
            onAnswer,
            onNever);
    }

    void askIfOpen(final int w, final Consumer<Boolean> onAnswer, final Runnable onNever) {
        askIfOpen(w, NO_CAUSE, onAnswer, onNever);
    }

    /** Its answer proves that every older probe still pending was not answered. */
    void sendBarrier() {
        if (barrierSupported()) multiplexer.sendAsMultiplexer(new C14PacketTabComplete(BARRIER_TEXT));
    }

    /** A C14 is sent; a multiplexer barrier gets a sequence number, the user's does not. */
    public void recordTabComplete(final boolean fromMultiplexer) {
        if (fromMultiplexer) c14.add(++seq);
        else c14.add(-1L);
    }

    /** {@code ifOpen} when the probe is answered, {@code ifNotOpen} when the barrier's answer comes first. */
    void checkOpen(final int w, final Runnable ifOpen, final Runnable ifNotOpen) {
        askIfOpen(w, ok -> ifOpen.run(), ifNotOpen);
        sendBarrier();
    }

    /** Refreshes window {@code w}: a click the server must reject, answered with every slot and the cursor. */
    void resync(final int w, final Runnable done, final Runnable onNever) {
        if (!ContainerProtocolConfig.refill) {
            done.run();
            return;
        }

        // the server rejects a click with a stack it does not see there, and resends the window
        final ItemStack wrong = new ItemStack(Blocks.stone);
        sendTracked(
            new C0EPacketClickWindow(w, -999, 0, 4, wrong, multiplexer.nextTransactionId(w)),
            NO_CAUSE,
            ok -> done.run(),
            onNever);
    }

    /**
     * Tested at join: a barrier, then a probe of the inventory. If the probe's answer comes while
     * the barrier is still pending, the server does not answer C14 and barriers are disabled.
     */
    void testBarrierSupport(final Runnable done, final Runnable onNever) {
        sendBarrier();
        final long barrier = seq;

        // a barrier still recorded when the probe is answered was never answered
        askIfOpen(0, ok -> {
            barrierUnsupported = c14.remove(barrier);
            if (barrierUnsupported) ServerContainerMultiplexer.LOG
                .warn("[Containers] the server does not answer the barrier; disabled for this session");
            done.run();
        }, onNever);
    }

    // ================================================================== answers

    /** An S32. Returns false when it answers no probe of ours. */
    public boolean onConfirmTransaction(final int w, final short tid, final boolean accepted) {
        for (final PendingProbe e : pending) {
            if (e.windowId != w || e.tid != tid) continue;
            settle(e, accepted);
            return true;
        }
        return false;
    }

    /** The answer to {@code e} proves every older pending probe unanswered, then runs its callback. */
    private void settle(final PendingProbe e, final boolean accepted) {
        neverAnswered(e.seq);
        pending.remove(e);
        if (accepted) {
            deliver(e, true);
            return;
        }
        // a rejected click: the callback runs once the S30 and S2F(-1) that follow are in
        multiplexer.sendAsMultiplexer(new C0FPacketConfirmTransaction(e.windowId, e.tid, true));
        resync.add(e);
    }

    /** An S3A. Returns true when it answers a barrier of ours; then it is consumed. */
    public boolean onTabCompleteAnswer() {
        final Long head = c14.poll();
        if (head == null || head < 0) return false;
        neverAnswered(head);
        return true;
    }

    /** The S2F(-1) that ends a resync: the oldest rejected probe's callback runs. */
    public void onResyncEnded() {
        if (!resync.isEmpty()) deliver(resync.poll(), false);
    }

    private void deliver(final PendingProbe e, final boolean accepted) {
        if (e.epoch == epoch) e.onAnswer.accept(accepted);
    }

    /** Every probe sent before {@code limit} that is still pending will never be answered. */
    private void neverAnswered(final long limit) {
        multiplexer.noteProgress();
        final List<PendingProbe> gone = new ArrayList<>();
        while (!pending.isEmpty() && pending.get(0).seq < limit) gone.add(pending.remove(0));
        for (final PendingProbe e : gone) if (e.epoch == epoch) e.onNever.run();
    }

    // ================================================================== what caused a GUI the server opens

    /** The interaction of the oldest unanswered probe sent after a world interaction; NONE without one. */
    OpeningInteraction pendingInteraction() {
        for (final PendingProbe e : pending) if (e.cause instanceof OpeningInteraction o) return o;
        return OpeningInteraction.NONE;
    }

    /** The oldest unanswered placement that can still take an arrival; the main window without one. */
    ArrivalTarget pendingArrivalTarget() {
        for (final PendingProbe e : pending) if (e.cause instanceof ArrivalTarget p && p.canPlace()) return p;
        return multiplexer.mainWindow;
    }

    /** A container opened: probes of other windows can no longer be answered and cause nothing. */
    void dropAllExceptWindow(final int w) {
        pending.removeIf(e -> e.windowId != w);
    }
}
