package com.authcses.sdk.transport;

import com.authcses.sdk.cache.CheckCache;
import com.authcses.sdk.metrics.SdkMetrics;
import com.authcses.sdk.model.*;

import java.util.List;

/**
 * Wraps a SdkTransport with L1 check cache.
 * Only caches check() results. Write operations invalidate the cache for the affected resource.
 */
public class CachedTransport extends ForwardingTransport {

    private final SdkTransport delegate;
    private final CheckCache cache;
    private final SdkMetrics metrics;

    public CachedTransport(SdkTransport delegate, CheckCache cache, SdkMetrics metrics) {
        this.delegate = delegate;
        this.cache = cache;
        this.metrics = metrics;
    }

    public CachedTransport(SdkTransport delegate, CheckCache cache) {
        this(delegate, cache, null);
    }

    @Override
    protected SdkTransport delegate() {
        return delegate;
    }

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency) {
        if (consistency instanceof Consistency.MinimizeLatency) {
            var cached = cache.get(resourceType, resourceId, permission, subjectType, subjectId);
            if (cached.isPresent()) {
                if (metrics != null) metrics.recordCacheHit();
                return cached.get();
            }
            if (metrics != null) metrics.recordCacheMiss();
        }

        var result = delegate.check(resourceType, resourceId, permission, subjectType, subjectId, consistency);

        if (consistency instanceof Consistency.MinimizeLatency) {
            cache.put(resourceType, resourceId, permission, subjectType, subjectId, result);
        }
        if (metrics != null) metrics.updateCacheSize(cache.size());
        return result;
    }

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency, java.util.Map<String, Object> context) {
        // Context-aware checks bypass cache — different contexts produce different results
        return delegate.check(resourceType, resourceId, permission, subjectType, subjectId, consistency, context);
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
    public RevokeResult deleteByFilter(String resourceType, String resourceId,
                                        String subjectType, String subjectId,
                                        String optionalRelation) {
        var result = delegate.deleteByFilter(resourceType, resourceId, subjectType, subjectId, optionalRelation);
        cache.invalidateResource(resourceType, resourceId);
        return result;
    }

    @Override
    public void close() {
        cache.invalidateAll();
        delegate.close();
    }

    private void invalidateAffectedResources(List<RelationshipUpdate> updates) {
        record ResourceKey(String type, String id) {}
        updates.stream()
                .map(u -> new ResourceKey(u.resourceType(), u.resourceId()))
                .distinct()
                .forEach(k -> cache.invalidateResource(k.type, k.id));
    }
}
