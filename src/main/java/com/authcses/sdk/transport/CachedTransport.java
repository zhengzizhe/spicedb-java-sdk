package com.authcses.sdk.transport;

import com.authcses.sdk.cache.Cache;
import com.authcses.sdk.cache.IndexedCache;
import com.authcses.sdk.metrics.SdkMetrics;
import com.authcses.sdk.model.*;

import java.util.List;

/**
 * Wraps a SdkTransport with L1 check cache.
 * Only caches check() results. Write operations invalidate the cache for the affected resource.
 *
 * <p>If the cache implements {@link IndexedCache}, resource invalidation uses O(k)
 * {@code invalidateByIndex}. Otherwise falls back to O(n) predicate scan.
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

    @Override
    public CheckResult check(CheckRequest request) {
        boolean hasCaveat = request.caveatContext() != null && !request.caveatContext().isEmpty();

        if (!hasCaveat && request.consistency() instanceof Consistency.MinimizeLatency) {
            var key = request.toKey();
            var cached = cache.getIfPresent(key);
            if (cached != null) {
                if (metrics != null) metrics.recordCacheHit();
                return cached;
            }
            if (metrics != null) metrics.recordCacheMiss();
        }

        var result = delegate.check(request);

        if (!hasCaveat && request.consistency() instanceof Consistency.MinimizeLatency) {
            var key = request.toKey();
            cache.put(key, result);
        }
        if (metrics != null) metrics.updateCacheSize(cache.size());
        return result;
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        var result = delegate.writeRelationships(updates);
        invalidateAffectedResources(updates);
        return result;
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        var result = delegate.deleteRelationships(updates);
        invalidateAffectedResources(updates);
        return result;
    }

    @Override
    public RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        var result = delegate.deleteByFilter(resource, subject, optionalRelation);
        invalidateByResource(resource.type() + ":" + resource.id());
        return result;
    }

    @Override
    public void close() {
        cache.invalidateAll();
        delegate.close();
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
