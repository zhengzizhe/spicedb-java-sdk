package com.authx.sdk;

import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.WriteResult;

import java.util.Objects;

/**
 * Handle returned by {@link WriteFlow#commit()}. Wraps the underlying
 * write outcome.
 *
 * <pre>
 * WriteCompletion completion = auth.on(Document).select(docId)
 *     .grant(Document.Rel.VIEWER).to(User, "alice")
 *     .commit();
 * auditLog.write(completion);
 * </pre>
 *
 * <p>Delegates {@link #count()} / {@link #zedToken()} for call-site
 * ergonomics.
 *
 * <p>The underlying {@link #result()} is a {@link WriteResult}. SpiceDB's
 * {@code WriteRelationships} RPC is the unified write primitive, so the
 * result shape is the same for grants, revokes, and mixed writes.
 */
public final class WriteCompletion {

    private final WriteResult result;
    private final int pendingCount;

    WriteCompletion(WriteResult result, int pendingCount) {
        this.result = Objects.requireNonNull(result, "result");
        this.pendingCount = pendingCount;
    }

    /** Access the raw write result (zedToken + submitted update count). */
    public WriteResult result() {
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

    /** Preferred name for {@link #count()}: number of submitted relationship updates. */
    public int updateCount() {
        return pendingCount;
    }

    /** ZedToken from the write. May be {@code null} for in-memory transport. */
    public String zedToken() {
        return result.zedToken();
    }

    /** Create a read consistency level that is at least as fresh as this write. */
    public Consistency asConsistency() {
        return result.asConsistency();
    }
}
