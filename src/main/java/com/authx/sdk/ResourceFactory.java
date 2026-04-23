package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.transport.SdkTransport;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.Executor;

/**
 * Factory for a specific resource type. All operations go through a
 * chain that starts with {@link #resource(String)} or {@link #lookup()}:
 *
 * <p><b>Typed chain (preferred):</b>
 * <pre>
 * client.on(Document).select("doc-1")
 *     .grant(Document.Rel.EDITOR).to(User, "bob").commit();
 * client.on(Document).select("doc-1")
 *     .check(Document.Perm.VIEW).by(User, "alice");
 * </pre>
 *
 * <p><b>Untyped chain (dynamic / string-driven cases):</b>
 * <pre>
 * ResourceFactory doc = client.on("document");
 * doc.resource("doc-1").grant("editor").to("user:bob");
 * doc.resource("doc-1").check("view").by("user:alice").hasPermission();
 * doc.resource("doc-1").batch().grant("editor").to("bob").revoke("owner").from("old").execute();
 * doc.resource("doc-1").who().withPermission("view").fetch();
 * doc.resource("doc-1").expand("view");
 * </pre>
 *
 * <p>Thread-safe — safe to store as a field and share across requests.
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
     * Used by flow / action classes that need schema-aware subject validation
     * (e.g. {@link GrantFlow} passes every accumulated subject through
     * {@link com.authx.sdk.cache.SchemaCache#validateSubject} before commit).
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

    /** Return the resource type this factory is bound to. */
    public String resourceType() {
        return resourceType;
    }
}
