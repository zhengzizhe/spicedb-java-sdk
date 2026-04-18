package com.authx.sdk.action;

import com.authx.sdk.model.RevokeResult;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Handle returned by typed revoke terminal methods. Mirror of
 * {@link GrantCompletion} for {@link RevokeResult}. See
 * {@link GrantCompletion} for listener semantics.
 */
public sealed interface RevokeCompletion permits RevokeCompletionImpl {

    /** Aggregated revoke result (never {@code null}). */
    RevokeResult result();

    /**
     * Run {@code callback} on the current thread before returning.
     *
     * @throws NullPointerException if {@code callback} is null
     */
    RevokeCompletion listener(Consumer<RevokeResult> callback);

    /**
     * Dispatch {@code callback} to {@code executor} and return
     * immediately. Callback exceptions are caught, logged at WARNING
     * under logger name {@code com.authx.sdk.action.RevokeCompletion},
     * and otherwise swallowed.
     *
     * @throws NullPointerException       if either argument is null
     * @throws java.util.concurrent.RejectedExecutionException
     *         if {@code executor} refuses the task (propagated unchanged)
     */
    RevokeCompletion listenerAsync(Consumer<RevokeResult> callback, Executor executor);

    /**
     * Internal factory — see {@link GrantCompletion#of(com.authx.sdk.model.GrantResult)}.
     */
    static RevokeCompletion of(RevokeResult result) {
        return new RevokeCompletionImpl(result);
    }
}
