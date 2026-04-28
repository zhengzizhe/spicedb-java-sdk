package com.authx.sdk;

import com.authx.sdk.model.GrantResult;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Handle returned by {@link WriteFlow#commit()}. Wraps the
 * underlying write outcome and optionally fires synchronous /
 * asynchronous listeners. The write has already happened by the time
 * this object exists — a listener registered here runs immediately
 * (sync) or is submitted to its executor (async).
 *
 * <pre>
 * WriteCompletion completion = auth.on(Document).select(docId)
 *     .grant(Document.Rel.VIEWER).to(User, "alice")
 *     .commit();
 * completion.listener(c -> auditLog.write(c));
 * </pre>
 *
 * <p>Delegates {@link #count()} / {@link #zedToken()} for call-site
 * ergonomics.
 *
 * <p>The underlying {@link #result()} is a {@link GrantResult}
 * regardless of whether the flow contained grants, revokes, or both —
 * SpiceDB's {@code WriteRelationships} RPC is the unified write
 * primitive, and its response shape (zedToken + count) doesn't
 * distinguish TOUCH vs DELETE totals.
 */
public final class WriteCompletion {

    private static final System.Logger LOG =
            System.getLogger(WriteCompletion.class.getName());

    private final GrantResult result;
    private final int pendingCount;
    private boolean listenerRegistered = false;

    WriteCompletion(GrantResult result, int pendingCount) {
        this.result = Objects.requireNonNull(result, "result");
        this.pendingCount = pendingCount;
    }

    /** Access the raw write result (zedToken + count). */
    public GrantResult result() {
        return result;
    }

    /**
     * Number of pending updates that were committed (sum of TOUCH and
     * DELETE). Matches {@code pending.size()} at commit time; the
     * server itself doesn't return a separate count so the SDK
     * preserves the client-side length.
     */
    public int count() {
        return pendingCount;
    }

    /** ZedToken from the write. May be {@code null} for in-memory transport. */
    public String zedToken() {
        return result.zedToken();
    }

    // ── Listeners ────────────────────────────────────────────────────

    /**
     * Invoke {@code callback} synchronously on the calling thread with
     * this completion. Exceptions propagate to the caller.
     */
    public void listener(Consumer<WriteCompletion> callback) {
        markListenerRegistered();
        Objects.requireNonNull(callback, "callback").accept(this);
    }

    /**
     * Dispatch {@code callback} to {@code executor}. Returns immediately.
     * Callback exceptions are caught, logged at WARNING, and swallowed.
     */
    public void listenerAsync(Consumer<WriteCompletion> callback, Executor executor) {
        markListenerRegistered();
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(executor, "executor");
        executor.execute(() -> {
            try {
                callback.accept(this);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING,
                        "WriteCompletion async listener threw — swallowed", t);
            }
        });
    }

    private void markListenerRegistered() {
        if (listenerRegistered) {
            throw new IllegalStateException(
                    "WriteCompletion listener already registered — use one listener per completion");
        }
        listenerRegistered = true;
    }
}
