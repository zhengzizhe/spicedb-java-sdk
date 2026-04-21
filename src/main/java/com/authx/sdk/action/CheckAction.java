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
 *
 * <p>Subjects come in as {@link SubjectRef} values or canonical strings
 * ({@code "user:alice"}, {@code "group:eng#member"}, {@code "user:*"}).
 * The SDK does not assume a default subject type.
 */
public class CheckAction {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final Executor asyncExecutor;
    private final String[] permissions;
    private Consistency consistency = Consistency.minimizeLatency();
    private Map<String, Object> context;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public CheckAction(String resourceType, String resourceId, SdkTransport transport,
                       Executor asyncExecutor, String[] permissions) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
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

    /** Alias for {@link #withContext(Map)} — reads as "check access given ...". */
    public CheckAction given(Map<String, Object> context) { return withContext(context); }

    /** Alias for {@link #withContext(Object...)} — reads as "check access given CLIENT_IP, ip". */
    public CheckAction given(Object... keyValues) { return withContext(keyValues); }

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

    /** Execute the permission check against a single {@link SubjectRef subject}. */
    public CheckResult by(SubjectRef subject) {
        var request = new CheckRequest(
                ResourceRef.of(resourceType, resourceId),
                Permission.of(permissions[0]),
                subject,
                consistency,
                context);
        return transport.check(request);
    }

    /** Execute the permission check against a canonical subject string ({@code "user:alice"}, ...). */
    public CheckResult by(String subjectRef) {
        return by(SubjectRef.parse(subjectRef));
    }

    /** Async version. Uses the SDK's configured executor instead of ForkJoinPool.commonPool. */
    public CompletableFuture<CheckResult> byAsync(SubjectRef subject) {
        return CompletableFuture.supplyAsync(() -> by(subject), asyncExecutor);
    }

    /** Async version — canonical subject string form. */
    public CompletableFuture<CheckResult> byAsync(String subjectRef) {
        return byAsync(SubjectRef.parse(subjectRef));
    }

    /** Check the permission against multiple {@link SubjectRef subjects} in a single bulk RPC. */
    public BulkCheckResult byAll(SubjectRef... subjects) {
        return byAll(Arrays.asList(subjects));
    }

    /** Collection overload of {@link #byAll(SubjectRef...)}. */
    public BulkCheckResult byAll(Collection<SubjectRef> subjects) {
        SubjectRef head = subjects.iterator().next();
        var request = CheckRequest.of(
                ResourceRef.of(resourceType, resourceId),
                Permission.of(permissions[0]),
                head,
                consistency);
        return transport.checkBulk(request, List.copyOf(subjects));
    }

    /** Check the permission against multiple canonical subject strings in a single bulk RPC. */
    public BulkCheckResult byAll(String... subjectRefs) {
        SubjectRef[] parsed = new SubjectRef[subjectRefs.length];
        for (int i = 0; i < subjectRefs.length; i++) parsed[i] = SubjectRef.parse(subjectRefs[i]);
        return byAll(parsed);
    }
}
