package com.authx.sdk.transport;

import com.authx.sdk.cache.Cache;
import com.authx.sdk.cache.IndexedCache;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.*;

import java.util.List;

/**
 * Wraps a SdkTransport with L1 check cache.
 * Only caches check() results. Write operations invalidate the cache for the affected resource.
 *
 * <p>If the cache implements {@link IndexedCache}, resource invalidation uses O(k)
 * {@code invalidateByIndex}. Otherwise falls back to O(n) predicate scan.
 *
 * <p>Uses singleFlight via {@link Cache#getOrLoad} — concurrent cache misses for the same key
 * result in only one gRPC call.
 */
public class CachedTransport extends ForwardingTransport {

    private final SdkTransport delegate;
    private final Cache<CheckKey, CheckResult> cache;
    private final SdkMetrics metrics;

    public CachedTransport(SdkTransport delegate, Cache<CheckKey, CheckResult> cache, SdkMetrics metrics) {
        this.delegate = delegate;
        this.cache = cache;
        this.metrics = metrics;
    }

    public CachedTransport(SdkTransport delegate, Cache<CheckKey, CheckResult> cache) {
        this(delegate, cache, null);
    }

    @Override
    protected SdkTransport delegate() {
        return delegate;
    }

    /**
     * Check with caching. Only MinimizeLatency requests without caveats are cached.
     *
     * <p>Design: PolicyAwareConsistencyTransport sits ABOVE this layer in the chain.
     * If the user's policy is SESSION and a write token exists, the consistency has
     * already been upgraded to AtLeast(token) before reaching here — which correctly
     * bypasses the cache (write-after-read must see fresh data). If no write token
     * exists, consistency remains MinimizeLatency and the cache is used normally.
     *
     * <p>Uses Caffeine's singleFlight via {@code getOrLoad}: concurrent cache misses
     * for the same key result in only one gRPC call to SpiceDB.
     */
    @Override
    public CheckResult check(CheckRequest request) {
        boolean hasCaveat = request.caveatContext() != null && !request.caveatContext().isEmpty();

        if (!hasCaveat && request.consistency() instanceof Consistency.MinimizeLatency) {
            var key = request.toKey();
            var result = cache.getOrLoad(key, k -> delegate.check(request));
            if (result != null) return result;
        }

        return delegate.check(request);
    }

    /**
     * Double-delete pattern (Facebook TAO / Hibernate L2 / Spring Cache):
     * invalidate BEFORE the write so concurrent reads during the write don't
     * see the soon-to-be-stale value, AND invalidate AFTER the write to clean
     * up any entry that a concurrent reader managed to populate while the
     * write was in flight. Without the second invalidation, a read scheduled
     * between the pre-invalidation and the write completion can fetch the
     * old value from SpiceDB and cache it for the full TTL window.
     *
     * <p>The post-invalidate runs in {@code finally} so partial writes (where
     * SpiceDB persisted some updates before throwing) still purge the cache
     * — better to refetch than serve stale data.
     */
    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        invalidateAffectedResources(updates);           // pre-invalidate
        try {
            return delegate.writeRelationships(updates);
        } finally {
            invalidateAffectedResources(updates);       // post-invalidate (close race)
        }
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        invalidateAffectedResources(updates);
        try {
            return delegate.deleteRelationships(updates);
        } finally {
            invalidateAffectedResources(updates);
        }
    }

    @Override
    public RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        String indexKey = resource.type() + ":" + resource.id();
        invalidateByResource(indexKey);
        try {
            return delegate.deleteByFilter(resource, subject, optionalRelation);
        } finally {
            invalidateByResource(indexKey);
        }
    }

    @Override
    public void close() {
        cache.invalidateAll();
        delegate.close();
    }

    /** Expose cache for size sampling by metrics (not on hot path). */
    public long cacheSize() {
        return cache.size();
    }

    private void invalidateAffectedResources(List<RelationshipUpdate> updates) {
        updates.stream()
                .map(u -> u.resource().type() + ":" + u.resource().id())
                .distinct()
                .forEach(this::invalidateByResource);
    }

    private void invalidateByResource(String indexKey) {
        if (cache instanceof IndexedCache<CheckKey, CheckResult> indexed) {
            indexed.invalidateByIndex(indexKey);
        } else {
            cache.invalidateAll(key -> indexKey.equals(key.resourceIndex()));
        }
    }
}
