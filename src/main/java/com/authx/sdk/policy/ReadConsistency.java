package com.authx.sdk.policy;

import java.time.Duration;

/**
 * Read consistency level for per-resource-type policy.
 *
 * <ul>
 *   <li>{@link #minimizeLatency()} — fastest, may read stale (SpiceDB 5s quantization window)</li>
 *   <li>{@link #session()} — best-effort write token tracking per resource type (default)</li>
 *   <li>{@link #snapshot()} — frozen point-in-time for pagination/export</li>
 *   <li>{@link #strong()} — absolute latest, bypasses all caches</li>
 * </ul>
 */
public sealed interface ReadConsistency {

    /** Lowest latency, may read up to ~5s stale. SpiceDB: minimize_latency=true */
    static ReadConsistency minimizeLatency() { return MinimizeLatency.INSTANCE; }

    /** Best-effort per-type last observed write token. Does not order concurrent writes or infer graph dependencies. */
    static ReadConsistency session() { return Session.INSTANCE; }

    /** Required shared namespace token; missing or uncertain coordination fails closed. */
    static ReadConsistency coordinatedSession() { return CoordinatedSession.INSTANCE; }

    /** Frozen snapshot for pagination/export. SpiceDB: at_exact_snapshot(token) */
    static ReadConsistency snapshot() { return Snapshot.INSTANCE; }

    /** Head revision, highest latency. Use a write token for causal read-after-write guarantees. */
    static ReadConsistency strong() { return Strong.INSTANCE; }

    /**
     * Read data no older than the given staleness bound.
     * SpiceDB: at_least_as_fresh with a time-based bound.
     * Planned — exact gRPC mapping depends on SpiceDB version.
     */
    static ReadConsistency boundedStaleness(Duration maxStaleness) {
        return new BoundedStaleness(maxStaleness);
    }

    record MinimizeLatency() implements ReadConsistency {
        static final MinimizeLatency INSTANCE = new MinimizeLatency();
    }
    record Session() implements ReadConsistency {
        static final Session INSTANCE = new Session();
    }
    record CoordinatedSession() implements ReadConsistency {
        static final CoordinatedSession INSTANCE = new CoordinatedSession();
    }
    record Snapshot() implements ReadConsistency {
        static final Snapshot INSTANCE = new Snapshot();
    }
    record Strong() implements ReadConsistency {
        static final Strong INSTANCE = new Strong();
    }
    record BoundedStaleness(Duration maxStaleness) implements ReadConsistency {}
}
