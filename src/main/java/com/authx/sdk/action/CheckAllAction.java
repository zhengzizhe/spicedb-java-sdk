package com.authx.sdk.action;

import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.PermissionMatrix;
import com.authx.sdk.model.PermissionSet;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent action for checking multiple permissions in a single bulk RPC.
 *
 * <p>Subjects come in as {@link SubjectRef} values or canonical strings.
 * The SDK does not assume a default subject type.
 */
public class CheckAllAction {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String[] permissions;
    private Consistency consistency = Consistency.minimizeLatency();

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public CheckAllAction(String resourceType, String resourceId, SdkTransport transport,
                          String[] permissions) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.permissions = permissions;
    }

    /** Override the consistency level for these checks. */
    public CheckAllAction withConsistency(Consistency consistency) {
        this.consistency = consistency;
        return this;
    }

    /**
     * Check all permissions for one {@link SubjectRef subject} in a single bulk RPC.
     * N permissions -> 1 gRPC call (not N sequential calls).
     */
    public PermissionSet by(SubjectRef subject) {
        java.util.List<com.authx.sdk.transport.SdkTransport.BulkCheckItem> items = Arrays.stream(permissions)
                .map(perm -> new SdkTransport.BulkCheckItem(
                        ResourceRef.of(resourceType, resourceId),
                        Permission.of(perm),
                        subject))
                .toList();
        List<CheckResult> results = transport.checkBulkMulti(items, consistency);
        Map<String, CheckResult> map = new LinkedHashMap<>();
        for (int i = 0; i < permissions.length; i++) {
            map.put(permissions[i], results.get(i));
        }
        return new PermissionSet(map);
    }

    /** Canonical-string form of {@link #by(SubjectRef)}. */
    public PermissionSet by(String subjectRef) {
        return by(SubjectRef.parse(subjectRef));
    }

    /**
     * Check all permissions against multiple {@link SubjectRef subjects},
     * returning a matrix keyed by the subject's canonical ref string.
     */
    public PermissionMatrix byAll(SubjectRef... subjects) {
        return byAll(Arrays.asList(subjects));
    }

    /** Collection overload of {@link #byAll(SubjectRef...)}. */
    public PermissionMatrix byAll(Collection<SubjectRef> subjects) {
        List<SubjectRef> list = subjects instanceof List
                ? (List<SubjectRef>) subjects
                : new ArrayList<>(subjects);
        List<SdkTransport.BulkCheckItem> items = new ArrayList<>(list.size() * permissions.length);
        for (SubjectRef sub : list) {
            for (String perm : permissions) {
                items.add(new SdkTransport.BulkCheckItem(
                        ResourceRef.of(resourceType, resourceId),
                        Permission.of(perm),
                        sub));
            }
        }
        List<CheckResult> results = transport.checkBulkMulti(items, consistency);

        Map<String, PermissionSet> matrix = new LinkedHashMap<>();
        int idx = 0;
        for (SubjectRef sub : list) {
            Map<String, CheckResult> permMap = new LinkedHashMap<>();
            for (String perm : permissions) {
                permMap.put(perm, results.get(idx++));
            }
            matrix.put(sub.toRefString(), new PermissionSet(permMap));
        }
        return new PermissionMatrix(matrix);
    }

    /** Canonical-string form of {@link #byAll(SubjectRef...)}. */
    public PermissionMatrix byAll(String... subjectRefs) {
        SubjectRef[] parsed = new SubjectRef[subjectRefs.length];
        for (int i = 0; i < subjectRefs.length; i++) parsed[i] = SubjectRef.parse(subjectRefs[i]);
        return byAll(parsed);
    }

    /** {@link Iterable} overload of {@link #byAll(String...)}. */
    public PermissionMatrix byAll(Iterable<String> subjectRefs) {
        List<SubjectRef> parsed = new ArrayList<>();
        for (String ref : subjectRefs) parsed.add(SubjectRef.parse(ref));
        return byAll(parsed);
    }
}
