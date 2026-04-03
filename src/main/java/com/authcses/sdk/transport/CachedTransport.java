package com.authcses.sdk.transport;

import com.authcses.sdk.cache.CheckCache;
import com.authcses.sdk.metrics.SdkMetrics;
import com.authcses.sdk.model.*;

import java.util.List;

/**
 * Wraps a SdkTransport with L1 check cache.
 * Only caches check() results. Write operations invalidate the cache for the affected resource.
 */
public class CachedTransport implements SdkTransport {

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
    public BulkCheckResult checkBulk(String resourceType, String resourceId,
                                     String permission, List<String> subjectIds, String defaultSubjectType,
                                     Consistency consistency) {
        // No caching for bulk — pass through (could be optimized later)
        return delegate.checkBulk(resourceType, resourceId, permission, subjectIds, defaultSubjectType, consistency);
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
    public List<Tuple> readRelationships(String resourceType, String resourceId,
                                          String relation, Consistency consistency) {
        return delegate.readRelationships(resourceType, resourceId, relation, consistency);
    }

    @Override
    public List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency) {
        return delegate.lookupSubjects(resourceType, resourceId, permission, subjectType, consistency);
    }

    @Override
    public List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency) {
        return delegate.lookupResources(resourceType, permission, subjectType, subjectId, consistency);
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
