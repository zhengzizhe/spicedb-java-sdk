package com.authcses.sdk.policy;

import java.time.Duration;

/**
 * Read consistency level. Each level maps to a SpiceDB Consistency parameter + SDK token tracking.
 *
 * <p>Implemented:
 * <ul>
 *   <li>{@link #strong()} — fully consistent, highest latency</li>
 *   <li>{@link #session()} — read-after-write within this client instance (via ZedToken tracking)</li>
 *   <li>{@link #minimizeLatency()} — lowest latency, may read stale</li>
 * </ul>
 *
 * <p>Planned (requires TokenTracker):
 * <ul>
 *   <li>{@link #boundedStaleness(Duration)} — accept data up to N seconds old</li>
 *   <li>{@link #monotonicRead()} — never read older than last read</li>
 *   <li>{@link #snapshot()} — consistent snapshot for pagination</li>
 * </ul>
 */
public sealed interface ReadConsistency {

    /** Always read the absolute latest state. SpiceDB: fully_consistent=true */
    static ReadConsistency strong() { return Strong.INSTANCE; }

    /** Read-after-write within same client. SpiceDB: at_least_as_fresh(lastWriteToken) */
    static ReadConsistency session() { return Session.INSTANCE; }

    /** Lowest latency, may read stale data. SpiceDB: minimize_latency=true */
    static ReadConsistency minimizeLatency() { return MinimizeLatency.INSTANCE; }

    /** Accept data up to maxAge old. Requires TokenTracker (planned). */
    static ReadConsistency boundedStaleness(Duration maxAge) { return new BoundedStaleness(maxAge); }

    /** Never read data older than the last read. Requires TokenTracker (planned). */
    static ReadConsistency monotonicRead() { return MonotonicRead.INSTANCE; }

    /** Consistent snapshot for pagination. SpiceDB: at_exact_snapshot(token) */
    static ReadConsistency snapshot() { return Snapshot.INSTANCE; }

    record Strong() implements ReadConsistency {
        static final Strong INSTANCE = new Strong();
    }
    record Session() implements ReadConsistency {
        static final Session INSTANCE = new Session();
    }
    record MinimizeLatency() implements ReadConsistency {
        static final MinimizeLatency INSTANCE = new MinimizeLatency();
    }
    record BoundedStaleness(Duration maxAge) implements ReadConsistency {}
    record MonotonicRead() implements ReadConsistency {
        static final MonotonicRead INSTANCE = new MonotonicRead();
    }
    record Snapshot() implements ReadConsistency {
        static final Snapshot INSTANCE = new Snapshot();
    }
}
