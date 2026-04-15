package com.authx.clustertest.matrix;

import com.authx.sdk.cache.CacheStats;
import com.authx.sdk.cache.CaffeineCache;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport;

import java.util.List;

/**
 * Thin wrapper for custom-built transport chains (like DepthSimTransport +
 * CachedTransport + InMemory). Exposes the same {@code check/write/prime/cacheStats}
 * surface as {@link MatrixClient} so scenarios can share runner code.
 */
public final class MatrixAdapter implements AutoCloseable {
    private final SdkTransport top;
    private final InMemoryTransport inner;
    private final CaffeineCache<CheckKey, CheckResult> cache;

    public MatrixAdapter(SdkTransport top, InMemoryTransport inner,
                          CaffeineCache<CheckKey, CheckResult> cache) {
        this.top = top; this.inner = inner; this.cache = cache;
    }

    public void prime(int n) {
        var updates = new java.util.ArrayList<SdkTransport.RelationshipUpdate>(n);
        for (int i = 0; i < n; i++) {
            updates.add(new SdkTransport.RelationshipUpdate(
                    SdkTransport.RelationshipUpdate.Operation.TOUCH,
                    ResourceRef.of("doc", "primed-" + i),
                    Relation.of("viewer"),
                    SubjectRef.of("user", "u-" + i, null)));
        }
        int chunk = 1000;
        for (int i = 0; i < updates.size(); i += chunk) {
            inner.writeRelationships(updates.subList(i, Math.min(i + chunk, updates.size())));
        }
    }

    public boolean check(String docId, String permission, String userId) {
        var req = new CheckRequest(
                ResourceRef.of("doc", docId),
                Permission.of(permission),
                SubjectRef.of("user", userId, null),
                Consistency.minimizeLatency(),
                null);
        var r = top.check(req);
        return r != null && r.hasPermission();
    }

    public CacheStats cacheStats() {
        return cache != null ? cache.stats() : CacheStats.EMPTY;
    }

    @Override public void close() {
        try { top.close(); } catch (Exception ignored) { }
    }
}
