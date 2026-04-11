package com.authx.sdk;

import com.authx.sdk.model.BatchResult;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Cross-resource batch builder: atomic operations across multiple resources.
 *
 * <pre>
 * client.batch()
 *     .on(client.resource("document", "doc-1"))
 *         .grant("owner").to("carol")
 *     .on(client.resource("folder", "folder-1"))
 *         .grant("editor").to("dave")
 *         .revoke("viewer").from("eve")
 *     .execute();
 * </pre>
 */
public class CrossResourceBatchBuilder {

    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final List<RelationshipUpdate> updates = new ArrayList<>();

    CrossResourceBatchBuilder(SdkTransport transport, String defaultSubjectType) {
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
    }

    /** Target a specific resource for the next operations. */
    public ResourceScope on(ResourceHandle resource) {
        return new ResourceScope(this, resource.resourceType(), resource.resourceId());
    }

    /**
     * Target a specific resource by {@code (type, id)} without needing a
     * {@link ResourceHandle} — most callers want this since they already
     * have both strings at call sites.
     *
     * <pre>
     * client.batch()
     *     .on("document", "doc-1").grant("editor").to("alice")
     *     .on("task", "t-5").grant("assignee").to("bob")
     *     .commit();
     * </pre>
     */
    public ResourceScope on(String resourceType, String resourceId) {
        return new ResourceScope(this, resourceType, resourceId);
    }

    /**
     * Typed overload — accepts a {@link ResourceType} descriptor in place
     * of the raw string, so business code can keep the same typed tokens
     * it uses in {@code client.on(Xxx.TYPE)} chains.
     */
    public ResourceScope on(ResourceType<?, ?> resourceType, String resourceId) {
        return new ResourceScope(this, resourceType.name(), resourceId);
    }

    /**
     * Fan subsequent grant/revoke operations across many ids of the same
     * resource type. Every terminal {@code to(user)} / {@code from(user)}
     * on the returned scope applies to every id in the collection, still
     * as part of the same atomic {@code WriteRelationships} RPC.
     *
     * <pre>
     * client.batch()
     *     .onAll(Document.TYPE, List.of("d-1", "d-2", "d-3"))
     *         .grant(Document.Rel.VIEWER).to("alice")
     *     .commit();
     * </pre>
     */
    public MultiResourceScope onAll(ResourceType<?, ?> resourceType, Collection<String> resourceIds) {
        return new MultiResourceScope(this, resourceType.name(), List.copyOf(resourceIds));
    }

    /** Raw-string overload for {@link #onAll(ResourceType, Collection)}. */
    public MultiResourceScope onAll(String resourceType, Collection<String> resourceIds) {
        return new MultiResourceScope(this, resourceType, List.copyOf(resourceIds));
    }

    /**
     * Execute all accumulated operations in a single atomic
     * {@code WriteRelationships} RPC. All-or-nothing: either every update
     * applies, or none do. Returns the zedToken of the committed batch for
     * subsequent write-after-read consistency.
     */
    public BatchResult execute() {
        if (updates.isEmpty()) return new BatchResult(null);
        var r = transport.writeRelationships(updates);
        return new BatchResult(r.zedToken());
    }

    /** Alias for {@link #execute()} — read more naturally in some call sites. */
    public BatchResult commit() {
        return execute();
    }

    void addUpdate(RelationshipUpdate update) {
        updates.add(update);
    }

    /** Scoped context targeting a single resource within a cross-resource batch. */
    public static class ResourceScope {
        private final CrossResourceBatchBuilder batch;
        private final String resourceType;
        private final String resourceId;

        ResourceScope(CrossResourceBatchBuilder batch, String resourceType, String resourceId) {
            this.batch = batch;
            this.resourceType = resourceType;
            this.resourceId = resourceId;
        }

        /** Add grant operations for the given relations on the scoped resource. */
        public GrantScope grant(String... relations) {
            return new GrantScope(this, relations);
        }

        /** Typed overload — grant one or more {@link Relation.Named} relations. */
        public GrantScope grant(Relation.Named... relations) {
            String[] names = new String[relations.length];
            for (int i = 0; i < relations.length; i++) names[i] = relations[i].relationName();
            return new GrantScope(this, names);
        }

