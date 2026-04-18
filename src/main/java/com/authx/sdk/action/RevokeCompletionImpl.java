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

    private static final System.Logger LOG =
            System.getLogger("com.authx.sdk.action.RevokeCompletion");

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
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(executor, "executor");
        executor.execute(() -> {
            try {
                callback.accept(result);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING,
                        "Async revoke listener threw (source={0}): {1}",
                        callback.getClass().getName(), t.toString());
            }
        });
        return this;
    }
}
