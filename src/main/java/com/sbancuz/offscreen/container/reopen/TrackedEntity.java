package com.sbancuz.offscreen.container.reopen;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

/** An entity recorded by id and class. */
record TrackedEntity(int id, Class<?> cls) {

    TrackedEntity(final Entity e) {
        this(e.getEntityId(), e.getClass());
    }

    @Nullable
    Entity find() {
        return Minecraft.getMinecraft().theWorld.getEntityByID(id);
    }

    boolean alive() {
        return find() instanceof Entity e && !e.isDead && e.getClass() == cls;
    }

    boolean gone() {
        return find() instanceof Entity e && (e.isDead || e.getClass() != cls);
    }
}
