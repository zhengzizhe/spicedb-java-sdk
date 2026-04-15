package com.authx.clustertest.matrix;

import com.authx.sdk.cache.CacheStats;
import com.authx.sdk.cache.CaffeineCache;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.CachedTransport;
import com.authx.sdk.transport.CoalescingTransport;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport;

import java.time.Duration;
import java.util.List;

/**
 * Hand-built minimal SDK transport chain for matrix benchmarks.
 * Bypasses AuthxClient.builder() because we need full control over which
 * layers are present (cache on/off, coalescing on/off, metrics).
 *
 * <p>Chain (top → bottom): coalescing? → cached? → in-memory.
 */
public final class MatrixClient implements AutoCloseable {

    private final SdkTransport top;
    private final InMemoryTransport inner;
    private final CaffeineCache<CheckKey, CheckResult> cache;
    private final SdkMetrics metrics;

    /** Default: 2ms backend latency (typical SpiceDB+CRDB RTT). */
    public static MatrixClient create(boolean cacheEnabled, boolean coalescing) {
        return create(cacheEnabled, coalescing, 100_000, Duration.ofMinutes(10), 2000);
    }

    public static MatrixClient create(boolean cacheEnabled, boolean coalescing,
                                       long cacheMaxSize, Duration ttl) {
        return create(cacheEnabled, coalescing, cacheMaxSize, ttl, 2000);
    }

    /**
     * @param backendLatencyMicros simulated latency per backend RPC, in microseconds.
     *                             Pass 0 to disable (InMemoryTransport will respond in ~1μs).
     *                             Pass 2000 (default) to simulate a typical SpiceDB+CRDB round-trip.
     */
    public static MatrixClient create(boolean cacheEnabled, boolean coalescing,
                                       long cacheMaxSize, Duration ttl,
                                       long backendLatencyMicros) {
        var inner = new InMemoryTransport();
        var metrics = new SdkMetrics();
        SdkTransport chain = inner;
        if (backendLatencyMicros > 0) {
            chain = new LatencySimTransport(chain, backendLatencyMicros);
        }
        CaffeineCache<CheckKey, CheckResult> cache = null;
        if (cacheEnabled) {
            cache = new CaffeineCache<>(cacheMaxSize, ttl, CheckKey::resourceIndex);
            chain = new CachedTransport(chain, cache, metrics);
        }
        if (coalescing) {
            chain = new CoalescingTransport(chain, metrics);
        }
        return new MatrixClient(chain, inner, cache, metrics);
    }

    private MatrixClient(SdkTransport top, InMemoryTransport inner,
                          CaffeineCache<CheckKey, CheckResult> cache, SdkMetrics metrics) {
        this.top = top; this.inner = inner; this.cache = cache; this.metrics = metrics;
    }

    /** Pre-populate inner store with N grants so checks against primed-{0..N-1} will return ALLOWED. */
    public void prime(int n) {
        var updates = new java.util.ArrayList<SdkTransport.RelationshipUpdate>(n);
        for (int i = 0; i < n; i++) {
            updates.add(new SdkTransport.RelationshipUpdate(
                    SdkTransport.RelationshipUpdate.Operation.TOUCH,
                    ResourceRef.of("doc", "primed-" + i),
                    Relation.of("viewer"),
                    SubjectRef.of("user", "u-" + i, null)));
        }
        // Batch in chunks to avoid InMemoryTransport assertions.
        int chunk = 1000;
        for (int i = 0; i < updates.size(); i += chunk) {
            inner.writeRelationships(updates.subList(i, Math.min(i + chunk, updates.size())));
        }
    }

    /** Warm the cache by issuing N reads against primed keys — parallel so
     *  backend-latency simulation doesn't stretch setup time linearly. */
    public void warmCache(int n) {
        if (cache == null) return;
        int threads = 32;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var latch = new java.util.concurrent.CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try { check("primed-" + idx, "view", "u-" + idx); }
                    finally { latch.countDown(); }
                });
            }
            try { latch.await(5, java.util.concurrent.TimeUnit.MINUTES); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } finally {
            pool.shutdown();
        }
    }

    /** Single check operation. */
    public boolean check(String docId, String permission, String userId) {
        var req = new CheckRequest(
                ResourceRef.of("doc", docId),
                Permission.of(permission),
                SubjectRef.of("user", userId, null),
                Consistency.minimizeLatency(),
                null);
        var result = top.check(req);
        return result != null && result.hasPermission();
    }

    /** Single grant write (exercises write invalidation path). */
    public void write(String docId, String relation, String userId) {
        top.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("doc", docId),
                Relation.of(relation),
                SubjectRef.of("user", userId, null))));
    }

    public CacheStats cacheStats() {
        return cache != null ? cache.stats() : CacheStats.EMPTY;
    }

    public long cacheSize() {
        return cache != null ? cache.size() : 0;
    }

    @Override public void close() {
        try { top.close(); } catch (Exception ignored) { }
    }
}
