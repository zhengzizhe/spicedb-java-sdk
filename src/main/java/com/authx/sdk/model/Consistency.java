package com.authx.sdk.model;

/**
 * SpiceDB consistency level for read operations, controlling the freshness vs. latency trade-off.
 *
 * <p>Use the static factory methods to select the desired consistency:
 * <ul>
 *   <li>{@link #minimizeLatency()} -- fastest reads, may return slightly stale data</li>
 *   <li>{@link #atLeast(String)} -- reads at least as fresh as the given ZedToken</li>
 *   <li>{@link #atExactSnapshot(String)} -- reads at the exact revision of the given ZedToken</li>
 *   <li>{@link #full()} -- fully consistent reads (highest latency)</li>
 * </ul>
 */
public sealed interface Consistency {

    /**
     * Returns a consistency level that minimizes latency, allowing SpiceDB to serve from any replica.
     *
     * @return the minimize-latency consistency singleton
     */
    static Consistency minimizeLatency() {
        return MinimizeLatency.INSTANCE;
    }

    /**
     * Returns a consistency level that guarantees reads at least as fresh as the given ZedToken.
     *
     * @param zedToken the ZedToken representing the minimum required freshness
     * @return an at-least consistency bound to the token
     */
    static Consistency atLeast(String zedToken) {
        return new AtLeast(zedToken);
    }

    /**
     * Returns a consistency level that reads at exactly the revision of the given ZedToken.
     *
     * @param zedToken the ZedToken representing the exact snapshot to read
     * @return an exact-snapshot consistency bound to the token
     */
    static Consistency atExactSnapshot(String zedToken) {
        return new AtExactSnapshot(zedToken);
    }

    /**
     * Returns a consistency level that guarantees fully consistent reads from the primary.
     *
     * @return the full-consistency singleton
     */
    static Consistency full() {
        return Full.INSTANCE;
    }

    /** Minimize-latency consistency -- SpiceDB may serve from any available replica. */
    record MinimizeLatency() implements Consistency {
        static final MinimizeLatency INSTANCE = new MinimizeLatency();
    }

    /**
     * At-least consistency -- reads are guaranteed to be at least as fresh as the given ZedToken.
     *
     * @param zedToken the minimum freshness ZedToken
     */
    record AtLeast(String zedToken) implements Consistency {
    }

    /**
     * Exact-snapshot consistency -- reads are served at exactly the revision of the given ZedToken.
     *
     * @param zedToken the exact snapshot ZedToken
     */
    record AtExactSnapshot(String zedToken) implements Consistency {
    }

    /** Full consistency -- reads go through the primary for maximum freshness. */
    record Full() implements Consistency {
        static final Full INSTANCE = new Full();
    }
}
