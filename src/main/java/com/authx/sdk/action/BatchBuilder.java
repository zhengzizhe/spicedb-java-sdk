package com.authx.sdk.action;

import com.authx.sdk.model.BatchResult;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for batching multiple grant/revoke operations into a single RPC.
 */
public class BatchBuilder {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final List<RelationshipUpdate> updates = new ArrayList<>();

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public BatchBuilder(String resourceType, String resourceId, SdkTransport transport,
                        String defaultSubjectType) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
    }

    public BatchGrantAction grant(String... relations) {
        return new BatchGrantAction(this, resourceType, resourceId, defaultSubjectType, relations);
    }

    public BatchRevokeAction revoke(String... relations) {
        return new BatchRevokeAction(this, resourceType, resourceId, defaultSubjectType, relations);
    }

    void addUpdate(RelationshipUpdate update) {
        updates.add(update);
    }

    public BatchResult execute() {
        if (updates.isEmpty()) return new BatchResult(null);
        // Send all updates (TOUCH + DELETE) in a single writeRelationships call
        var r = transport.writeRelationships(updates);
        return new BatchResult(r.zedToken());
    }
}
