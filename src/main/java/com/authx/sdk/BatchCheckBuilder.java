package com.authx.sdk;

import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-resource batch permission check — send many arbitrary
 * {@code (resourceType, resourceId, permission, subject)} triples in one
 * {@code CheckBulkPermissions} RPC and receive the results as a
 * {@link CheckMatrix}. Unlike {@link TypedResourceFactory.TypedCheckAction}
 * which is bound to one resource type, this builder lets you mix types
 * in the same batch, so a UI showing "can alice view doc-1 AND complete
 * task-5 AND edit folder-9?" is one round trip instead of three.
 *
 * <pre>
 * CheckMatrix result = client.batchCheck()
 *         .add("document", "doc-1", Document.Perm.VIEW, SubjectRef.user("alice"))
 *         .add("task",     "t-5",   Task.Perm.COMPLETE, SubjectRef.user("alice"))
 *         .add("folder",   "f-9",   Folder.Perm.EDIT,   SubjectRef.user("alice"))
 *         .fetch();
 *
 * result.allowed("document", "view", "alice");   // true/false
 * result.allAllowed();                           // true iff all three pass
 * </pre>
 */
public final class BatchCheckBuilder {

    private final SdkTransport transport;
    private final List<Entry> entries = new ArrayList<>();
    private Consistency consistency = Consistency.minimizeLatency();

    BatchCheckBuilder(SdkTransport transport) {
        this.transport = transport;
    }

    /** Override the consistency level for this whole batch. */
    public BatchCheckBuilder withConsistency(Consistency consistency) {
        this.consistency = consistency;
        return this;
    }

    /** Add one (resourceType, id, permission, subject) cell to the batch. */
    public BatchCheckBuilder add(String resourceType, String resourceId,
                                  Permission.Named permission, SubjectRef subject) {
        entries.add(new Entry(resourceType, resourceId, permission.permissionName(), subject));
        return this;
    }

    /** Convenience: default-user subject. */
    public BatchCheckBuilder add(String resourceType, String resourceId,
                                  Permission.Named permission, String userId) {
        return add(resourceType, resourceId, permission, SubjectRef.user(userId));
    }

    /** String-based overload for dynamic-permission cases. */
    public BatchCheckBuilder add(String resourceType, String resourceId,
                                  String permission, SubjectRef subject) {
        entries.add(new Entry(resourceType, resourceId, permission, subject));
        return this;
    }

    /**
     * Execute the batch. The entire set is sent in a single
     * {@code CheckBulkPermissions} RPC (auto-batched above the transport's
     * MAX_BATCH_SIZE threshold). Returns a {@link CheckMatrix} keyed on
     * {@code (resourceType:resourceId, permission, subject.id)} — note
     * the {@code resourceId} dimension in the matrix is a composite
     * {@code "type:id"} so cross-type batches don't collide on bare ids.
     */
    public CheckMatrix fetch() {
        if (entries.isEmpty()) return CheckMatrix.builder().build();
        List<SdkTransport.BulkCheckItem> items = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            items.add(new SdkTransport.BulkCheckItem(
                    ResourceRef.of(e.resourceType, e.resourceId),
                    Permission.of(e.permission),
                    e.subject));
        }
        List<CheckResult> results = transport.checkBulkMulti(items, consistency);
        var b = CheckMatrix.builder();
        for (int i = 0; i < results.size(); i++) {
            Entry e = entries.get(i);
            String compositeId = e.resourceType + ":" + e.resourceId;
            b.add(compositeId, e.permission, e.subject.id(), results.get(i).hasPermission());
        }
        return b.build();
    }

    private record Entry(String resourceType, String resourceId, String permission, SubjectRef subject) {}
}
