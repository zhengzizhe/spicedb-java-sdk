package com.authx.sdk.action;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.BatchResult;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for batching multiple grant/revoke operations into a single RPC.
 */
public class BatchBuilder {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final @Nullable SchemaCache schemaCache;
    private final List<RelationshipUpdate> updates = new ArrayList<>();

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public BatchBuilder(String resourceType, String resourceId, SdkTransport transport) {
        this(resourceType, resourceId, transport, null);
    }

    /**
     * Internal — constructor used by {@link com.authx.sdk.ResourceHandle} when
     * schema-aware subject validation is available. When {@code schemaCache}
     * is {@code null} or empty, validation is skipped (fail-open) — behaves
     * exactly like the 3-arg constructor.
     */
    public BatchBuilder(String resourceType, String resourceId, SdkTransport transport,
                        @Nullable SchemaCache schemaCache) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.schemaCache = schemaCache;
    }

    /** Add grant operations for the given relations to this batch. */
    public BatchGrantAction grant(String... relations) {
        return new BatchGrantAction(this, resourceType, resourceId, relations, schemaCache);
    }

    /** Add revoke operations for the given relations to this batch. */
    public BatchRevokeAction revoke(String... relations) {
        return new BatchRevokeAction(this, resourceType, resourceId, relations, schemaCache);
    }

    void addUpdate(RelationshipUpdate update) {
        updates.add(update);
    }

    /** Execute all accumulated operations in a single atomic gRPC call. */
    public BatchResult execute() {
        if (updates.isEmpty()) return new BatchResult(null);
        // Send all updates (TOUCH + DELETE) in a single writeRelationships call
        var r = transport.writeRelationships(updates);
        return new BatchResult(r.zedToken());
    }
}
