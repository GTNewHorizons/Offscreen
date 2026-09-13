package com.sbancuz.offscreen.core;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;

import com.sbancuz.offscreen.window.Window;
import com.sbancuz.offscreen.window.input.InputRouter;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class Driver {

    public static final Driver INSTANCE = new Driver();

    private final Map<Integer, Window> windows = new ConcurrentHashMap<>();
    private final InputRouter router = new InputRouter(windows);

    @SubscribeEvent
    public void onRenderTick(final TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (final Window w : windows.values()) {
            w.tryRenderFrame();
        }
    }

    @SubscribeEvent
    public void onClientTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (Minecraft.getMinecraft().theWorld == null && !windows.isEmpty()) {
            closeAll();
            SessionRegistry.INSTANCE.disposeAll();
            return;
        }
        for (final Window w : windows.values()) {
            w.update();
        }
        SessionRegistry.INSTANCE.tickAll();
    }

    public void trackWindow(final Window window) {
        if (windows.containsKey(window.getId())) return;
        windows.put(window.getId(), window);
    }

    public void removeWindow(final Window window) {
        if (!windows.containsKey(window.getId())) return;
        router.releaseIfOwner(window);
        windows.remove(window.getId());
    }

    public Collection<Window> getWindows() {
        return Collections.unmodifiableCollection(windows.values());
    }

    public int getWindowCount() {
        return windows.size();
    }

    public Window getWindow(int id) {
        return windows.get(id);
    }

    public void closeAll() {
        for (final Window w : windows.values()) {
            w.destroy();
        }
    }

    public void closeAll(Predicate<Window> predicate) {
        for (final Window w : windows.values()) {
            if (predicate.test(w)) {
                w.destroy();
            }
        }
    }
}
