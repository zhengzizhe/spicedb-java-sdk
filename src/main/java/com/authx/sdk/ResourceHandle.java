package com.authx.sdk;

import com.authx.sdk.action.BatchBuilder;
import com.authx.sdk.action.CheckAction;
import com.authx.sdk.action.CheckAllAction;
import com.authx.sdk.action.GrantAction;
import com.authx.sdk.action.RelationQuery;
import com.authx.sdk.action.RevokeAction;
import com.authx.sdk.action.RevokeAllAction;
import com.authx.sdk.action.WhoBuilder;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.ExpandTree;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.transport.SdkTransport;

import java.util.concurrent.Executor;

/**
 * A handle to a specific resource instance (e.g., {@code document:doc-123}).
 *
 * <p>All operations on this handle target the bound resource type and id.
 * Obtain a handle via {@link ResourceFactory#resource(String)} or
 * {@link AuthxClient#resource(String, String)}.
 *
 * <pre>
 * ResourceHandle doc = client.on("document").resource("doc-123");
 * doc.grant("editor").to("alice");
 * doc.check("view").by("bob").hasPermission();
 * doc.who().withPermission("view").fetch();
 * </pre>
 *
 * Thread-safe and stateless — safe to share across threads.
 */
public class ResourceHandle {

    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final Executor asyncExecutor;

    ResourceHandle(String resourceType, String resourceId, SdkTransport transport, String defaultSubjectType) {
        this(resourceType, resourceId, transport, defaultSubjectType, Runnable::run);
    }

    ResourceHandle(String resourceType, String resourceId, SdkTransport transport,
                   String defaultSubjectType, Executor asyncExecutor) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.asyncExecutor = asyncExecutor;
    }

    /** Return the resource type (e.g., {@code "document"}). */
    public String resourceType() { return resourceType; }

    /** Return the resource id (e.g., {@code "doc-123"}). */
    public String resourceId() { return resourceId; }

    // ---- Grant ----

    /** Start a grant action to add one or more relations on this resource. */
    public GrantAction grant(String... relations) {
        return new GrantAction(resourceType, resourceId, transport, relations);
    }

    // ---- Revoke ----

    /** Start a revoke action to remove specific relations from subjects. */
    public RevokeAction revoke(String... relations) {
        return new RevokeAction(resourceType, resourceId, transport, relations);
    }

    /** Revoke all relationships on this resource using filter-based delete. */
    public RevokeAllAction revokeAll() {
        return new RevokeAllAction(resourceType, resourceId, transport, null);
    }

    /** Revoke all relationships for the given relations using filter-based delete. */
    public RevokeAllAction revokeAll(String... relations) {
        return new RevokeAllAction(resourceType, resourceId, transport, relations);
    }

    // ---- Check ----

    /** Start a permission check for a single permission on this resource. */
    public CheckAction check(String permission) {
        return new CheckAction(resourceType, resourceId, transport, defaultSubjectType,
                asyncExecutor, new String[]{permission});
    }

    /** Check multiple permissions in a single bulk RPC. */
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

    /** Start a subject lookup query ("who has X on this resource?"). */
    public WhoBuilder who() {
        return new WhoBuilder(resourceType, resourceId, transport, defaultSubjectType, asyncExecutor);
    }

    // ---- Relations ----

    /** Read relationships on this resource, optionally filtered by relation names. */
    public RelationQuery relations(String... relations) {
        return new RelationQuery(resourceType, resourceId, transport, relations);
    }

    // ---- Batch ----

    /** Start a batch builder to combine multiple grant/revoke operations into a single RPC. */
    public BatchBuilder batch() {
        return new BatchBuilder(resourceType, resourceId, transport);
    }
}
