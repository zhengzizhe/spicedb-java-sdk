package com.authx.sdk;

import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.RevokeResult;
import com.authx.sdk.transport.SdkTransport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Factory for a specific resource type. Two usage patterns:
 *
 * <p><b>Primary (typed chain via codegen):</b>
 * <pre>
 * DocumentResource doc = new DocumentResource(client);
 * doc.on("doc-1").grant(Document.Rel.EDITOR).toUser("bob");
 * doc.on("doc-1").check(Document.Perm.VIEW).by("alice");
 * </pre>
 *
 * <p><b>String fallback (dynamic cases):</b>
 * <pre>
 * ResourceFactory doc = client.on("document");
 * doc.check("doc-1", "view", "alice");
 * doc.grant("doc-1", "editor", "bob");
 * </pre>
 *
 * <p>For advanced operations (batch, expand, who, relations), use {@link #resource(String)}:
 * <pre>
 * doc.resource("doc-1").batch().grant("editor").to("bob").revoke("owner").from("old").execute();
 * doc.resource("doc-1").who().withPermission("view").fetch();
 * doc.resource("doc-1").expand("view");
 * </pre>
 *
 * Thread-safe — safe to store as a field and share across requests.
 */
public class ResourceFactory {

    private volatile String resourceType;
    private volatile SdkTransport transport;
    private volatile String defaultSubjectType;
    private volatile Executor asyncExecutor = Runnable::run;
    /**
     * Shared SchemaCache reference. Used by the typed action classes
     * (TypedGrantAction / TypedRevokeAction) to validate subject types at
     * runtime before issuing a write. Nullable — tests and in-memory clients
     * can operate without a schema, in which case validation is a no-op.
     */
    private volatile com.authx.sdk.cache.SchemaCache schemaCache;

    protected ResourceFactory() {}

    ResourceFactory(String resourceType, SdkTransport transport, String defaultSubjectType) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
    }

    ResourceFactory(String resourceType, SdkTransport transport, String defaultSubjectType,
                    Executor asyncExecutor) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.asyncExecutor = asyncExecutor;
    }

    ResourceFactory(String resourceType, SdkTransport transport, String defaultSubjectType,
                    Executor asyncExecutor, com.authx.sdk.cache.SchemaCache schemaCache) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.asyncExecutor = asyncExecutor;
        this.schemaCache = schemaCache;
    }

    void init(String resourceType, SdkTransport transport, String defaultSubjectType) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
    }

    void init(String resourceType, SdkTransport transport, String defaultSubjectType,
              Executor asyncExecutor) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.asyncExecutor = asyncExecutor;
    }

    void init(String resourceType, SdkTransport transport, String defaultSubjectType,
              Executor asyncExecutor, com.authx.sdk.cache.SchemaCache schemaCache) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.asyncExecutor = asyncExecutor;
        this.schemaCache = schemaCache;
    }

    /**
     * Package-private accessor for the schema cache, used by typed action
     * classes in the same package to validate subject types before writes.
     * {@code null}-safe — an absent cache means validation is skipped.
     */
    com.authx.sdk.cache.SchemaCache schemaCache() { return schemaCache; }

    /** Package-private accessor for the transport chain, used by typed action classes. */
    SdkTransport transport() { return transport; }

    /** Package-private accessor for the default subject type (usually "user"). */
    String defaultSubjectType() { return defaultSubjectType; }

    /** Package-private accessor for the async executor used by typed action classes. */
    Executor asyncExecutor() { return asyncExecutor; }

    // ---- Entry points ----

    /** Get a handle for advanced operations: batch, expand, who, relations. */
    public ResourceHandle resource(String id) {
        return new ResourceHandle(resourceType, id, transport, defaultSubjectType, asyncExecutor);
    }

    /** Reverse lookup: find all resources of this type a user can access. */
    public LookupQuery lookup() {
        return new LookupQuery(resourceType, transport, defaultSubjectType);
    }

    // ---- String-based operations (escape hatch for dynamic cases) ----

    /** Check permission. */
    public boolean check(String id, String permission, String userId) {
        return resource(id).check(permission).by(userId).hasPermission();
    }

    /** Check with explicit consistency. */
    public boolean check(String id, String permission, String userId, Consistency consistency) {
        return resource(id).check(permission).withConsistency(consistency).by(userId).hasPermission();
    }

    /** Grant relation to user(s). Returns result with zedToken for write-after-read consistency. */
    public GrantResult grant(String id, String relation, String... userIds) {
        return resource(id).grant(relation).to(userIds);
    }

    /** Grant relation to subject refs (e.g., "department:eng#all_members", "user:*"). */
    public GrantResult grantToSubjects(String id, String relation, String... subjectRefs) {
        return resource(id).grant(relation).toSubjects(subjectRefs);
    }

    /** Revoke relation from user(s). Returns result with zedToken for write-after-read consistency. */
    public RevokeResult revoke(String id, String relation, String... userIds) {
        return resource(id).revoke(relation).from(userIds);
    }

    /** Revoke relation from subject refs. */
    public RevokeResult revokeFromSubjects(String id, String relation, String... subjectRefs) {
        return resource(id).revoke(relation).fromSubjects(subjectRefs);
    }

    /** Get all relations grouped by relation name. */
    public Map<String, List<String>> allRelations(String id) {
        return resource(id).relations().groupByRelation();
    }

    /** Return the resource type this factory is bound to. */
    public String resourceType() {
        return resourceType;
    }
}
