package com.authx.sdk.action;

import com.authx.sdk.model.RevokeResult;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Package-private implementation of {@link RevokeCompletion}.
 * Stores the aggregated {@link RevokeResult} as a final field.
 * Listener methods return {@code this} for chaining.
 */
final class RevokeCompletionImpl implements RevokeCompletion {

    private final RevokeResult result;

    RevokeCompletionImpl(RevokeResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public RevokeResult result() {
        return result;
    }

    @Override
    public RevokeCompletion listener(Consumer<RevokeResult> callback) {
        Objects.requireNonNull(callback, "callback");
        callback.accept(result);
        return this;
    }

    @Override
    public RevokeCompletion listenerAsync(Consumer<RevokeResult> callback, Executor executor) {
        // Filled in by T006.
        throw new UnsupportedOperationException("listenerAsync — implemented in T006");
    }
}
