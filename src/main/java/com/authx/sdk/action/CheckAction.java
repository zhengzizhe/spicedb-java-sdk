package com.authx.sdk.action;

import com.authx.sdk.model.BulkCheckResult;
import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** Override the consistency level for this check. */
    public CheckAction withConsistency(Consistency consistency) {
        this.consistency = consistency;
        return this;
    }

    /** Caveat context for conditional permissions (e.g., IP range, time). */
    public CheckAction withContext(Map<String, Object> context) {
        this.context = context;
        return this;
    }

    /** Caveat context from alternating key-value pairs, e.g. {@code withContext(IpAllowlist.CLIENT_IP, "10.0.0.5")}. */
    public CheckAction withContext(Object... keyValues) {
        this.context = toMap(keyValues);
        return this;
    }

    private static Map<String, Object> toMap(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must have even length");
        }
        var map = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            if (!(kv[i] instanceof String key)) {
                throw new IllegalArgumentException("Key at index " + i + " must be a String");
            }
            map.put(key, kv[i + 1]);
        }
        return map;
    }

    /** Execute the permission check against a single user id. */
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

    /** Check the permission against multiple user ids in a single bulk RPC. */
    public BulkCheckResult byAll(String... userIds) {
        return byAll(Arrays.asList(userIds));
    }

    /** Check the permission against multiple user ids in a single bulk RPC. */
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
