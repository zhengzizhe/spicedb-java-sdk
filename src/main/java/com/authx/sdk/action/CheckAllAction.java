package com.authx.sdk.action;

import com.authx.sdk.model.*;
import com.authx.sdk.transport.SdkTransport;

import java.util.*;

/**
 * Fluent action for checking multiple permissions in a single bulk RPC.
 */
public class CheckAllAction {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final String[] permissions;
    private Consistency consistency = Consistency.minimizeLatency();

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public CheckAllAction(String resourceType, String resourceId, SdkTransport transport,
                          String defaultSubjectType, String[] permissions) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.permissions = permissions;
    }

    public CheckAllAction withConsistency(Consistency consistency) {
        this.consistency = consistency;
        return this;
    }

    /**
     * Check all permissions for one user in a single bulk RPC.
     * N permissions -> 1 gRPC call (not N sequential calls).
     */
    public PermissionSet by(String userId) {
        var items = Arrays.stream(permissions)
                .map(perm -> new SdkTransport.BulkCheckItem(
                        ResourceRef.of(resourceType, resourceId),
                        Permission.of(perm),
                        SubjectRef.of(defaultSubjectType, userId, null)))
                .toList();
        List<CheckResult> results = transport.checkBulkMulti(items, consistency);
        Map<String, CheckResult> map = new LinkedHashMap<>();
        for (int i = 0; i < permissions.length; i++) {
            map.put(permissions[i], results.get(i));
        }
        return new PermissionSet(map);
    }

    public PermissionMatrix byAll(String... userIds) {
        return byAll(Arrays.asList(userIds));
    }

    public PermissionMatrix byAll(Collection<String> userIds) {
        List<String> uidList = userIds instanceof List ? (List<String>) userIds : new ArrayList<>(userIds);
        // Build all (user x permission) items in one flat list
        List<SdkTransport.BulkCheckItem> items = new ArrayList<>(uidList.size() * permissions.length);
        for (String uid : uidList) {
            for (String perm : permissions) {
                items.add(new SdkTransport.BulkCheckItem(
                        ResourceRef.of(resourceType, resourceId),
                        Permission.of(perm),
                        SubjectRef.of(defaultSubjectType, uid, null)));
            }
        }

        List<CheckResult> results = transport.checkBulkMulti(items, consistency);

        // Unpack flat results back into matrix[user][permission]
        Map<String, PermissionSet> matrix = new LinkedHashMap<>();
        int idx = 0;
        for (String uid : uidList) {
            Map<String, CheckResult> permMap = new LinkedHashMap<>();
            for (String perm : permissions) {
                permMap.put(perm, results.get(idx++));
            }
            matrix.put(uid, new PermissionSet(permMap));
        }
        return new PermissionMatrix(matrix);
    }
}
