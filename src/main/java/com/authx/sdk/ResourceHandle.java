package com.authx.sdk;

import com.authx.sdk.action.*;
import com.authx.sdk.model.*;
import com.authx.sdk.transport.SdkTransport;

/**
 * A handle to a specific resource (e.g., document:doc-123).
 * All operations on this handle target this resource.
 * Thread-safe and stateless — safe to share across threads.
 */
public class ResourceHandle {

    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final java.util.concurrent.Executor asyncExecutor;

    ResourceHandle(String resourceType, String resourceId, SdkTransport transport, String defaultSubjectType) {
        this(resourceType, resourceId, transport, defaultSubjectType, Runnable::run);
    }

    ResourceHandle(String resourceType, String resourceId, SdkTransport transport,
                   String defaultSubjectType, java.util.concurrent.Executor asyncExecutor) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.asyncExecutor = asyncExecutor;
    }

    public String resourceType() { return resourceType; }
    public String resourceId() { return resourceId; }

    // ---- Grant ----

    public GrantAction grant(String... relations) {
        return new GrantAction(resourceType, resourceId, transport, defaultSubjectType, relations);
    }

    // ---- Revoke ----

    public RevokeAction revoke(String... relations) {
        return new RevokeAction(resourceType, resourceId, transport, defaultSubjectType, relations);
    }

    public RevokeAllAction revokeAll() {
        return new RevokeAllAction(resourceType, resourceId, transport, defaultSubjectType, null);
    }

    public RevokeAllAction revokeAll(String... relations) {
        return new RevokeAllAction(resourceType, resourceId, transport, defaultSubjectType, relations);
    }

    // ---- Check ----

    public CheckAction check(String permission) {
        return new CheckAction(resourceType, resourceId, transport, defaultSubjectType,
                asyncExecutor, new String[]{permission});
    }

    public CheckAllAction checkAll(String... permissions) {
        return new CheckAllAction(resourceType, resourceId, transport, defaultSubjectType, permissions);
    }

    // ---- Expand ----

    /**
     * Expand the permission tree — shows how a permission is computed through the relation graph.
     * Useful for debugging why a user does/doesn't have a permission.
     */
    public ExpandTree expand(String permission) {
        return transport.expand(
                ResourceRef.of(resourceType, resourceId),
                Permission.of(permission),
                Consistency.full());
    }

    // ---- Who ----

    public WhoBuilder who() {
        return new WhoBuilder(resourceType, resourceId, transport, defaultSubjectType, asyncExecutor);
    }

    // ---- Relations ----

    public RelationQuery relations(String... relations) {
        return new RelationQuery(resourceType, resourceId, transport, relations);
    }

    // ---- Batch ----

    public BatchBuilder batch() {
        return new BatchBuilder(resourceType, resourceId, transport, defaultSubjectType);
    }
}
