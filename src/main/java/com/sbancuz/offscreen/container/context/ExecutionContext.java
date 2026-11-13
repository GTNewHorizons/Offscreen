package com.sbancuz.offscreen.container.context;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C11PacketEnchantItem;
import net.minecraft.network.play.client.C14PacketTabComplete;
import net.minecraft.network.play.client.C17PacketCustomPayload;

import com.sbancuz.offscreen.container.ContainerAdapter.PayloadEffect;
import com.sbancuz.offscreen.container.ScreenView;
import com.sbancuz.offscreen.container.ServerContainer;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.container.ServerProbes;
import com.sbancuz.offscreen.container.reopen.OpeningInteraction;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;

/**
 * Who the running code acts for. Each overridable method is a question a hook asks; most defer
 * to the enclosing context. What an outbound packet may do follows from the answers.
 */
public abstract class ExecutionContext {

    protected final ServerContainerMultiplexer multiplexer;
    /** The enclosing frame, set by {@link ContextStack} when the run starts. */
    ExecutionContext enclosing;

    protected ExecutionContext(final ServerContainerMultiplexer multiplexer) {
        this.multiplexer = multiplexer;
    }

    /** Code here acts for the view of the server's open container. */
    public boolean actsForActiveView() {
        return enclosing.actsForActiveView();
    }

    /** A click for the idle active container's window may be sent from here. */
    boolean mayClick() {
        return actsForActiveView();
    }

    /** Packets sent from here are the multiplexer's own and pass unchecked. */
    boolean isMultiplexer() {
        return false;
    }

    /** Nothing sent from here reaches the server. */
    boolean blocksOutbound() {
        return enclosing.blocksOutbound();
    }

    boolean onMainThread() {
        return true;
    }

    /** What a mod payload no adapter knows does, when sent from here. */
    PayloadEffect effectOfUnknownPayload() {
        return enclosing.effectOfUnknownPayload();
    }

    /** A packet that acts on the open container, or a mod payload, was sent from here. */
    void noteServerDependentSend() {}

    /** {@code displayGuiScreen} was called from here. */
    public void displayScreen(@Nullable final GuiScreen s, final Consumer<GuiScreen> vanilla) {
        enclosing.displayScreen(s, vanilla);
    }

    /** {@code closeScreen} was called from here. {@code packet == false}: closeScreenNoPacket. */
    public void closeScreen(final boolean packet) {
        enclosing.closeScreen(packet);
    }

    /** Runs a screen change now, or after the run where the GUI must not see it happen (hosted). */
    public void runAfterScope(final Runnable r) {
        r.run();
    }

    /** A handler changed the open container silently: check what is open once it has returned. */
    public void requestReconcile() {
        enclosing.requestReconcile();
    }

    /** The view code here runs for. */
    public ScreenView view() {
        return enclosing.view();
    }

    // ================================================================== outbound packets and clicks

    /** What to do with an outbound packet sent from here: {@code send}, nothing, or send and a follow-up. */
    public final Runnable routeOutbound(final Packet p, final Runnable send) {
        if (blocksOutbound()) return ServerProbes.NOTHING;
        if (p instanceof C14PacketTabComplete) multiplexer.probes()
            .recordTabComplete(isMultiplexer());
        if (isMultiplexer()) return send;

        // acts on the open container: the active view's only
        if (p instanceof C0EPacketClickWindow c) return routeClick(c.func_149548_c(), send);
        if (p instanceof C11PacketEnchantItem c) return routeClick(c.func_149539_c(), send);
        if (p instanceof C0DPacketCloseWindow) return routeCloseWindow(send);

        if (isWorldUse(p)) return routeWorldUse(p, send);
        if (p instanceof C17PacketCustomPayload || p instanceof FMLProxyPacket) return routePayload(p, send);
        return send;
    }

    /** Whether a click or C11 for window {@code w} may be sent from here. */
    public final boolean allowsClick(final int w) {
        if (isMultiplexer()) return true;
        return multiplexer.isIdle() && multiplexer.activeContainer().windowId == w && mayClick();
    }

