package com.sbancuz.offscreen.integration.mui2;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0DPacketCloseWindow;

import com.cleanroommc.modularui.ModularUI;
import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.network.IPacket;
import com.cleanroommc.modularui.network.ModularNetwork;
import com.cleanroommc.modularui.network.NetworkHandler;
import com.cleanroommc.modularui.network.packets.CloseAllGuiPacket;
import com.cleanroommc.modularui.network.packets.CloseGuiPacket;
import com.cleanroommc.modularui.network.packets.OpenGuiPacket;
import com.cleanroommc.modularui.network.packets.PacketSyncHandler;
import com.cleanroommc.modularui.network.packets.ReopenGuiPacket;
import com.cleanroommc.modularui.network.packets.SyncConfig;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widgets.slot.ModularCraftingSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.sbancuz.offscreen.container.ContainerAdapter;
import com.sbancuz.offscreen.container.ServerContainer;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.mixins.late.ClientScreenHandlerAccessor;
import com.sbancuz.offscreen.mixins.late.ModularNetworkSideInvoker;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;

/** ModularUI2 adapter. Loaded only when ModularUI2 is present. */
public final class Mui2Adapter implements ContainerAdapter {

    public static final Mui2Adapter INSTANCE = new Mui2Adapter();

    /** The IPacket currently inside {@code NetworkHandler.sendToServer} on this thread. */
    private static final ThreadLocal<IPacket> SENDING = new ThreadLocal<>();
    /** Network ids of synced containers, recorded by {@code GuiManager.openFromClient}. */
    private static final Map<Container, Integer> NIDS = Collections.synchronizedMap(new WeakHashMap<>());
    private static int arrivingNid = -1;

    private Mui2Adapter() {}

    // ------------------------------------------------------------------ hooks from the late mixins

    public static void sending(@Nullable final IPacket p) {
        SENDING.set(p);
    }

