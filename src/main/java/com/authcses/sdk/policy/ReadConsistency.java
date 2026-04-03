package com.authcses.sdk.policy;

import java.time.Duration;

/**
 * Read consistency level for per-resource-type policy.
 *
 * <ul>
 *   <li>{@link #minimizeLatency()} — fastest, may read stale (SpiceDB 5s quantization window)</li>
 *   <li>{@link #session()} — write-then-read safe within this SDK instance (default)</li>
 *   <li>{@link #snapshot()} — frozen point-in-time for pagination/export</li>
 *   <li>{@link #strong()} — absolute latest, bypasses all caches</li>
 * </ul>
 */
public sealed interface ReadConsistency {

    /** Lowest latency, may read up to ~5s stale. SpiceDB: minimize_latency=true */
    static ReadConsistency minimizeLatency() { return MinimizeLatency.INSTANCE; }

    /** Read-after-write within same SDK instance. SpiceDB: at_least_as_fresh(lastWriteToken) */
    static ReadConsistency session() { return Session.INSTANCE; }

    /** Frozen snapshot for pagination/export. SpiceDB: at_exact_snapshot(token) */
    static ReadConsistency snapshot() { return Snapshot.INSTANCE; }

    /** Absolute latest state, highest latency. SpiceDB: fully_consistent=true */
    static ReadConsistency strong() { return Strong.INSTANCE; }

    record MinimizeLatency() implements ReadConsistency {
        static final MinimizeLatency INSTANCE = new MinimizeLatency();
    }
    record Session() implements ReadConsistency {
        static final Session INSTANCE = new Session();
    }
    record Snapshot() implements ReadConsistency {
        static final Snapshot INSTANCE = new Snapshot();
    }
    record Strong() implements ReadConsistency {
        static final Strong INSTANCE = new Strong();
    }
}
