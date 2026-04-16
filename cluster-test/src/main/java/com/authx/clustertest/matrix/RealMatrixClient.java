package com.authx.clustertest.matrix;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.cache.CacheStats;
import com.authx.sdk.policy.CachePolicy;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.policy.ReadConsistency;
import com.authx.sdk.policy.ResourcePolicy;

import java.time.Duration;

/**
 * Matrix client variant that talks to a real SpiceDB cluster via AuthxClient.
 * Used by B-class / production-grade scenarios — replaces
 * InMemoryTransport + LatencySim with actual gRPC to SpiceDB.
 */
public final class RealMatrixClient implements AutoCloseable {
    private final AuthxClient client;

    public static RealMatrixClient create(String[] targets, String presharedKey,
                                           boolean cacheEnabled, boolean coalescing,
                                           long cacheMaxSize, Duration ttl) {
        return create(targets, presharedKey, cacheEnabled, coalescing,
                cacheMaxSize, ttl, ReadConsistency.minimizeLatency());
    }

    public static RealMatrixClient create(String[] targets, String presharedKey,
                                           boolean cacheEnabled, boolean coalescing,
                                           long cacheMaxSize, Duration ttl,
                                           ReadConsistency consistency) {
        var client = AuthxClient.builder()
                .connection(c -> c
                        .targets(targets)
                        .presharedKey(presharedKey)
                        .requestTimeout(Duration.ofSeconds(30)))
                .cache(c -> c
                        .enabled(cacheEnabled)
                        .maxSize(cacheMaxSize)
                        .watchInvalidation(cacheEnabled))
                .features(f -> f
                        .coalescing(coalescing)
                        .virtualThreads(true)
                        .shutdownHook(false))
                .extend(e -> e.policies(PolicyRegistry.builder()
                        .defaultPolicy(ResourcePolicy.builder()
                                .cache(cacheEnabled ? CachePolicy.of(ttl) : CachePolicy.disabled())
                                .readConsistency(consistency)
                                .build())
                        .build()))
                .build();
        return new RealMatrixClient(client);
    }

    private RealMatrixClient(AuthxClient client) { this.client = client; }

    public boolean check(String resourceType, String resourceId, String permission, String userId) {
        return client.on(resourceType).check(resourceId, permission, userId);
    }

    public boolean check(String resourceType, String resourceId, String permission,
                          String userId, com.authx.sdk.model.Consistency consistency) {
        return client.on(resourceType).check(resourceId, permission, userId, consistency);
    }

    /** Grant a single relation (used by write-mix scenarios). */
    public void grant(String resourceType, String resourceId, String relation, String userId) {
        client.on(resourceType).grant(resourceId, relation, userId);
    }

    /** Grant a relation to an arbitrary subject ref (e.g. "folder:fld-3", "group:g-1#member"). */
    public void grantSubject(String resourceType, String resourceId, String relation, String subjectRef) {
        client.on(resourceType).grantToSubjects(resourceId, relation, subjectRef);
    }

    public CacheStats cacheStats() {
        var cache = client.cache();
        return cache != null ? cache.stats() : CacheStats.EMPTY;
    }

    public long cacheSize() {
        var cache = client.cache();
        return cache != null ? cache.size() : 0;
    }

    @Override public void close() {
        try { client.close(); } catch (Exception ignored) { }
    }
}
