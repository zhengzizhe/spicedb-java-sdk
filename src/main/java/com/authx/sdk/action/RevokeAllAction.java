package com.authx.sdk.action;

import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.RevokeResult;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;

/**
 * Fluent action for revoking all matching relationships using filter-based delete.
 */
public class RevokeAllAction {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final String[] relations;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public RevokeAllAction(String resourceType, String resourceId, SdkTransport transport,
                           String defaultSubjectType, String[] relations) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.relations = relations;
    }

    /** Revoke all matching relationships from the given user ids using filter-based delete. */
    public RevokeResult from(String... userIds) {
        return from(java.util.Arrays.asList(userIds));
    }

    /**
     * Atomically delete all matching relationships using filter-based delete.
     * No TOCTOU race — does NOT read-then-delete.
     */
    public RevokeResult from(java.util.Collection<String> userIds) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        int totalDeleted = 0;
        String lastToken = null;

        for (String uid : userIds) {
            SubjectRef subject = SubjectRef.of(defaultSubjectType, uid, null);
            if (relations == null || relations.length == 0) {
                // Delete ALL relations for this subject on this resource
                var result = transport.deleteByFilter(resource, subject, null);
                totalDeleted += result.count();
                if (result.zedToken() != null) lastToken = result.zedToken();
            } else {
                for (String rel : relations) {
                    var result = transport.deleteByFilter(resource, subject, Relation.of(rel));
                    totalDeleted += result.count();
                    if (result.zedToken() != null) lastToken = result.zedToken();
                }
            }
        }

        return new RevokeResult(lastToken, totalDeleted);
    }
}
