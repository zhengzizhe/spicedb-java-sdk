package com.authx.sdk;

import com.authx.sdk.model.WriteResult;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Cross-resource batch builder: atomic operations across multiple resources.
 *
 * <pre>
 * client.batch()
 *     .on("document", "doc-1")
 *         .grant("owner").to("carol")
 *     .on("folder", "folder-1")
 *         .grant("editor").to("dave")
 *         .revoke("viewer").from("eve")
 *     .execute();
 * </pre>
 */
public class CrossResourceBatchBuilder {

    private final SdkTransport transport;
    private final ArrayList<RelationshipUpdate> updates = new ArrayList<>();

    CrossResourceBatchBuilder(SdkTransport transport) {
        this.transport = transport;
    }

    /**
     * Target a specific resource by {@code (type, id)}.
     *
     * <pre>
     * client.batch()
     *     .on("document", "doc-1").grant("editor").to("alice")
     *     .on("task", "t-5").grant("assignee").to("bob")
     *     .commit();
     * </pre>
     */
    public ResourceScope on(String resourceType, String resourceId) {
        return new ResourceScope(this,
                Objects.requireNonNull(resourceType, "resourceType"),
                Objects.requireNonNull(resourceId, "resourceId"));
    }

    /**
     * Typed overload — accepts a {@link ResourceType} descriptor in place
     * of the raw string, so business code can keep the same typed tokens
     * it uses in {@code client.on(Xxx)} chains.
     */
    public ResourceScope on(ResourceType<?, ?> resourceType, String resourceId) {
        return new ResourceScope(this,
                Objects.requireNonNull(resourceType, "resourceType").name(),
                Objects.requireNonNull(resourceId, "resourceId"));
    }

    /**
     * Fan subsequent grant/revoke operations across many ids of the same
     * resource type. Every terminal {@code to(user)} / {@code from(user)}
     * on the returned scope applies to every id in the collection, still
     * as part of the same atomic {@code WriteRelationships} RPC.
     *
     * <pre>
     * client.batch()
     *     .onAll(Document, List.of("d-1", "d-2", "d-3"))
     *         .grant(Document.Rel.VIEWER).to("alice")
     *     .commit();
     * </pre>
     */
    public MultiResourceScope onAll(ResourceType<?, ?> resourceType, Collection<String> resourceIds) {
        SdkRefs.requireNotEmpty(resourceIds, "onAll(...)", "resource id");
        return new MultiResourceScope(this,
                Objects.requireNonNull(resourceType, "resourceType").name(),
                List.copyOf(resourceIds));
    }

    /** Raw-string overload for {@link #onAll(ResourceType, Collection)}. */
    public MultiResourceScope onAll(String resourceType, Collection<String> resourceIds) {
        SdkRefs.requireNotEmpty(resourceIds, "onAll(...)", "resource id");
        return new MultiResourceScope(this,
                Objects.requireNonNull(resourceType, "resourceType"),
                List.copyOf(resourceIds));
    }

    /**
     * Execute all accumulated operations in a single atomic
     * {@code WriteRelationships} RPC. All-or-nothing: either every update
     * applies, or none do. Returns the same write completion shape used by
     * normal {@link WriteFlow#commit()} calls.
     */
    public WriteCompletion execute() {
        if (updates.isEmpty()) {
            throw new IllegalStateException("batch commit requires at least one update");
        }
        WriteResult r = transport.writeRelationships(List.copyOf(updates));
        return new WriteCompletion(r, updates.size());
    }

    /** Alias for {@link #execute()} — read more naturally in some call sites. */
    public WriteCompletion commit() {
        return execute();
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
            SdkRefs.requireNotEmpty(relations, "grant(...)", "relation");
            return new GrantScope(this, relations);
        }

        /** Typed overload — grant one or more {@link Relation.Named} relations. */
        public GrantScope grant(Relation.Named... relations) {
            return new GrantScope(this, SdkRefs.relationNames(relations, "grant(...)"));
        }

