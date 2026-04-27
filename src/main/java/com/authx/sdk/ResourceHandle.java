package com.authx.sdk;

import com.authx.sdk.action.BatchBuilder;
import com.authx.sdk.action.CheckAction;
import com.authx.sdk.action.CheckAllAction;
import com.authx.sdk.action.GrantAction;
import com.authx.sdk.action.RelationQuery;
import com.authx.sdk.action.RevokeAction;
import com.authx.sdk.action.RevokeAllAction;
import com.authx.sdk.action.WhoBuilder;
import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.ExpandTree;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.transport.SdkTransport;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

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
    private final Executor asyncExecutor;
    private final @Nullable SchemaCache schemaCache;

    ResourceHandle(String resourceType, String resourceId, SdkTransport transport) {
        this(resourceType, resourceId, transport, Runnable::run, null);
    }

    ResourceHandle(String resourceType, String resourceId, SdkTransport transport,
                   Executor asyncExecutor) {
        this(resourceType, resourceId, transport, asyncExecutor, null);
    }

    ResourceHandle(String resourceType, String resourceId, SdkTransport transport,
                   Executor asyncExecutor, @Nullable SchemaCache schemaCache) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.asyncExecutor = asyncExecutor;
        this.schemaCache = schemaCache;
    }

    /** Return the resource type (e.g., {@code "document"}). */
    public String resourceType() { return resourceType; }

    /** Return the resource id (e.g., {@code "doc-123"}). */
    public String resourceId() { return resourceId; }

    // ---- Grant ----

    /** Start a grant action to add one or more relations on this resource. */
    public GrantAction grant(String... relations) {
        return new GrantAction(resourceType, resourceId, transport, relations, schemaCache);
    }

    // ---- Revoke ----

    /** Start a revoke action to remove specific relations from subjects. */
    public RevokeAction revoke(String... relations) {
        return new RevokeAction(resourceType, resourceId, transport, relations, schemaCache);
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
        return new CheckAction(resourceType, resourceId, transport,
                asyncExecutor, new String[]{permission});
    }

    /** Check multiple permissions in a single bulk RPC. */
    public CheckAllAction checkAll(String... permissions) {
        return new CheckAllAction(resourceType, resourceId, transport, permissions);
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

    /**
     * Start a subject lookup query ("who of this type has X on this resource?").
     *
     * @param subjectType the subject object type to look up (required by
     *                    SpiceDB's LookupSubjects RPC).
     */
    public WhoBuilder who(String subjectType) {
        return new WhoBuilder(resourceType, resourceId, transport, subjectType, asyncExecutor);
    }

    /**
     * Typed version of {@link #who(String)} — takes a {@link ResourceType}
     * descriptor (e.g. {@code User}) so business code can avoid
     * hand-writing the type name string.
     */
    public <R extends Enum<R> & Relation.Named,
            P extends Enum<P> & Permission.Named>
    WhoBuilder who(ResourceType<R, P> subjectType) {
        return who(subjectType.name());
    }

    // ---- Relations ----

    /** Read relationships on this resource, optionally filtered by relation names. */
    public RelationQuery relations(String... relations) {
        return new RelationQuery(resourceType, resourceId, transport, relations);
    }

    // ---- Batch ----

    /** Start a batch builder to combine multiple grant/revoke operations into a single RPC. */
    public BatchBuilder batch() {
        return new BatchBuilder(resourceType, resourceId, transport, schemaCache);
    }
}
