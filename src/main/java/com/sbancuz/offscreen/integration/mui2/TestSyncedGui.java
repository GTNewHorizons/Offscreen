package com.sbancuz.offscreen.integration.mui2;

import java.util.concurrent.atomic.AtomicInteger;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.sbancuz.offscreen.Offscreen;

/**
 * Synced open behavior check: button sends clicks up (C2S), label shows the
 * server counter coming back (S2C). Common-safe: the builder runs on both sides.
 */
public final class TestSyncedGui {

    private TestSyncedGui() {}

    private static final AtomicInteger SERVER_CLICKS = new AtomicInteger();

    public static final OffscreenSimpleFactory FACTORY = new OffscreenSimpleFactory(
        "offscreen_synced_test",
        Offscreen.MODID,
        TestSyncedGui::build);

    /** Referenced from init so the factory registers on both sides. */
    public static void init() {}

    private static ModularPanel build(final GuiData data, final PanelSyncManager syncManager,
        final UISettings settings) {
        final int[] mirror = { 0 };
        final IntSyncValue clicks = new IntSyncValue(
            () -> mirror[0],
            v -> mirror[0] = v,
            SERVER_CLICKS::get,
            SERVER_CLICKS::set);
        clicks.allowC2S();
        syncManager.syncValue("clicks", clicks);

        final ModularPanel panel = ModularPanel.defaultPanel("offscreen_synced_test", 220, 150);
        final var title = new TextWidget<>(IKey.str("Synced (server roundtrip)"));
        title.pos(10, 10);
        final var echo = new TextWidget<>(IKey.dynamic(() -> "Server clicks: " + clicks.getValue()));
        echo.pos(10, 25);
        final var hint = new TextWidget<>(IKey.str("Button -> server, label <- server"));
        hint.pos(10, 40);
        final var button = new ButtonWidget<>().overlay(IKey.str("+1"))
            .pos(10, 60)
            .size(60, 20)
            .onMouseTapped(mouseButton -> {
                clicks.setValue(clicks.getValue() + 1, true, true);
                return true;
            });
        return panel.child(title)
            .child(echo)
            .child(hint)
            .child(button);
    }
}