        /** Add revoke operations for the given relations on the scoped resource. */
        public RevokeScope revoke(String... relations) {
            return new RevokeScope(this, relations);
        }

        /** Typed overload — revoke one or more {@link Relation.Named} relations. */
        public RevokeScope revoke(Relation.Named... relations) {
            String[] names = new String[relations.length];
            for (int i = 0; i < relations.length; i++) names[i] = relations[i].relationName();
            return new RevokeScope(this, names);
        }

        /** Switch to a different resource. */
        public ResourceScope on(ResourceHandle resource) {
            return batch.on(resource);
        }

        /** Switch to a different resource by type/id. */
        public ResourceScope on(String resourceType, String resourceId) {
            return batch.on(resourceType, resourceId);
        }

        /** Typed switch — accepts a {@link ResourceType} descriptor. */
        public ResourceScope on(ResourceType<?, ?> resourceType, String resourceId) {
            return batch.on(resourceType, resourceId);
        }

        /** Switch to a multi-id scope (same-type fan-out). */
        public MultiResourceScope onAll(ResourceType<?, ?> resourceType, Collection<String> resourceIds) {
            return batch.onAll(resourceType, resourceIds);
        }

        public MultiResourceScope onAll(String resourceType, Collection<String> resourceIds) {
            return batch.onAll(resourceType, resourceIds);
        }

        /** Execute all operations. */
        public BatchResult execute() {
            return batch.execute();
        }

        /** Alias for {@link #execute()}. */
        public BatchResult commit() {
            return batch.commit();
        }
    }

    /** Grant scope within a cross-resource batch, targeting specific relations. */
    public static class GrantScope {
        private final ResourceScope scope;
        private final String[] relations;

        GrantScope(ResourceScope scope, String[] relations) {
            this.scope = scope;
            this.relations = relations;
        }

        /** Grant the relation(s) to the given user ids. */
        public ResourceScope to(String... userIds) {
            return to(Arrays.asList(userIds));
        }

        /** Grant the relation(s) to the given user ids. */
        public ResourceScope to(Collection<String> userIds) {
            ResourceRef resource = ResourceRef.of(scope.resourceType, scope.resourceId);
            for (String rel : relations) {
                for (String uid : userIds) {
                    scope.batch.addUpdate(new RelationshipUpdate(
                            Operation.TOUCH,
                            resource,
                            Relation.of(rel),
                            SubjectRef.of(scope.batch.defaultSubjectType, uid, null)));
                }
            }
            return scope;
        }

        /** Grant the relation(s) to the given subject refs. */
        public ResourceScope toSubjects(String... subjectRefs) {
            ResourceRef resource = ResourceRef.of(scope.resourceType, scope.resourceId);
            for (String rel : relations) {
                for (String ref : subjectRefs) {
                    SubjectRef parsed = SubjectRef.parse(ref);
                    scope.batch.addUpdate(new RelationshipUpdate(
                            Operation.TOUCH,
                            resource,
                            Relation.of(rel),
                            parsed));
                }
            }
            return scope;
        }
    }

    /**
     * Scope that fans every subsequent grant/revoke across a list of
     * resource ids of the same type. All generated updates join the same
     * batch — {@code commit()} still sends one atomic {@code WriteRelationships}.
     */
    public static class MultiResourceScope {
        private final CrossResourceBatchBuilder batch;
        private final String resourceType;
        private final List<String> resourceIds;

        MultiResourceScope(CrossResourceBatchBuilder batch, String resourceType, List<String> resourceIds) {
            this.batch = batch;
            this.resourceType = resourceType;
            this.resourceIds = resourceIds;
        }

        public MultiGrantScope grant(String... relations) {
            return new MultiGrantScope(this, relations);
        }

        public MultiGrantScope grant(Relation.Named... relations) {
            String[] names = new String[relations.length];
            for (int i = 0; i < relations.length; i++) names[i] = relations[i].relationName();
            return new MultiGrantScope(this, names);
        }

