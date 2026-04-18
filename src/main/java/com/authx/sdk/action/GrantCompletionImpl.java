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

    private static final System.Logger LOG =
            System.getLogger("com.authx.sdk.action.GrantCompletion");

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
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(executor, "executor");
        // Task wrapping: catch any Throwable from the user callback so that
        // (a) the caller of listenerAsync never sees it, (b) the executor's
        // UncaughtExceptionHandler doesn't print stacktraces for routine
        // listener bugs, and (c) the thread stays alive for subsequent tasks.
        // The submission itself (executor.execute) is NOT wrapped — a
        // RejectedExecutionException from a saturated/shutdown executor must
        // propagate to the caller per SR:req-8.
        executor.execute(() -> {
            try {
                callback.accept(result);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING,
                        "Async grant listener threw (source={0}): {1}",
                        callback.getClass().getName(), t.toString());
            }
        });
        return this;
    }
}
