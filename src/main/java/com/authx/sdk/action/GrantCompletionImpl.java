package com.authx.sdk.action;

import com.authx.sdk.model.GrantResult;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Package-private implementation of {@link GrantCompletion}.
 * Stores the aggregated {@link GrantResult} as a final field.
 * Listener methods return {@code this} for chaining.
 */
final class GrantCompletionImpl implements GrantCompletion {

    private final GrantResult result;

    GrantCompletionImpl(GrantResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public GrantResult result() {
        return result;
    }

    @Override
    public GrantCompletion listener(Consumer<GrantResult> callback) {
        Objects.requireNonNull(callback, "callback");
        callback.accept(result);
        return this;
    }

    @Override
    public GrantCompletion listenerAsync(Consumer<GrantResult> callback, Executor executor) {
        // Filled in by T005.
        throw new UnsupportedOperationException("listenerAsync — implemented in T005");
    }
}
