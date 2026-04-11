package com.authx.sdk.spi;

import java.time.Duration;

/**
 * Generic at-most-once idempotency gate for keyed events.
 *
 * <p>{@code DuplicateDetector} is used by the SDK to suppress redundant delivery
 * of the same logical event — most notably, {@code WatchResponse} events that
 * are replayed by SpiceDB around the cursor boundary after a stream reconnect.
 * It is <b>process-local</b>: a {@code DuplicateDetector} instance inside one JVM
 * cannot see what another JVM has processed. Cross-process deduplication is a
 * separate concern (see the multi-instance listener discussion in the README).
 *
 * <p>Usage contract:
 * <pre>
 * if (detector.tryProcess(key)) {
 *     // First time we've seen this key — run the side effect.
 *     runListener(event);
 * } else {
 *     // Already processed — skip silently.
 * }
 * </pre>
 *
 * <p>{@link #tryProcess(Object)} MUST be atomic: concurrent callers with the
 * same key must have exactly one return {@code true} and the rest return
 * {@code false}. Implementations MUST be thread-safe.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link #noop()} — always returns true (no deduplication)</li>
 *   <li>{@code com.authx.sdk.dedup.CaffeineDuplicateDetector} — bounded LRU with TTL</li>
 * </ul>
 *
 * @param <K> the key type used to identify events (most commonly {@code String}
 *            for {@code zedToken}, but can be any hashable type)
 */
@FunctionalInterface
public interface DuplicateDetector<K> {

    /**
     * Atomically test-and-set: return {@code true} if this is the first time the
     * caller has seen {@code key}; otherwise record nothing new and return {@code false}.
     *
     * <p>Passing {@code null} is a no-op that returns {@code true} — callers that
     * cannot produce a stable key for an event should get pass-through behavior
     * (fail-open rather than drop the event).
     */
    boolean tryProcess(K key);

    /** A detector that never deduplicates. Every call returns {@code true}. */
    static <K> DuplicateDetector<K> noop() {
        return key -> true;
    }

    /**
     * Common-case factory for in-memory bounded-LRU deduplication with TTL.
     * Delegates to {@code CaffeineDuplicateDetector} in the {@code dedup} package
     * so the SPI interface itself has no hard dependency on Caffeine.
     *
     * <p>Caffeine is a {@code compileOnly} dependency of the SDK — most consumers
     * already include it for the L1 cache. If it is missing from the runtime
     * classpath, this factory <b>gracefully degrades to {@link #noop()}</b> with
     * a single WARNING log line, mirroring the behavior of {@code CachedTransport}.
     * This means a missing dependency will silently disable dedup but never
     * crash the SDK.
     *
     * <p>To get hard-failure behavior instead (catch the missing dependency
     * earlier), construct {@code com.authx.sdk.dedup.CaffeineDuplicateDetector}
     * directly.
     *
     * @param maxEntries maximum number of distinct keys retained; oldest entries
     *                   are evicted when exceeded
     * @param ttl        how long a key is remembered after first insertion
     */
    static <K> DuplicateDetector<K> lru(int maxEntries, Duration ttl) {
        // Probe for Caffeine availability BEFORE referencing the implementation
        // class. Going straight to `new CaffeineDuplicateDetector<>` would cause
        // some strict JVM verifiers to eagerly link CaffeineDuplicateDetector —
        // which transitively references Caffeine types — at JIT time, BEFORE
        // the catch block gets a chance to run. A Class.forName probe with
        // initialize=false keeps the failure strictly at the reflective site
        // where we can observe it with ClassNotFoundException, and leaves
        // NoClassDefFoundError as a belt-and-braces backstop.
        try {
            Class.forName("com.github.benmanes.caffeine.cache.Caffeine",
                    false, DuplicateDetector.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            System.getLogger(DuplicateDetector.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "DuplicateDetector.lru() requested but Caffeine not on classpath. " +
                            "Add dependency: com.github.ben-manes.caffeine:caffeine:3.1.8. " +
                            "Falling back to no-op detector (no deduplication will occur).");
            return noop();
        }
        try {
            return new com.authx.sdk.dedup.CaffeineDuplicateDetector<>(maxEntries, ttl);
        } catch (NoClassDefFoundError e) {
            // Belt-and-braces: even if Class.forName above succeeded, the
            // implementation class could still fail to link for other reasons
            // (partial shading, version skew). Fall back to noop the same way.
            System.getLogger(DuplicateDetector.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "DuplicateDetector.lru() failed to construct CaffeineDuplicateDetector: {0}. " +
                            "Falling back to no-op detector.", e.toString());
            return noop();
        }
    }
}
