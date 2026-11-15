package com.sbancuz.offscreen.container.context;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.inventory.Container;

import com.sbancuz.offscreen.container.ServerContainerMultiplexer;

/**
 * Who the running code acts for: a stack of {@link ExecutionContext}s, each the scope of a run. The hooks ask the
 * innermost context what to do, so they never need to know where they were called from. The bottom context is
 * the main window ({@link MainWindowContext}); code on other threads sees {@link OffThreadContext}.
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

    void push(final ExecutionContext context) {
        context.enclosing = top;
        top = context;
    }

    void pop(final ExecutionContext context) {
        top = context.enclosing;
    }

    /** Runs {@code body} for the main window's view; what it deferred is applied after the run. */
    public void runInMainWindowView(final Runnable body) {
        new ViewContext(multiplexer, multiplexer.mainWindow, true).run(body);
    }

    /** A client container's close logic, with nothing it sends reaching the server. */
    public void runClientCloseLogic(final Container clientContainer, final EntityClientPlayerMP player) {
        new ClientCloseLogicContext(multiplexer, clientContainer).run(() -> clientContainer.onContainerClosed(player));
    }
}
