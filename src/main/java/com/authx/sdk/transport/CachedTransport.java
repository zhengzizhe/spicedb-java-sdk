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

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        invalidateAffectedResources(updates);           // pre-invalidation (pessimistic)
        var result = delegate.writeRelationships(updates);
        invalidateAffectedResources(updates);           // post-invalidation (confirm)
        return result;
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        invalidateAffectedResources(updates);
        var result = delegate.deleteRelationships(updates);
        invalidateAffectedResources(updates);
        return result;
    }

    @Override
    public RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        String indexKey = resource.type() + ":" + resource.id();
        invalidateByResource(indexKey);
        var result = delegate.deleteByFilter(resource, subject, optionalRelation);
        invalidateByResource(indexKey);
        return result;
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
