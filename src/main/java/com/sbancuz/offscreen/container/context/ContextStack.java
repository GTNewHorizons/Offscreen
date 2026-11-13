package com.sbancuz.offscreen.container.context;

import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.inventory.Container;

import com.sbancuz.offscreen.container.ServerContainerMultiplexer;
import com.sbancuz.offscreen.window.GuiGlobals;
import com.sbancuz.offscreen.window.HostedScreen;

/**
 * Who the running code acts for: a stack of {@link ExecutionContext}s. The hooks
 * ask the innermost context what to do, so they never need to know where they were called from.
 * The bottom context is the main window ({@link MainWindowContext}); code on other threads sees
 * {@link OffThreadContext}. What the globals look like during a run is {@link GuiGlobals}'s job.
 */
public final class ContextStack {

    private final ServerContainerMultiplexer multiplexer;
    private final ExecutionContext foreign;
    private volatile Thread mainThread;
    private ExecutionContext top;

    public ContextStack(final ServerContainerMultiplexer multiplexer) {
        this.multiplexer = multiplexer;
        this.foreign = new OffThreadContext(multiplexer);
        this.top = new MainWindowContext(multiplexer);
    }

    public void init() {
        mainThread = Thread.currentThread();
    }

    public boolean onMainThread() {
        final Thread t = mainThread;
        return t == null || Thread.currentThread() == t;
    }

    /** The innermost context of this thread. */
    public ExecutionContext current() {
        if (onMainThread()) return top;
        return foreign;
    }

    /** Runs {@code body} in {@code context}. */
    public void run(final ExecutionContext context, final Runnable body) {
        call(context, () -> {
            body.run();
            return Boolean.TRUE;
        });
    }

    /** {@link #run} for a body with a result. */
    public <T> T call(final ExecutionContext context, final Supplier<T> body) {
        context.enclosing = top;
        top = context;
        try {
            return body.get();
        } finally {
            top = context.enclosing;
        }
    }

    /** Runs {@code body} for the main window's view; what it deferred is applied after the run. */
    public void runInMainWindowView(final Runnable body) {
        final ViewContext run = new ViewContext(multiplexer, multiplexer.mainWindow);
        run(run, body);
        run.applyAfterRun();
    }

    /**
     * Runs {@code body} for the hosted entry's view, with Minecraft's globals set for it; the screen changes the
     * body asked for are applied after the run, outside the scope. Without a world nothing
     * is protected and only the current screen is the entry's.
     */
    public void runInHostedView(final HostedScreen<?> entry, final Runnable body, final boolean applyDeferred) {
        final GuiGlobals globals = multiplexer.globals;
        if (!multiplexer.isInWorld()) {
            globals.runWithScreen(entry.screen(), body);
            return;
        }
        final ViewContext run = new ViewContext(multiplexer, entry);
        run(run, () -> globals.runWith(entry.screen(), entry.containerForGlobals(), body));

        // Replies before the ordering confirmation belong to this view
        if (run.passedServerDependentTraffic()) multiplexer.attributeArrivalsTo(entry);
        if (applyDeferred) run.applyAfterRun();
    }

    /** A client container's close logic, with nothing it sends reaching the server. */
    public void runClientCloseLogic(final Container clientContainer, final EntityClientPlayerMP player) {
        final GuiGlobals globals = multiplexer.globals;
        final Runnable closeLogic = () -> clientContainer.onContainerClosed(player);
        run(
            new ClientCloseLogicContext(multiplexer),
            () -> globals.runWith(Minecraft.getMinecraft().currentScreen, clientContainer, closeLogic));
    }
}
