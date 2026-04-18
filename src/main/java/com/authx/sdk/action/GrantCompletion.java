package com.authx.sdk.action;

import com.authx.sdk.model.GrantResult;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Handle returned by typed grant terminal methods. Carries the
 * aggregated {@link GrantResult} of a synchronous write and supports
 * attaching one or more completion listeners.
 *
 * <p>Two listener methods are provided:
 * <ul>
 *   <li>{@link #listener(Consumer)} — invokes the callback synchronously
 *       on the current thread before returning.</li>
 *   <li>{@link #listenerAsync(Consumer, Executor)} — dispatches the
 *       callback to the supplied executor and returns immediately.</li>
 * </ul>
 *
 * <p>Ignoring the return value (statement form) is fully supported:
 * existing callers that used the terminal methods in {@code void} form
 * compile and run unchanged.
 *
 * <p>This interface is sealed: the only implementation is
 * {@link GrantCompletionImpl}, created internally by the SDK via
 * {@link #of(GrantResult)}.
 */
public sealed interface GrantCompletion permits GrantCompletionImpl {

    /** Aggregated write result (never {@code null}). */
    GrantResult result();

    /**
     * Run {@code callback} on the current thread before returning.
     *
     * @throws NullPointerException if {@code callback} is null
     */
    GrantCompletion listener(Consumer<GrantResult> callback);

    /**
     * Dispatch {@code callback} to {@code executor} and return
     * immediately. Callback exceptions are caught, logged at WARNING
     * under logger name {@code com.authx.sdk.action.GrantCompletion},
     * and otherwise swallowed — they do not reach the caller, do not
     * affect the write outcome, and do not cancel other already-submitted
     * async listeners.
     *
     * @throws NullPointerException       if either argument is null
     * @throws java.util.concurrent.RejectedExecutionException
     *         if {@code executor} refuses the task (propagated unchanged)
     */
    GrantCompletion listenerAsync(Consumer<GrantResult> callback, Executor executor);

    /**
     * Internal factory — used by {@code TypedGrantAction} (which lives in
     * a sibling package) to create completion instances without exposing
     * {@link GrantCompletionImpl} publicly.
     */
    static GrantCompletion of(GrantResult result) {
        return new GrantCompletionImpl(result);
    }
}
