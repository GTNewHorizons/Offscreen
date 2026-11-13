package com.sbancuz.offscreen.container.reopen;

import java.util.function.Consumer;

import net.minecraft.network.Packet;

/** {@link OpeningInteraction#NONE} and {@link OpeningInteraction#INVENTORY}: nothing to replay, reachable or not. */
final class NoInteraction extends OpeningInteraction {

    private final boolean reopenable;
    private final String name;

    NoInteraction(final boolean reopenable, final String name) {
        this.reopenable = reopenable;
        this.name = name;
    }

    @Override
    public boolean canEverReopen() {
        return reopenable;
    }

    @Override
    public boolean canReplayNow() {
        return reopenable;
    }

    @Override
    public boolean targetGoneForGood() {
        return false;
    }

    @Override
    void replayPackets(final Consumer<Packet> send) {}

    @Override
    public String toString() {
        return name;
    }
}
