package com.sbancuz.offscreen.container;

import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;

import org.lwjgl.opengl.GL11;

import com.sbancuz.offscreen.container.ServerContainer.State;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

/**
 * What a view shows of the multiplexer: a gated view is dimmed under a badge that says why
 * and can be clicked, the hint of its container is shown over it, and the cursor stack is drawn
 * only in views of the active container and in free views.
 */
@Lwjgl3Aware
public final class GatedViewOverlay {

    private final ServerContainerMultiplexer multiplexer;
    private final Minecraft mc = Minecraft.getMinecraft();

    GatedViewOverlay(final ServerContainerMultiplexer multiplexer) {
        this.multiplexer = multiplexer;
    }

    /** Drawn over the view's screen, inside its scope. */
    public void draw(final ScreenView v, final int width, final int height) {
        if (!multiplexer.isInWorld()) return;
        final ServerContainer b = v.gatingContainer();
        if (b == null) return;
        final boolean gated = !v.receivesInput();
        final Optional<OverlayHint> hint = b.shownHint();
        if (!gated && !hint.isPresent()) return;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glTranslatef(0f, 0f, 400f);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        if (gated) drawBadge(b, width, height);
        hint.ifPresent(h -> drawHint(h, width));
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    /** Whether a press at {@code x, y} hits the badge of a view gated by {@code b}. */
    boolean badgeContains(final ServerContainer b, final int x, final int y) {
        final int right = 10 + mc.fontRenderer.getStringWidth(badgeLabel(b));
        return y >= 2 && y < 14 && x >= 2 && x < right;
    }

    /** Whether {@code gc} draws the cursor stack: the stack belongs to the server's open container. */
    public boolean cursorVisible(final GuiContainer gc) {
        if (!multiplexer.isInWorld()) return true;
        final ScreenView v = multiplexer.contexts.current()
            .view();
        if (v.screen() != gc) return true;

        final ServerContainer b = v.gatingContainer();
        return b == null || b == multiplexer.activeContainer();
    }

    private String badgeLabel(final ServerContainer b) {
        if (multiplexer.isDegraded()) return I18n.format("offscreen.container.status.unknown");
        if (b == multiplexer.activeContainer() || b.state == State.REOPENING)
            return I18n.format("offscreen.container.status.busy");
        if (b.canReopen()) return I18n.format("offscreen.container.status.snapshot");
        return I18n.format("offscreen.container.status.paused");
    }

    private void drawBadge(final ServerContainer b, final int width, final int height) {
        final FontRenderer fr = mc.fontRenderer;
        final String label = badgeLabel(b);
        Gui.drawRect(0, 0, width, height, 0x60101018);
        Gui.drawRect(2, 2, 10 + fr.getStringWidth(label), 14, 0xC0000000);

        int color = 0xFFD080;
        if (multiplexer.isDegraded()) color = 0xFF5555;
        fr.drawStringWithShadow(label, 6, 4, color);
    }

    private void drawHint(final OverlayHint hint, final int width) {
        final FontRenderer fr = mc.fontRenderer;
        final String text = I18n.format(hint.translationKey);
        final int w = fr.getStringWidth(text);
        final int x = (width - w) / 2;
        Gui.drawRect(x - 4, 18, x + w + 4, 30, 0xC0000000);
        fr.drawStringWithShadow(text, x, 20, 0xFF8080);
    }
}
