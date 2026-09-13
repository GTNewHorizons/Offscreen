package com.sbancuz.offscreen.scope;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.sbancuz.offscreen.Offscreen;

import cpw.mods.fml.common.Loader;

public final class ScopePipeline implements Scope {

    private final Scope[] scopes;
    private int enteredCount;

    private ScopePipeline(final Scope[] scopes) {
        this.scopes = scopes;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void enter() {
        int i = 0;
        try {
            for (; i < scopes.length; i++) {
                scopes[i].enter();
            }
            enteredCount = scopes.length;
        } catch (final Throwable enterFailure) {
            enteredCount = i;
            unwind(enterFailure);
            throw (enterFailure instanceof RuntimeException) ? (RuntimeException) enterFailure
                : new RuntimeException(enterFailure);
        }
    }

    @Override
    public void restore() {
        unwind(null);
    }

    private void unwind(final @Nullable Throwable enterFailureOrNull) {
        if (enterFailureOrNull != null) {
            Offscreen.LOG
                .error("[Offscreen] scope enter() failed; unwinding already-entered scopes", enterFailureOrNull);
        }
        for (int i = enteredCount - 1; i >= 0; i--) {
            try {
                scopes[i].restore();
            } catch (final Throwable restoreFailure) {
                Offscreen.LOG.error(
                    "[Offscreen] scope restore() failed during unwind; continuing with the rest",
                    restoreFailure);
            }
        }
        enteredCount = 0;
    }

    public static final class Builder {

        private final List<Scope> scopes = new ArrayList<>();

        public Builder always(final Scope scope) {
            scopes.add(scope);
            return this;
        }

        public Builder when(final boolean condition, final Scope scope) {
            if (condition) {
                scopes.add(scope);
            }
            return this;
        }

        public Builder ifModLoaded(final String modId, final Supplier<Scope> scopeFactory) {
            if (Loader.isModLoaded(modId)) {
                scopes.add(scopeFactory.get());
            }
            return this;
        }

        public ScopePipeline build() {
            return new ScopePipeline(scopes.toArray(new Scope[0]));
        }
    }
}
