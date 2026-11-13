package com.sbancuz.offscreen.container.context;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiScreen;

import com.sbancuz.offscreen.container.ContainerAdapter.PayloadEffect;
import com.sbancuz.offscreen.container.ScreenView;
import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/**
 * A run of a view's input, update or draw. Whether the view is active is fixed at entry.
 * Screen changes the view's GUI asks for are the view's to apply; what must not
 * happen while the GUI runs is collected here and applied once the run is over.
 */
public final class ViewContext extends ExecutionContext {

    private final ScreenView view;
    private final boolean forActiveView;
    private final List<Runnable> afterRun = new ArrayList<>(0);
    /** The run sent packets that act on the server's open container or mod payloads. */
    private boolean serverDependent;

    ViewContext(final ServerContainerMultiplexer multiplexer, final ScreenView view) {
        super(multiplexer);
        this.view = view;
        this.forActiveView = view.isActiveView();
    }

    @Override
    public boolean actsForActiveView() {
        return forActiveView;
    }

    @Override
    PayloadEffect effectOfUnknownPayload() {
        return view.effectOfUnknownPayload();
    }

    @Override
    public ScreenView view() {
        return view;
    }

    @Override
    void noteServerDependentSend() {
        serverDependent = true;
    }

    boolean passedServerDependentTraffic() {
        return serverDependent;
    }

    @Override
    public void displayScreen(@Nullable final GuiScreen s, final Consumer<GuiScreen> vanilla) {
        view.onDisplayRequested(s, vanilla);
    }

    @Override
    public void closeScreen(final boolean packet) {
        view.onCloseRequested(packet);
    }

    @Override
    public void runAfterScope(final Runnable r) {
        afterRun.add(r);
    }

    void applyAfterRun() {
        afterRun.forEach(Runnable::run);
    }
}
