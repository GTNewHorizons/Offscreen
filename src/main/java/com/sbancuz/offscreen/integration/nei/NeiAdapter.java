package com.sbancuz.offscreen.integration.nei;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.network.Packet;

import com.sbancuz.offscreen.container.ContainerAdapter;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;

/**
 * What NEI's outbound packets do on the server. NEI packets go through CodeChickenCore's
 * {@code PacketCustom}: one type byte (bit 0x80 marks a compressed body), then the body.
 * NEI GUIs are vanilla {@code GuiContainer}s, so this adapter claims no screens.
 */
public final class NeiAdapter implements ContainerAdapter {

    public static final NeiAdapter INSTANCE = new NeiAdapter();
    private static final String CHANNEL = "NEI";

    private NeiAdapter() {}

    @Override
    public boolean handles(final GuiScreen screen) {
        return false;
    }

    @Override
    public PayloadEffect classifyPayload(final Packet packet) {
        if (!(packet instanceof FMLProxyPacket fp) || !CHANNEL.equals(fp.channel())) return PayloadEffect.UNKNOWN;
        final ByteBuf buf = fp.payload();
        if (buf == null || buf.readableBytes() < 1) return PayloadEffect.UNKNOWN;
        final int base = buf.readerIndex();
        final int raw = buf.getUnsignedByte(base);
        // a compressed body is not read: the conservative class applies
        final boolean readable = (raw & 0x80) == 0;

        return switch (raw & 0x7F) {
            case 4, 11, 16, 22, 25, 27 -> PayloadEffect.ACTS_ON_OPEN_CONTAINER;
            case 5 -> slotAction(buf, base, readable);
            case 13 -> type13(buf, base, readable);
            case 21, 24 -> PayloadEffect.OPENS_GUI;
            case 23 -> inventoryGui(buf, base, readable);
            case 1, 6, 7, 8, 9, 10, 12, 14, 15, 26 -> PayloadEffect.NEUTRAL;
            default -> PayloadEffect.UNKNOWN;
        };
    }

    /** Type 5 (bool container, short slot): the cursor (-999) and container targets act on the open container. */
    private static PayloadEffect slotAction(final ByteBuf buf, final int base, final boolean readable) {
        if (!readable || buf.readableBytes() < 4) return PayloadEffect.ACTS_ON_OPEN_CONTAINER;
        if (buf.getBoolean(base + 1) || buf.getShort(base + 2) == -999) return PayloadEffect.ACTS_ON_OPEN_CONTAINER;
        return PayloadEffect.NEUTRAL;
    }

    /** Type 13: mode 2 makes the server open a GUI. */
    private static PayloadEffect type13(final ByteBuf buf, final int base, final boolean readable) {
        if (!readable || buf.readableBytes() < 2) return PayloadEffect.OPENS_GUI;
        if (buf.getByte(base + 1) == 2) return PayloadEffect.OPENS_GUI;
        return PayloadEffect.NEUTRAL;
    }

    /** Type 23: opens (true) or closes (false) the server-side inventory GUI. */
    private static PayloadEffect inventoryGui(final ByteBuf buf, final int base, final boolean readable) {
        if (!readable || buf.readableBytes() < 2) return PayloadEffect.OPENS_GUI;
        if (buf.getBoolean(base + 1)) return PayloadEffect.OPENS_GUI;
        return PayloadEffect.CLOSES_GUI;
    }
}
