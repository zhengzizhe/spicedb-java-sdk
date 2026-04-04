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
    public CheckResult check(CheckRequest request) {
        boolean hasCaveat = request.caveatContext() != null && !request.caveatContext().isEmpty();

        if (!hasCaveat && request.consistency() instanceof Consistency.MinimizeLatency) {
            var key = request.toKey();
            var cached = cache.get(key.resource().type(), key.resource().id(),
                    key.permission().name(), key.subject().type(), key.subject().id());
            if (cached.isPresent()) {
                if (metrics != null) metrics.recordCacheHit();
                return cached.get();
            }
            if (metrics != null) metrics.recordCacheMiss();
        }

        var result = delegate.check(request);

        if (!hasCaveat && request.consistency() instanceof Consistency.MinimizeLatency) {
            var key = request.toKey();
            cache.put(key.resource().type(), key.resource().id(),
                    key.permission().name(), key.subject().type(), key.subject().id(), result);
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
        cache.invalidateResource(resource.type(), resource.id());
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
                .map(u -> new ResourceKey(u.resource().type(), u.resource().id()))
                .distinct()
                .forEach(k -> cache.invalidateResource(k.type, k.id));
    }
}
