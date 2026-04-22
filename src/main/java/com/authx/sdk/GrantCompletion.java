package com.authx.sdk;

import com.authx.sdk.model.GrantResult;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Chainable handle returned by {@link GrantFlow#commit()}. Wraps the
 * {@link GrantResult} produced by the write and offers optional
 * {@link #listener} / {@link #listenerAsync} hooks that fire immediately
 * (the write has already completed by the time this object exists).
 *
 * <pre>
 * auth.on(Document).select(docId)
 *     .grant(Document.Rel.VIEWER)
 *     .to(User, "alice")
 *     .commit()
 *         .listener(r -> auditLog.write(r))
 *         .listenerAsync(r -> eventBus.emit(r), executor);
 * </pre>
 *
 * <p>Delegates the common {@link GrantResult} accessors
 * ({@link #count()}, {@link #zedToken()}) so that callers who do not
 * need listeners can keep writing {@code .commit().count()} as before.
 */
public final class GrantCompletion {

    private static final System.Logger LOG =
            System.getLogger(GrantCompletion.class.getName());

    private final GrantResult result;

    GrantCompletion(GrantResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    /** Access the raw {@link GrantResult}. */
    public GrantResult result() {
        return result;
    }

    // ── GrantResult delegations (for call-site ergonomics) ───────────

    /** Number of tuples written. Delegates to {@code result().count()}. */
    public int count() {
        return result.count();
    }

    /** ZedToken from the write. May be {@code null} for in-memory transport. */
    public String zedToken() {
        return result.zedToken();
    }

    // ── Listeners ────────────────────────────────────────────────────

    /**
     * Invoke {@code callback} synchronously on the calling thread. The
     * write has already happened, so the callback runs immediately.
     * Exceptions propagate to the caller; subsequent chained listeners
     * after the throwing one do not fire.
     *
     * @throws NullPointerException if {@code callback} is null
     */
    public GrantCompletion listener(Consumer<GrantResult> callback) {
        Objects.requireNonNull(callback, "callback").accept(result);
        return this;
    }

    /**
     * Dispatch {@code callback} to {@code executor}. Returns immediately.
     * Callback exceptions are caught, logged at WARNING, and swallowed
     * — they never reach the caller or affect other listeners.
     *
     * @throws NullPointerException if either argument is null
     * @throws java.util.concurrent.RejectedExecutionException
     *         if {@code executor} refuses the task (propagated)
     */
    public GrantCompletion listenerAsync(Consumer<GrantResult> callback, Executor executor) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(executor, "executor");
        executor.execute(() -> {
            try {
                callback.accept(result);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING,
                        "GrantCompletion async listener threw — swallowed", t);
            }
        });
        return this;
    }
}
