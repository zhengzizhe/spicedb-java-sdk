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
    private final List<RelationshipUpdate> updates = new ArrayList<>();

    CrossResourceBatchBuilder(SdkTransport transport) {
        this.transport = transport;
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

        /** Grant the relation(s) to the given {@link SubjectRef subjects}. */
        public ResourceScope to(SubjectRef... subjects) {
            ResourceRef resource = ResourceRef.of(scope.resourceType, scope.resourceId);
            for (String rel : relations) {
                for (SubjectRef sub : subjects) {
                    scope.batch.addUpdate(new RelationshipUpdate(
                            Operation.TOUCH, resource, Relation.of(rel), sub));
                }
            }
            return scope;
        }

        /** Grant the relation(s) to the given canonical subject strings
         *  ({@code "user:alice"}, {@code "group:eng#member"}, {@code "user:*"}). */
        public ResourceScope to(String... subjectRefs) {
            SubjectRef[] subjects = new SubjectRef[subjectRefs.length];
            for (int i = 0; i < subjectRefs.length; i++) subjects[i] = SubjectRef.parse(subjectRefs[i]);
            return to(subjects);
        }

        /** {@link Iterable} overload of {@link #to(String...)}. */
        public ResourceScope to(Iterable<String> subjectRefs) {
            java.util.List<SubjectRef> subjects = new java.util.ArrayList<>();
            for (String ref : subjectRefs) subjects.add(SubjectRef.parse(ref));
            return to(subjects.toArray(SubjectRef[]::new));
        }

        // ────── Typed subject overloads (mirror TypedGrantAction) ─────

        /** Typed single subject: {@code .grant(...).to(User.TYPE, "alice")}. */
        public ResourceScope to(ResourceType<?, ?> subjectType, String id) {
            return to(new String[]{subjectType.name() + ":" + id});
        }

        /** Typed sub-relation: {@code .grant(...).to(Group.TYPE, "eng", "member")}. */
        public ResourceScope to(ResourceType<?, ?> subjectType, String id, String subjectRelation) {
            return to(new String[]{subjectType.name() + ":" + id + "#" + subjectRelation});
        }

        /** Typed wildcard: {@code .grant(...).toWildcard(User.TYPE)}. */
        public ResourceScope toWildcard(ResourceType<?, ?> subjectType) {
            return to(new String[]{subjectType.name() + ":*"});
        }

        /** Typed batch: same type, many ids. Each id is wrapped as {@code "type:id"}. */
        public ResourceScope to(ResourceType<?, ?> subjectType, Iterable<String> ids) {
            java.util.List<String> refs = new java.util.ArrayList<>();
            for (String id : ids) refs.add(subjectType.name() + ":" + id);
            return to(refs.toArray(String[]::new));
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

        /** Grant the relation(s) to the given {@link SubjectRef subjects}, fanned across all resource ids. */
        public MultiResourceScope to(SubjectRef... subjects) {
            for (String id : scope.resourceIds) {
                ResourceRef resource = ResourceRef.of(scope.resourceType, id);
                for (String rel : relations) {
                    for (SubjectRef sub : subjects) {
                        scope.batch.addUpdate(new RelationshipUpdate(
                                Operation.TOUCH, resource, Relation.of(rel), sub));
                    }
                }
            }
            return scope;
        }

        /** Grant the relation(s) to the given canonical subject strings, fanned across all resource ids. */
        public MultiResourceScope to(String... subjectRefs) {
            SubjectRef[] subjects = new SubjectRef[subjectRefs.length];
            for (int i = 0; i < subjectRefs.length; i++) subjects[i] = SubjectRef.parse(subjectRefs[i]);
            return to(subjects);
        }

        /** {@link Iterable} overload of {@link #to(String...)}. */
        public MultiResourceScope to(Iterable<String> subjectRefs) {
            java.util.List<SubjectRef> subjects = new java.util.ArrayList<>();
            for (String ref : subjectRefs) subjects.add(SubjectRef.parse(ref));
            return to(subjects.toArray(SubjectRef[]::new));
        }

        // ────── Typed subject overloads (mirror TypedGrantAction) ─────

        /** Typed single subject across every resource id in the fan. */
        public MultiResourceScope to(ResourceType<?, ?> subjectType, String id) {
            return to(new String[]{subjectType.name() + ":" + id});
        }

        /** Typed sub-relation across every resource id in the fan. */
        public MultiResourceScope to(ResourceType<?, ?> subjectType, String id, String subjectRelation) {
            return to(new String[]{subjectType.name() + ":" + id + "#" + subjectRelation});
        }

        /** Typed wildcard across every resource id in the fan. */
        public MultiResourceScope toWildcard(ResourceType<?, ?> subjectType) {
            return to(new String[]{subjectType.name() + ":*"});
        }

        /** Typed batch: same type, many ids. Each id is wrapped as {@code "type:id"}. */
        public MultiResourceScope to(ResourceType<?, ?> subjectType, Iterable<String> ids) {
            java.util.List<String> refs = new java.util.ArrayList<>();
            for (String id : ids) refs.add(subjectType.name() + ":" + id);
            return to(refs.toArray(String[]::new));
        }
    }

    public static class MultiRevokeScope {
        private final MultiResourceScope scope;
        private final String[] relations;

        MultiRevokeScope(MultiResourceScope scope, String[] relations) {
            this.scope = scope;
            this.relations = relations;
        }

        /** Revoke the relation(s) from the given {@link SubjectRef subjects}, fanned across all resource ids. */
        public MultiResourceScope from(SubjectRef... subjects) {
            for (String id : scope.resourceIds) {
                ResourceRef resource = ResourceRef.of(scope.resourceType, id);
                for (String rel : relations) {
                    for (SubjectRef sub : subjects) {
                        scope.batch.addUpdate(new RelationshipUpdate(
                                Operation.DELETE, resource, Relation.of(rel), sub));
                    }
                }
            }
            return scope;
        }

        /** Revoke the relation(s) from the given canonical subject strings, fanned across all resource ids. */
        public MultiResourceScope from(String... subjectRefs) {
            SubjectRef[] subjects = new SubjectRef[subjectRefs.length];
            for (int i = 0; i < subjectRefs.length; i++) subjects[i] = SubjectRef.parse(subjectRefs[i]);
            return from(subjects);
        }

        /** {@link Iterable} overload of {@link #from(String...)}. */
        public MultiResourceScope from(Iterable<String> subjectRefs) {
            java.util.List<SubjectRef> subjects = new java.util.ArrayList<>();
            for (String ref : subjectRefs) subjects.add(SubjectRef.parse(ref));
            return from(subjects.toArray(SubjectRef[]::new));
        }

        // ────── Typed subject overloads (mirror TypedRevokeAction) ─────

        /** Typed single subject across every resource id in the fan. */
        public MultiResourceScope from(ResourceType<?, ?> subjectType, String id) {
            return from(new String[]{subjectType.name() + ":" + id});
        }

        /** Typed sub-relation across every resource id in the fan. */
        public MultiResourceScope from(ResourceType<?, ?> subjectType, String id, String subjectRelation) {
            return from(new String[]{subjectType.name() + ":" + id + "#" + subjectRelation});
        }

        /** Typed wildcard across every resource id in the fan. */
        public MultiResourceScope fromWildcard(ResourceType<?, ?> subjectType) {
            return from(new String[]{subjectType.name() + ":*"});
        }

        /** Typed batch: same type, many ids. Each id is wrapped as {@code "type:id"}. */
        public MultiResourceScope from(ResourceType<?, ?> subjectType, Iterable<String> ids) {
            java.util.List<String> refs = new java.util.ArrayList<>();
            for (String id : ids) refs.add(subjectType.name() + ":" + id);
            return from(refs.toArray(String[]::new));
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

        /** Revoke the relation(s) from the given {@link SubjectRef subjects}. */
        public ResourceScope from(SubjectRef... subjects) {
            ResourceRef resource = ResourceRef.of(scope.resourceType, scope.resourceId);
            for (String rel : relations) {
                for (SubjectRef sub : subjects) {
                    scope.batch.addUpdate(new RelationshipUpdate(
                            Operation.DELETE, resource, Relation.of(rel), sub));
                }
            }
            return scope;
        }

        /** Revoke the relation(s) from the given canonical subject strings. */
        public ResourceScope from(String... subjectRefs) {
            SubjectRef[] subjects = new SubjectRef[subjectRefs.length];
            for (int i = 0; i < subjectRefs.length; i++) subjects[i] = SubjectRef.parse(subjectRefs[i]);
            return from(subjects);
        }

        /** {@link Iterable} overload of {@link #from(String...)}. */
        public ResourceScope from(Iterable<String> subjectRefs) {
            java.util.List<SubjectRef> subjects = new java.util.ArrayList<>();
            for (String ref : subjectRefs) subjects.add(SubjectRef.parse(ref));
            return from(subjects.toArray(SubjectRef[]::new));
        }

        // ────── Typed subject overloads (mirror TypedRevokeAction) ─────

        /** Typed single subject: {@code .revoke(...).from(User.TYPE, "alice")}. */
        public ResourceScope from(ResourceType<?, ?> subjectType, String id) {
            return from(new String[]{subjectType.name() + ":" + id});
        }

        /** Typed sub-relation: {@code .revoke(...).from(Group.TYPE, "eng", "member")}. */
        public ResourceScope from(ResourceType<?, ?> subjectType, String id, String subjectRelation) {
            return from(new String[]{subjectType.name() + ":" + id + "#" + subjectRelation});
        }

        /** Typed wildcard: {@code .revoke(...).fromWildcard(User.TYPE)}. */
        public ResourceScope fromWildcard(ResourceType<?, ?> subjectType) {
            return from(new String[]{subjectType.name() + ":*"});
        }

        /** Typed batch: same type, many ids. Each id is wrapped as {@code "type:id"}. */
        public ResourceScope from(ResourceType<?, ?> subjectType, Iterable<String> ids) {
            java.util.List<String> refs = new java.util.ArrayList<>();
            for (String id : ids) refs.add(subjectType.name() + ":" + id);
            return from(refs.toArray(String[]::new));
        }
    }
}