        /** Add revoke operations for the given relations on the scoped resource. */
        public RevokeScope revoke(String... relations) {
            SdkRefs.requireNotEmpty(relations, "revoke(...)", "relation");
            return new RevokeScope(this, relations);
        }

        /** Typed overload — revoke one or more {@link Relation.Named} relations. */
        public RevokeScope revoke(Relation.Named... relations) {
            return new RevokeScope(this, SdkRefs.relationNames(relations, "revoke(...)"));
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
        public WriteCompletion execute() {
            return batch.execute();
        }

        /** Alias for {@link #execute()}. */
        public WriteCompletion commit() {
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
            SdkRefs.requireNotEmpty(subjects, "to(...)", "subject");
            RelationshipUpdates.addTo(scope.batch.updates, scope.resourceType, scope.resourceId,
                    Operation.TOUCH, relations, subjects);
            return scope;
        }

        /** Grant the relation(s) to the given canonical subject strings
         *  ({@code "user:alice"}, {@code "group:eng#member"}, {@code "user:*"}). */
        public ResourceScope to(String... subjectRefs) {
            return to(SdkRefs.subjects(subjectRefs, "to(...)"));
        }

        /** {@link Iterable} overload of {@link #to(String...)}. */
        public ResourceScope to(Iterable<String> subjectRefs) {
            return to(SdkRefs.subjects(subjectRefs, "to(Iterable)"));
        }

        // ────── Typed subject overloads (mirror WriteFlow grant) ─────

        /** Typed single subject: {@code .grant(...).to(User, "alice")}. */
        public ResourceScope to(ResourceType<?, ?> subjectType, String id) {
            return to(SdkRefs.typedSubject(subjectType, id));
        }

        /** Typed sub-relation: {@code .grant(...).to(Group, "eng", "member")}. */
        public ResourceScope to(ResourceType<?, ?> subjectType, String id, String subjectRelation) {
            return to(SdkRefs.typedSubject(subjectType, id, subjectRelation));
        }

        /** Enum-typed sub-relation: {@code .grant(...).to(Group, "eng", Group.Rel.MEMBER)}. */
        public <R2 extends Enum<R2> & Relation.Named,
                P2 extends Enum<P2> & Permission.Named>
        ResourceScope to(ResourceType<R2, P2> subjectType, String id, R2 subjectRelation) {
            return to(subjectType, id, subjectRelation.relationName());
        }

        /** Enum-typed sub-permission: {@code .grant(...).to(Department, "hq", Department.Perm.ALL_MEMBERS)}. */
        public ResourceScope to(ResourceType<?, ?> subjectType, String id,
                                 Permission.Named subjectPermission) {
            return to(SdkRefs.typedSubject(subjectType, id, subjectPermission.permissionName()));
        }

        /** Typed wildcard: {@code .grant(...).toWildcard(User)}. */
        public ResourceScope toWildcard(ResourceType<?, ?> subjectType) {
            return to(SdkRefs.wildcardSubject(subjectType));
        }

        /** Typed batch: same type, many ids. Each id is wrapped as {@code "type:id"}. */
        public ResourceScope to(ResourceType<?, ?> subjectType, Iterable<String> ids) {
            return to(SdkRefs.typedSubjectStrings(subjectType, ids, "to(ResourceType, Iterable)"));
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
            SdkRefs.requireNotEmpty(relations, "grant(...)", "relation");
            return new MultiGrantScope(this, relations);
        }

        public MultiGrantScope grant(Relation.Named... relations) {
            return new MultiGrantScope(this, SdkRefs.relationNames(relations, "grant(...)"));
        }

        public MultiRevokeScope revoke(String... relations) {
            SdkRefs.requireNotEmpty(relations, "revoke(...)", "relation");
            return new MultiRevokeScope(this, relations);
        }

        public MultiRevokeScope revoke(Relation.Named... relations) {
            return new MultiRevokeScope(this, SdkRefs.relationNames(relations, "revoke(...)"));
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

        public WriteCompletion execute() { return batch.execute(); }
        public WriteCompletion commit()  { return batch.commit(); }
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
            SdkRefs.requireNotEmpty(subjects, "to(...)", "subject");
            RelationshipUpdates.addTo(scope.batch.updates, scope.resourceType, scope.resourceIds,
                    Operation.TOUCH, relations, subjects);
            return scope;
        }

        /** Grant the relation(s) to the given canonical subject strings, fanned across all resource ids. */
        public MultiResourceScope to(String... subjectRefs) {
            return to(SdkRefs.subjects(subjectRefs, "to(...)"));
        }

        /** {@link Iterable} overload of {@link #to(String...)}. */
        public MultiResourceScope to(Iterable<String> subjectRefs) {
            return to(SdkRefs.subjects(subjectRefs, "to(Iterable)"));
        }

        // ────── Typed subject overloads (mirror WriteFlow grant) ─────

        /** Typed single subject across every resource id in the fan. */
        public MultiResourceScope to(ResourceType<?, ?> subjectType, String id) {
            return to(SdkRefs.typedSubject(subjectType, id));
        }

        /** Typed sub-relation across every resource id in the fan. */
        public MultiResourceScope to(ResourceType<?, ?> subjectType, String id, String subjectRelation) {
            return to(SdkRefs.typedSubject(subjectType, id, subjectRelation));
        }

        /** Enum-typed sub-relation across every resource id in the fan. */
        public <R2 extends Enum<R2> & Relation.Named,
                P2 extends Enum<P2> & Permission.Named>
        MultiResourceScope to(ResourceType<R2, P2> subjectType, String id, R2 subjectRelation) {
            return to(subjectType, id, subjectRelation.relationName());
        }

        /** Enum-typed sub-permission across every resource id in the fan. */
        public MultiResourceScope to(ResourceType<?, ?> subjectType, String id,
                                       Permission.Named subjectPermission) {
            return to(SdkRefs.typedSubject(subjectType, id, subjectPermission.permissionName()));
        }

        /** Typed wildcard across every resource id in the fan. */
        public MultiResourceScope toWildcard(ResourceType<?, ?> subjectType) {
            return to(SdkRefs.wildcardSubject(subjectType));
        }

        /** Typed batch: same type, many ids. Each id is wrapped as {@code "type:id"}. */
        public MultiResourceScope to(ResourceType<?, ?> subjectType, Iterable<String> ids) {
            return to(SdkRefs.typedSubjectStrings(subjectType, ids, "to(ResourceType, Iterable)"));
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
            SdkRefs.requireNotEmpty(subjects, "from(...)", "subject");
            RelationshipUpdates.addTo(scope.batch.updates, scope.resourceType, scope.resourceIds,
                    Operation.DELETE, relations, subjects);
            return scope;
        }

        /** Revoke the relation(s) from the given canonical subject strings, fanned across all resource ids. */
        public MultiResourceScope from(String... subjectRefs) {
            return from(SdkRefs.subjects(subjectRefs, "from(...)"));
        }

        /** {@link Iterable} overload of {@link #from(String...)}. */
        public MultiResourceScope from(Iterable<String> subjectRefs) {
            return from(SdkRefs.subjects(subjectRefs, "from(Iterable)"));
        }

        // ────── Typed subject overloads (mirror WriteFlow revoke) ─────

        /** Typed single subject across every resource id in the fan. */
        public MultiResourceScope from(ResourceType<?, ?> subjectType, String id) {
            return from(SdkRefs.typedSubject(subjectType, id));
        }

        /** Typed sub-relation across every resource id in the fan. */
        public MultiResourceScope from(ResourceType<?, ?> subjectType, String id, String subjectRelation) {
            return from(SdkRefs.typedSubject(subjectType, id, subjectRelation));
        }

        /** Enum-typed sub-relation across every resource id in the fan. */
        public <R2 extends Enum<R2> & Relation.Named,
                P2 extends Enum<P2> & Permission.Named>
        MultiResourceScope from(ResourceType<R2, P2> subjectType, String id, R2 subjectRelation) {
            return from(subjectType, id, subjectRelation.relationName());
        }

        /** Enum-typed sub-permission across every resource id in the fan. */
        public MultiResourceScope from(ResourceType<?, ?> subjectType, String id,
                                         Permission.Named subjectPermission) {
            return from(SdkRefs.typedSubject(subjectType, id, subjectPermission.permissionName()));
        }

        /** Typed wildcard across every resource id in the fan. */
        public MultiResourceScope fromWildcard(ResourceType<?, ?> subjectType) {
            return from(SdkRefs.wildcardSubject(subjectType));
        }

        /** Typed batch: same type, many ids. Each id is wrapped as {@code "type:id"}. */
        public MultiResourceScope from(ResourceType<?, ?> subjectType, Iterable<String> ids) {
            return from(SdkRefs.typedSubjectStrings(subjectType, ids, "from(ResourceType, Iterable)"));
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
            SdkRefs.requireNotEmpty(subjects, "from(...)", "subject");
            RelationshipUpdates.addTo(scope.batch.updates, scope.resourceType, scope.resourceId,
                    Operation.DELETE, relations, subjects);
            return scope;
        }

        /** Revoke the relation(s) from the given canonical subject strings. */
        public ResourceScope from(String... subjectRefs) {
            return from(SdkRefs.subjects(subjectRefs, "from(...)"));
        }

        /** {@link Iterable} overload of {@link #from(String...)}. */
        public ResourceScope from(Iterable<String> subjectRefs) {
            return from(SdkRefs.subjects(subjectRefs, "from(Iterable)"));
        }

        // ────── Typed subject overloads (mirror WriteFlow revoke) ─────

        /** Typed single subject: {@code .revoke(...).from(User, "alice")}. */
        public ResourceScope from(ResourceType<?, ?> subjectType, String id) {
            return from(SdkRefs.typedSubject(subjectType, id));
        }

        /** Typed sub-relation: {@code .revoke(...).from(Group, "eng", "member")}. */
        public ResourceScope from(ResourceType<?, ?> subjectType, String id, String subjectRelation) {
            return from(SdkRefs.typedSubject(subjectType, id, subjectRelation));
        }

        /** Enum-typed sub-relation: {@code .revoke(...).from(Group, "eng", Group.Rel.MEMBER)}. */
        public <R2 extends Enum<R2> & Relation.Named,
                P2 extends Enum<P2> & Permission.Named>
        ResourceScope from(ResourceType<R2, P2> subjectType, String id, R2 subjectRelation) {
            return from(subjectType, id, subjectRelation.relationName());
        }

        /** Enum-typed sub-permission: {@code .revoke(...).from(Department, "hq", Department.Perm.ALL_MEMBERS)}. */
        public ResourceScope from(ResourceType<?, ?> subjectType, String id,
                                   Permission.Named subjectPermission) {
            return from(SdkRefs.typedSubject(subjectType, id, subjectPermission.permissionName()));
        }

        /** Typed wildcard: {@code .revoke(...).fromWildcard(User)}. */
        public ResourceScope fromWildcard(ResourceType<?, ?> subjectType) {
            return from(SdkRefs.wildcardSubject(subjectType));
        }

        /** Typed batch: same type, many ids. Each id is wrapped as {@code "type:id"}. */
        public ResourceScope from(ResourceType<?, ?> subjectType, Iterable<String> ids) {
            return from(SdkRefs.typedSubjectStrings(subjectType, ids, "from(ResourceType, Iterable)"));
        }
    }
}