        public MultiRevokeScope revoke(String... relations) {
            return new MultiRevokeScope(this, relations);
        }

        public MultiRevokeScope revoke(Relation.Named... relations) {
            String[] names = new String[relations.length];
            for (int i = 0; i < relations.length; i++) names[i] = relations[i].relationName();
            return new MultiRevokeScope(this, names);
        }

        /** Leave the fan and go back to single-resource scope. */
        public ResourceScope on(ResourceType<?, ?> type, String id) {
            return batch.on(type, id);
        }

        public ResourceScope on(String type, String id) {
            return batch.on(type, id);
        }

        public MultiResourceScope onAll(ResourceType<?, ?> type, Collection<String> ids) {
            return batch.onAll(type, ids);
        }

        public BatchResult execute() { return batch.execute(); }
        public BatchResult commit()  { return batch.commit(); }
    }

    public static class MultiGrantScope {
        private final MultiResourceScope scope;
        private final String[] relations;

        MultiGrantScope(MultiResourceScope scope, String[] relations) {
            this.scope = scope;
            this.relations = relations;
        }

        public MultiResourceScope to(String... userIds) {
            return to(Arrays.asList(userIds));
        }

        public MultiResourceScope to(Collection<String> userIds) {
            for (String id : scope.resourceIds) {
                ResourceRef resource = ResourceRef.of(scope.resourceType, id);
                for (String rel : relations) {
                    for (String uid : userIds) {
                        scope.batch.addUpdate(new RelationshipUpdate(
                                Operation.TOUCH,
                                resource,
                                Relation.of(rel),
                                SubjectRef.of(scope.batch.defaultSubjectType, uid, null)));
                    }
                }
            }
            return scope;
        }

        public MultiResourceScope toSubjects(String... subjectRefs) {
            for (String id : scope.resourceIds) {
                ResourceRef resource = ResourceRef.of(scope.resourceType, id);
                for (String rel : relations) {
                    for (String ref : subjectRefs) {
                        SubjectRef parsed = SubjectRef.parse(ref);
                        scope.batch.addUpdate(new RelationshipUpdate(
                                Operation.TOUCH, resource, Relation.of(rel), parsed));
                    }
                }
            }
            return scope;
        }
    }

    public static class MultiRevokeScope {
        private final MultiResourceScope scope;
        private final String[] relations;

        MultiRevokeScope(MultiResourceScope scope, String[] relations) {
            this.scope = scope;
            this.relations = relations;
        }

        public MultiResourceScope from(String... userIds) {
            return from(Arrays.asList(userIds));
        }

        public MultiResourceScope from(Collection<String> userIds) {
            for (String id : scope.resourceIds) {
                ResourceRef resource = ResourceRef.of(scope.resourceType, id);
                for (String rel : relations) {
                    for (String uid : userIds) {
                        scope.batch.addUpdate(new RelationshipUpdate(
                                Operation.DELETE,
                                resource,
                                Relation.of(rel),
                                SubjectRef.of(scope.batch.defaultSubjectType, uid, null)));
                    }
                }
            }
            return scope;
        }
    }

    /** Revoke scope within a cross-resource batch, targeting specific relations. */
    public static class RevokeScope {
        private final ResourceScope scope;
        private final String[] relations;

        RevokeScope(ResourceScope scope, String[] relations) {
            this.scope = scope;
            this.relations = relations;
        }

        /** Revoke the relation(s) from the given user ids. */
        public ResourceScope from(String... userIds) {
            return from(Arrays.asList(userIds));
        }

        /** Revoke the relation(s) from the given user ids. */
        public ResourceScope from(Collection<String> userIds) {
            ResourceRef resource = ResourceRef.of(scope.resourceType, scope.resourceId);
            for (String rel : relations) {
                for (String uid : userIds) {
                    scope.batch.addUpdate(new RelationshipUpdate(
                            Operation.DELETE,
                            resource,
                            Relation.of(rel),
                            SubjectRef.of(scope.batch.defaultSubjectType, uid, null)));
                }
            }
            return scope;
        }
    }
}
