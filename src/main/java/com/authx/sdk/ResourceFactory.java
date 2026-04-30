package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.transport.SdkTransport;
import org.jspecify.annotations.Nullable;

/**
 * Internal holder for resource-type scoped SDK dependencies.
 */
class ResourceFactory {

    private volatile String resourceType;
    private volatile SdkTransport transport;
    private volatile @Nullable SchemaCache schemaCache;

    ResourceFactory(String resourceType, SdkTransport transport) {
        this.resourceType = resourceType;
        this.transport = transport;
    }

    ResourceFactory(String resourceType, SdkTransport transport, @Nullable SchemaCache schemaCache) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.schemaCache = schemaCache;
    }

    /** Package-private accessor for the transport chain, used by typed action classes. */
    SdkTransport transport() { return transport; }

    /**
     * Package-private accessor for the schema cache (may be {@code null}).
     * Used by flow / action classes that need schema-aware subject validation
     * (e.g. {@link WriteFlow} passes every accumulated subject through
     * {@link com.authx.sdk.cache.SchemaCache#validateSubject} before commit).
     */
    @Nullable SchemaCache schemaCache() { return schemaCache; }

    String resourceType() {
        return resourceType;
    }
}
