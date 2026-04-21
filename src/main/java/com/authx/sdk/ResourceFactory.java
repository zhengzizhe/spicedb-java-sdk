package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.RevokeResult;
import com.authx.sdk.transport.SdkTransport;

import org.jspecify.annotations.Nullable;

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
    private volatile Executor asyncExecutor = Runnable::run;
    private volatile @Nullable SchemaCache schemaCache;

    protected ResourceFactory() {}

    ResourceFactory(String resourceType, SdkTransport transport) {
        this.resourceType = resourceType;
        this.transport = transport;
    }

    ResourceFactory(String resourceType, SdkTransport transport, Executor asyncExecutor) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.asyncExecutor = asyncExecutor;
    }

    ResourceFactory(String resourceType, SdkTransport transport, Executor asyncExecutor,
                    @Nullable SchemaCache schemaCache) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.asyncExecutor = asyncExecutor;
        this.schemaCache = schemaCache;
    }

    void init(String resourceType, SdkTransport transport) {
        this.resourceType = resourceType;
        this.transport = transport;
    }

    void init(String resourceType, SdkTransport transport, Executor asyncExecutor) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.asyncExecutor = asyncExecutor;
    }

    void init(String resourceType, SdkTransport transport, Executor asyncExecutor,
              @Nullable SchemaCache schemaCache) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.asyncExecutor = asyncExecutor;
        this.schemaCache = schemaCache;
    }

    /** Package-private accessor for the transport chain, used by typed action classes. */
    SdkTransport transport() { return transport; }

    /** Package-private accessor for the async executor used by typed action classes. */
    Executor asyncExecutor() { return asyncExecutor; }

    /**
     * Package-private accessor for the schema cache (may be {@code null}).
     * Used by typed action classes that need schema-aware subject inference
     * (e.g. {@link TypedGrantAction#to(String)} bare-id single-type inference).
     */
    @Nullable SchemaCache schemaCache() { return schemaCache; }

    // ---- Entry points ----

    /** Get a handle for advanced operations: batch, expand, who, relations. */
    public ResourceHandle resource(String id) {
        return new ResourceHandle(resourceType, id, transport, asyncExecutor, schemaCache);
    }

    /** Reverse lookup: find all resources of this type a subject can access. */
    public LookupQuery lookup() {
        return new LookupQuery(resourceType, transport);
    }

    // ---- String-based operations (escape hatch for dynamic cases) ----

    /**
     * Check permission for a canonical subject string ({@code "user:alice"},
     * {@code "group:eng#member"}).
     */
    public boolean check(String id, String permission, String subjectRef) {
        return resource(id).check(permission).by(subjectRef).hasPermission();
    }

    /** Check with explicit consistency. */
    public boolean check(String id, String permission, String subjectRef, Consistency consistency) {
        return resource(id).check(permission).withConsistency(consistency).by(subjectRef).hasPermission();
    }

    /**
     * Grant relation to canonical subject refs
     * (e.g., {@code "user:alice"}, {@code "group:eng#member"}, {@code "user:*"}).
     * Returns result with zedToken for write-after-read consistency.
     */
    public GrantResult grant(String id, String relation, String... subjectRefs) {
        return resource(id).grant(relation).to(subjectRefs);
    }

    /** Revoke relation from canonical subject refs. */
    public RevokeResult revoke(String id, String relation, String... subjectRefs) {
        return resource(id).revoke(relation).from(subjectRefs);
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