    private Runnable routeClick(final int w, final Runnable send) {
        if (!allowsClick(w)) {
            ServerContainerMultiplexer.LOG
                .warn("[Containers] dropped click for window {} (active {})", w, multiplexer.activeContainer());
            return ServerProbes.NOTHING;
        }
        noteServerDependentSend();
        return send;
    }

    /** A C0D from an unhooked path: from the active view it counts as that view closing. */
    private Runnable routeCloseWindow(final Runnable send) {
        if (onMainThread() && multiplexer.knowsServerState() && actsForActiveView()) {
            final ScreenView v = view();
            return followedBy(send, () -> v.close(false));
        }
        ServerContainerMultiplexer.LOG.warn("[Containers] dropped C0D from an unhooked path");
        // the caller has cleared the client cursor
        if (onMainThread() && multiplexer.isIdle()) multiplexer.resyncActive();
        return ServerProbes.NOTHING;
    }

    private boolean isWorldUse(final Packet p) {
        if (p instanceof C08PacketPlayerBlockPlacement) return true;
        if (p instanceof C02PacketUseEntity u) return u.func_149565_c() == C02PacketUseEntity.Action.INTERACT;
        return p instanceof C0BPacketEntityAction a && a.func_149513_d() == 7;
    }

    /**
     * C08, C02 INTERACT, C0B action 7 from outside the multiplexer: recorded as the interaction
     * that may open a container, followed by a probe of the inventory.
     */
    private Runnable routeWorldUse(final Packet p, final Runnable send) {
        final ServerContainer active = multiplexer.activeContainer();
        if (!multiplexer.isIdle() || !(onMainThread() || active.isPlayerInventory())) {
            ServerContainerMultiplexer.LOG.warn("[Containers] dropped world interaction (active {})", active);
            return ServerProbes.NOTHING;
        }
        if (!onMainThread()) return send;

        if (!active.isPlayerInventory()) multiplexer.pauseActiveForWorldUse(); // the server closes it anyway; may drop
                                                                               // a loaded cursor
        final OpeningInteraction o = OpeningInteraction.fromPacket(p);
        return followedBy(
            send,
            () -> multiplexer.probes()
                .askIfOpen(0, o, ServerProbes.IGNORE, ServerProbes.NOTHING));
    }

    /** A mod payload, or a vanilla {@code MC|} channel message. */
    private Runnable routePayload(final Packet p, final Runnable send) {
        final PayloadEffect cls = classifyPayload(p);
        if (cls == PayloadEffect.DROP) return ServerProbes.NOTHING;
        if (cls == PayloadEffect.ACTS_ON_OPEN_CONTAINER && !actsForActiveView()) {
            ServerContainerMultiplexer.LOG.warn(
                "[Containers] dropped payload acting on the open container (active {})",
                multiplexer.activeContainer());
            return ServerProbes.NOTHING;
        }

        if (cls != PayloadEffect.NEUTRAL) noteServerDependentSend();
        if (cls == PayloadEffect.CLOSES_GUI && onMainThread())
            return followedBy(send, multiplexer::reconcileWithServer);
        return send;
    }

    private PayloadEffect classifyPayload(final Packet p) {
        if (p instanceof C17PacketCustomPayload c && c.func_149559_c()
            .startsWith("MC|")) return vanillaChannel(c.func_149559_c());
        final PayloadEffect cls = multiplexer.classifyPayload(p);
        if (cls != PayloadEffect.UNKNOWN) return cls;
        return effectOfUnknownPayload();
    }

    private PayloadEffect vanillaChannel(final String channel) {
        return switch (channel) {
            case "MC|TrSel", "MC|Beacon", "MC|ItemName" -> PayloadEffect.ACTS_ON_OPEN_CONTAINER;
            default -> PayloadEffect.NEUTRAL;
        };
    }

    private Runnable followedBy(final Runnable first, final Runnable second) {
        return () -> {
            first.run();
            second.run();
        };
    }
}