    public static void onOpenFromClient(final int networkId) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (networkId < 0 && arrivingNid >= 0
            && mc.thePlayer != null
            && mc.thePlayer.openContainer instanceof ModularContainer c) {
            NIDS.put(c, arrivingNid);
        }
        arrivingNid = networkId;
    }

    /**
     * Replaces {@code ModularNetwork.CLIENT.closeAll()} in {@code ClientScreenHandler.onGuiChanged}:
     * in a world, a session is closed only together with its container.
     */
    public static void containCloseAll() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) ModularNetwork.CLIENT.closeAll();
    }

    // ------------------------------------------------------------------ ContainerAdapter

    @Override
    public boolean handles(final GuiScreen screen) {
        final ModularScreen ms = screen instanceof IMuiScreen mui ? mui.getScreen() : null;
        try {
            return ms != null && ms.getContainer() != null
                && !ms.getContainer()
                    .isClientOnly();
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /** The ModularUI2 network id of a container, -1 for none. */
    private static int nid(final ServerContainer b) {
        return NIDS.getOrDefault(b.clientContainer, -1);
    }

    /** Player, phantom and crafting-result slots keep or own nothing; the rest falls under the generic rules. */
    @Override
    public SlotOnClose slotOnClose(final Container clientContainer, final Slot slot) {
        if (!(slot instanceof ModularSlot ms)) return ContainerAdapter.super.slotOnClose(clientContainer, slot);
        if (ModularSlot.isPlayerSlot(slot) || ms.isPhantom() || slot instanceof ModularCraftingSlot) {
            return SlotOnClose.KEEPS_ITEMS;
        }
        return ContainerAdapter.super.slotOnClose(clientContainer, slot);
    }

    /** The close hook of ModularUI2 is {@code onModularContainerClosed}. */
    @Override
    public boolean hasCloseLogic(final Container clientContainer) {
        if (!(clientContainer instanceof ModularContainer))
            return ContainerAdapter.super.hasCloseLogic(clientContainer);
        return declaresMethod(clientContainer.getClass(), ModularContainer.class, "onModularContainerClosed");
    }

    @Override
    public PayloadEffect classifyPayload(final Packet packet) {
        final IPacket p = SENDING.get();
        if (p == null || !(packet instanceof FMLProxyPacket fp) || !ModularUI.ID.equals(fp.channel())) {
            return PayloadEffect.UNKNOWN;
        }
        final ServerContainerMultiplexer multiplexer = ServerContainerMultiplexer.INSTANCE;
        final int active = nid(multiplexer.activeContainer());
        final boolean forActive = active >= 0 && packetNid(p) == active;
        if (p instanceof OpenGuiPacket) return PayloadEffect.OPENS_GUI;

        // the client never needs to reopen anything but the active container itself (a no-op)
        if (p instanceof ReopenGuiPacket) return forActive ? PayloadEffect.NEUTRAL : PayloadEffect.DROP;
        // CloseGui closes whatever the server has open: only for the active container itself, or while that is harmless
        if (p instanceof CloseGuiPacket && forActive && multiplexer.fromActiveView()) return PayloadEffect.CLOSES_GUI;
        if (p instanceof CloseGuiPacket)
            return multiplexer.idleAtQuietPlayerInventory() ? PayloadEffect.NEUTRAL : PayloadEffect.DROP;
        if (p instanceof CloseAllGuiPacket)
            return multiplexer.hasOpenContainerOf(this) ? PayloadEffect.DROP : PayloadEffect.NEUTRAL;
        if (p instanceof PacketSyncHandler || p instanceof SyncConfig) return PayloadEffect.NEUTRAL;
        return PayloadEffect.UNKNOWN;
    }

    /** The private {@code networkId} of ReopenGuiPacket and CloseGuiPacket, or -1. */
    private static int packetNid(final IPacket p) {
        try {
            final Field f = p.getClass()
                .getDeclaredField("networkId");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    @Override
    public void closeOnServer(final ServerContainer b, final ServerContainerMultiplexer multiplexer,
        final boolean finalClose) {
        final int nid = nid(b);
        final Runnable dispose = () -> NetworkHandler.sendToServer(new CloseGuiPacket(nid, true));
        if (nid >= 0 && finalClose) {
            // instead of C0D: closes the open container and disposes the server session
            multiplexer.runAsMultiplexer(dispose);
            return;
        }
        multiplexer.sendAsMultiplexer(new C0DPacketCloseWindow(b.windowId));
        // (b) holds after the C0D if nothing is left to drop: a replay opens a new session, so dispose this one
        if (nid >= 0 && multiplexer.playerInventoryDropsNothing()) multiplexer.runAsMultiplexer(dispose);
    }

    @Override
    public void onContainerClosed(final ServerContainer b) {
        final int nid = nid(b);
        NIDS.remove(b.clientContainer);
        final Object client = ModularNetwork.CLIENT;
        if (nid >= 0 && client instanceof ModularNetworkSideInvoker side) side.offscreen$deactivate(nid, true);
    }

    @Override
    public void detachFromMainWindow(final GuiScreen screen) {
        if (!(screen instanceof IMuiScreen mui)) return;
        if (ClientScreenHandlerAccessor.getCurrentScreenMui() == mui.getScreen()) {
            ClientScreenHandlerAccessor.setCurrentScreenMui(null);
        }
        if (ClientScreenHandlerAccessor.getLastMui() == mui) ClientScreenHandlerAccessor.setLastMui(null);
        ClientScreenHandlerAccessor.getMuiStack()
            .remove(mui);
    }

    /** The server keeps sessions by player UUID across a respawn: close them all once that is harmless. */
    @Override
    public void afterRespawn(final ServerContainerMultiplexer multiplexer) {
        if (multiplexer.hasOpenContainerOf(this) || !multiplexer.idleAtQuietPlayerInventory()) return;
        multiplexer.runAsMultiplexer(() -> NetworkHandler.sendToServer(new CloseAllGuiPacket()));
        ModularNetwork.CLIENT.closeAll(Minecraft.getMinecraft().thePlayer, false);
    }
}
