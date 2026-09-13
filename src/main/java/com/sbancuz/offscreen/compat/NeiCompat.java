package com.sbancuz.offscreen.compat;

import java.util.function.Supplier;

import net.minecraft.client.gui.inventory.GuiContainer;

import com.sbancuz.offscreen.api.HostUI;
import com.sbancuz.offscreen.integration.nei.NeiScope;
import com.sbancuz.offscreen.integration.nei.NeiWrapperUI;
import com.sbancuz.offscreen.scope.Scope;

/**
 * NEI integration entry points. Only call {@link ModCompat#NEI}-gated code
 * through here so the NEI classes below are never touched when NEI is absent.
 */
public final class NeiCompat {

    private NeiCompat() {}

    /** Wraps container-backed UIs with NEI handling; returns the input otherwise. */
    public static HostUI wrapIfNeeded(final HostUI base) {
        if (!ModCompat.NEI.isLoaded()) return base;
        if (base.getGuiScreen() instanceof GuiContainer) {
            return new NeiWrapperUI(base);
        }
        return base;
    }

    /** Window-level NEI scope for the currently hosted container. */
    public static Scope windowScope(final Supplier<GuiContainer> containerSupplier) {
        return new NeiScope(containerSupplier);
    }
}
