package com.sbancuz.offscreen.mixins.early;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

import io.netty.util.concurrent.GenericFutureListener;

/** Routes the client play connection's outbound and inbound packets through the multiplexer. */
@Mixin(NetworkManager.class)
public abstract class NetworkManagerMixin {

    @Shadow
    private INetHandler netHandler;

    /** Follow-ups (a probe after a world interaction) leave right behind the packet. */
    @WrapMethod(method = "scheduleOutboundPacket")
    private void offscreen$routeOutbound(final Packet packet, final GenericFutureListener[] listeners,
        final Operation<Void> original) {
        if (netHandler instanceof NetHandlerPlayClient)
            ServerContainerMultiplexer.INSTANCE.onOutboundPacket(packet, () -> original.call(packet, listeners));
        else original.call(packet, listeners);
    }

    @WrapOperation(
        method = "processReceivedPackets",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Packet;processPacket(Lnet/minecraft/network/INetHandler;)V"))
    private void offscreen$routeInbound(final Packet packet, final INetHandler handler,
        final Operation<Void> original) {
        // only the play connection: login and status packets pass
        if (handler instanceof NetHandlerPlayClient)
            ServerContainerMultiplexer.INSTANCE.onInboundPacket(packet, () -> original.call(packet, handler));
        else original.call(packet, handler);
    }
}
