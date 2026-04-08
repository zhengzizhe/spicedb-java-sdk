package com.authx.sdk.action;

import com.authx.sdk.model.*;
import com.authx.sdk.transport.SdkTransport;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Fluent action for checking a single permission against one or more subjects.
 */
public class CheckAction {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final Executor asyncExecutor;
    private final String[] permissions;
    private Consistency consistency = Consistency.minimizeLatency();
    private Map<String, Object> context;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public CheckAction(String resourceType, String resourceId, SdkTransport transport,
                       String defaultSubjectType, Executor asyncExecutor, String[] permissions) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.asyncExecutor = asyncExecutor;
        this.permissions = permissions;
    }

    public CheckAction withConsistency(Consistency consistency) {
        this.consistency = consistency;
        return this;
    }

    /** Caveat context for conditional permissions (e.g., IP range, time). */
    public CheckAction withContext(Map<String, Object> context) {
        this.context = context;
        return this;
    }

    public CheckResult by(String userId) {
        var request = new CheckRequest(
                ResourceRef.of(resourceType, resourceId),
                Permission.of(permissions[0]),
                SubjectRef.of(defaultSubjectType, userId, null),
                consistency,
                context);
        return transport.check(request);
    }

    /** Async version. Uses the SDK's configured executor instead of ForkJoinPool.commonPool. */
    public CompletableFuture<CheckResult> byAsync(String userId) {
        return CompletableFuture.supplyAsync(() -> by(userId), asyncExecutor);
    }

    public BulkCheckResult byAll(String... userIds) {
        return byAll(Arrays.asList(userIds));
    }

    public BulkCheckResult byAll(Collection<String> userIds) {
        var request = CheckRequest.of(
                ResourceRef.of(resourceType, resourceId),
                Permission.of(permissions[0]),
                SubjectRef.of(defaultSubjectType, "", null),
                consistency);
        List<SubjectRef> subjects = userIds.stream()
                .map(uid -> SubjectRef.of(defaultSubjectType, uid, null))
                .toList();
        return transport.checkBulk(request, subjects);
    }
}
