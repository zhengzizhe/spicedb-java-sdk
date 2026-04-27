package com.authx.sdk;

import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Cross-resource batch permission check — send many arbitrary
 * {@code (resourceType, resourceId, permission, subject)} triples in one
 * {@code CheckBulkPermissions} RPC and receive the results as a
 * {@link CheckMatrix}. Unlike {@link TypedCheckAction} which is bound to
 * one resource type, this builder lets you mix types in the same batch,
 * so a UI showing "can alice view doc-1 AND complete task-5 AND edit
 * folder-9?" is one round trip instead of three.
 *
 * <pre>
 * SubjectRef alice = SubjectRef.of("user", "alice");
 * CheckMatrix result = client.batchCheck()
 *         .add("document", "doc-1", Document.Perm.VIEW,     alice)
 *         .add("task",     "t-5",   Task.Perm.COMPLETE,     alice)
 *         .add("folder",   "f-9",   Folder.Perm.EDIT,       alice)
 *         .fetch();
 * </pre>
 *
 * <p>Subjects are always {@link SubjectRef}s — the SDK does not assume
 * a default subject type. Use {@link SubjectRef#parse(String)} to go
 * from a canonical string.
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

    /** Typed overload — accepts a {@link ResourceType} descriptor in place of the raw string. */
    public BatchCheckBuilder add(ResourceType<?, ?> resourceType, String resourceId,
                                  Permission.Named permission, SubjectRef subject) {
        return add(resourceType.name(), resourceId, permission, subject);
    }

    /**
     * Fan one {@code (type, permission, subject)} triple across many
     * resource ids — common pattern for "can this user view any of these
     * 50 docs?".
     */
    public BatchCheckBuilder addAll(ResourceType<?, ?> resourceType,
                                     Collection<String> resourceIds,
                                     Permission.Named permission,
                                     SubjectRef subject) {
        for (String id : resourceIds) {
            add(resourceType.name(), id, permission, subject);
        }
        return this;
    }

    /** Raw-string overload for {@link #addAll}. */
    public BatchCheckBuilder addAll(String resourceType,
                                     Collection<String> resourceIds,
                                     Permission.Named permission,
                                     SubjectRef subject) {
        for (String id : resourceIds) {
            add(resourceType, id, permission, subject);
        }
        return this;
    }

    /** Bulk add from a pre-built list of {@link Cell}s. */
    public BatchCheckBuilder addAll(Collection<Cell> cells) {
        for (Cell c : cells) {
            entries.add(new Entry(c.resourceType, c.resourceId, c.permission, c.subject));
        }
        return this;
    }

    /**
     * Value object for pre-assembling a batch outside the builder, then
     * feeding them in via {@link #addAll(Collection)}. Useful when the
     * cells are computed dynamically (e.g., from a search result set).
     */
    public record Cell(String resourceType, String resourceId, String permission, SubjectRef subject) {
        public static Cell of(ResourceType<?, ?> type, String id,
                              Permission.Named perm, SubjectRef subject) {
            return new Cell(type.name(), id, perm.permissionName(), subject);
        }
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
     * {@code (resourceType:resourceId, permission, subject.toRefString())}.
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
        com.authx.sdk.model.CheckMatrix.Builder b = CheckMatrix.builder();
        for (int i = 0; i < results.size(); i++) {
            Entry e = entries.get(i);
            String compositeId = e.resourceType + ":" + e.resourceId;
            b.add(compositeId, e.permission, e.subject.toRefString(), results.get(i).hasPermission());
        }
        return b.build();
    }

    private record Entry(String resourceType, String resourceId, String permission, SubjectRef subject) {}
}
