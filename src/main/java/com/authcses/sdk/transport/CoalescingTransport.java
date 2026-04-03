package com.authcses.sdk.transport;

import com.authcses.sdk.metrics.SdkMetrics;
import com.authcses.sdk.model.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request coalescing: concurrent identical check() calls share one gRPC round-trip.
 * Key includes consistency level — different consistency requirements are NOT coalesced.
 *
 * <p>Only coalesces check() (most common hot-path). Other operations pass through.
 */
public class CoalescingTransport implements SdkTransport {

    private final SdkTransport delegate;
    private final SdkMetrics metrics;
    private final ConcurrentHashMap<String, CompletableFuture<CheckResult>> inflight = new ConcurrentHashMap<>();

    public CoalescingTransport(SdkTransport delegate, SdkMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    public CoalescingTransport(SdkTransport delegate) {
        this(delegate, null);
    }

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency) {
        String key = coalescingKey(resourceType, resourceId, permission, subjectType, subjectId, consistency);

        // Try to be the "owner" of this request
        CompletableFuture<CheckResult> myFuture = new CompletableFuture<>();
        CompletableFuture<CheckResult> existing = inflight.putIfAbsent(key, myFuture);

        if (existing != null) {
            // Another thread is already executing this exact request — wait for its result
            if (metrics != null) metrics.recordCoalesced();
            try {
                return existing.join();
            } catch (java.util.concurrent.CompletionException ce) {
                if (ce.getCause() instanceof RuntimeException re) throw re;
                throw ce;
            }
        }

        // We are the owner — execute the call, share result with all waiters
        try {
            var result = delegate.check(resourceType, resourceId, permission, subjectType, subjectId, consistency);
            myFuture.complete(result);
            return result;
        } catch (Exception e) {
            myFuture.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key, myFuture); // only remove if still ours
        }
    }

    @Override
    public BulkCheckResult checkBulk(String resourceType, String resourceId,
                                     String permission, List<String> subjectIds, String defaultSubjectType,
                                     Consistency consistency) {
        return delegate.checkBulk(resourceType, resourceId, permission, subjectIds, defaultSubjectType, consistency);
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        return delegate.writeRelationships(updates);
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        return delegate.deleteRelationships(updates);
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
        inflight.clear();
        delegate.close();
    }

    private static String coalescingKey(String resourceType, String resourceId,
                                        String permission, String subjectType, String subjectId,
                                        Consistency consistency) {
        String consistencyKey = switch (consistency) {
            case Consistency.MinimizeLatency ignored -> "min";
            case Consistency.Full ignored -> "full";
            case Consistency.AtLeast al -> "al:" + al.zedToken();
            case Consistency.AtExactSnapshot aes -> "aes:" + aes.zedToken();
        };
        return resourceType + ":" + resourceId + "#" + permission + "@" +
                subjectType + ":" + subjectId + "!" + consistencyKey;
    }
}
