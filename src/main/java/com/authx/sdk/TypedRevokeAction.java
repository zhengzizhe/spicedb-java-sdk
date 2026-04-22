package com.authx.sdk;

import com.authx.sdk.action.RevokeCompletion;
import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.RevokeResult;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.SubjectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Typed revoke action — mirror of {@link TypedGrantAction} for removing
 * relationships.
 *
 * <p><b>No-match behaviour:</b> SpiceDB tolerates revokes that match no
 * tuples — a revoke with a wrong subject type silently succeeds as a
 * no-op rather than raising an error. Client code that wants fast-fail
 * semantics must check the {@link com.authx.sdk.model.RevokeResult}'s
 * {@code count()} or issue a {@code check()} first. (Pre-ADR 2026-04-18
 * the SDK caught subject-type mismatches locally via SchemaCache; that
 * check was removed with the cache subsystem.)
 *
 * <pre>
 * doc.select("doc-1").revoke(Document.Rel.EDITOR).fromUser("bob");
 * doc.select("doc-1").revoke(Document.Rel.FOLDER).fromFolder("f-1");
 * task.select("t-1").revoke(Task.Rel.DOCUMENT)
 *     .from(SubjectRef.of("document", "doc-5", null));
 * </pre>
 */
public class TypedRevokeAction<R extends Relation.Named> {

    protected final ResourceFactory factory;
    protected final String[] ids;
    protected final R[] relations;

    @SafeVarargs
    public TypedRevokeAction(ResourceFactory factory, String[] ids, R... relations) {
        this.factory = factory;
        this.ids = ids;
        this.relations = relations;
    }

    // ════════════════════════════════════════════════════════════════
    //  Terminal methods — subjects come in as SubjectRef or canonical strings
    // ════════════════════════════════════════════════════════════════

    /** Revoke from one or more {@link SubjectRef subjects}. */
    public RevokeCompletion from(SubjectRef... subjects) {
        if (subjects == null || subjects.length == 0) {
            return RevokeCompletion.of(new RevokeResult(null, 0));
        }
        String[] refs = new String[subjects.length];
        for (int i = 0; i < subjects.length; i++) {
            refs[i] = subjects[i].toRefString();
        }
        return write(refs);
    }

    /** Collection overload of {@link #from(SubjectRef...)}. */
    public RevokeCompletion from(Collection<SubjectRef> subjects) {
        return from(subjects.toArray(SubjectRef[]::new));
    }

    /**
     * Bare-id form with single-type inference. Mirrors
     * {@link com.authx.sdk.action.RevokeAction#from(String)}.
     *
     * @throws IllegalArgumentException when inference is impossible
     *         (ambiguous / wildcard-only) or when the id is bare and no
     *         schema is attached.
     */
    public RevokeCompletion from(String id) {
        if (id.indexOf(':') >= 0) {
            return from(new String[]{id});
        }
        SchemaCache cache = factory.schemaCache();
        if (cache == null) {
            return from(new String[]{id});
        }
        String resourceType = factory.resourceType();
        SubjectType inferred = null;
        for (R rel : relations) {
            String relName = rel.relationName();
            List<SubjectType> sts = cache.getSubjectTypes(resourceType, relName);
            if (sts.isEmpty()) {
                return from(new String[]{id});
            }
            if (sts.stream().allMatch(SubjectType::wildcard)) {
                throw new IllegalArgumentException(
                        resourceType + "." + relName + " only accepts wildcards ("
                                + shapes(sts) + "); use fromWildcard(ResourceType) instead");
            }
            var single = SubjectType.inferSingleType(sts);
            if (single.isEmpty()) {
                throw new IllegalArgumentException(
                        "ambiguous subject type for " + resourceType + "." + relName
                                + " (allowed: " + shapes(sts)
                                + "); use from(ResourceType, id) instead");
            }
            if (inferred == null) {
                inferred = single.get();
            } else if (!inferred.type().equals(single.get().type())) {
                throw new IllegalArgumentException(
                        "cannot infer single subject type across " + relations.length
                                + " relations with differing declared types ("
                                + inferred.type() + " vs " + single.get().type()
                                + "); use from(ResourceType, id) instead");
            }
        }
        return from(new String[]{inferred.type() + ":" + id});
    }

    private static String shapes(List<SubjectType> sts) {
        return sts.stream()
                .map(SubjectType::toRef)
                .distinct()
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** Typed subject form: {@code revoke(...).from(User.TYPE, "alice")}. */
    public <R2 extends Enum<R2> & Relation.Named, P2 extends Enum<P2> & Permission.Named>
    RevokeCompletion from(ResourceType<R2, P2> subjectType, String id) {
        return from(new String[]{subjectType.name() + ":" + id});
    }

    /** Typed subject with sub-relation: {@code revoke(...).from(Group.TYPE, "eng", "member")}. */
    public <R2 extends Enum<R2> & Relation.Named, P2 extends Enum<P2> & Permission.Named>
    RevokeCompletion from(ResourceType<R2, P2> subjectType, String id, String subjectRelation) {
        return from(new String[]{subjectType.name() + ":" + id + "#" + subjectRelation});
    }

    /**
     * Typed subject with typed {@link Relation.Named} sub-relation —
     * symmetric to {@link TypedGrantAction#to(ResourceType, String, Enum)}.
     * Example: {@code .from(Group, "eng", Group.Rel.MEMBER)}.
     */
    public <R2 extends Enum<R2> & Relation.Named, P2 extends Enum<P2> & Permission.Named>
    RevokeCompletion from(ResourceType<R2, P2> subjectType, String id, R2 subjectRelation) {
        return from(subjectType, id, subjectRelation.relationName());
    }

    /**
     * Typed subject with typed {@link Permission.Named} sub-relation
     * (e.g. {@code department#all_members}): {@code .from(Department, "hq", Department.Perm.ALL_MEMBERS)}.
     *
     * <p>Drops the {@code Enum} bound to sidestep type-erasure conflict with
     * the {@code R2} overload above — see {@code TypedGrantAction.to} for
     * the rationale.
     */
    public RevokeCompletion from(ResourceType<?, ?> subjectType, String id, Permission.Named subjectPermission) {
        return from(new String[]{subjectType.name() + ":" + id + "#" + subjectPermission.permissionName()});
    }

    /** Wildcard typed form: {@code revoke(...).fromWildcard(User.TYPE)}. */
    public <R2 extends Enum<R2> & Relation.Named, P2 extends Enum<P2> & Permission.Named>
    RevokeCompletion fromWildcard(ResourceType<R2, P2> subjectType) {
        return from(new String[]{subjectType.name() + ":*"});
    }

    /** Typed batch: same subject type, many ids. */
    public <R2 extends Enum<R2> & Relation.Named, P2 extends Enum<P2> & Permission.Named>
    RevokeCompletion from(ResourceType<R2, P2> subjectType, Iterable<String> ids) {
        List<String> refs = new ArrayList<>();
        for (String id : ids) refs.add(subjectType.name() + ":" + id);
        return from(refs.toArray(String[]::new));
    }

    /**
     * Revoke from one or more canonical subject strings:
     * {@code "user:alice"}, {@code "group:eng#member"}, {@code "user:*"}.
     */
    public RevokeCompletion from(String... subjectRefs) {
        if (subjectRefs == null || subjectRefs.length == 0) {
            return RevokeCompletion.of(new RevokeResult(null, 0));
        }
        return write(subjectRefs);
    }

    /** {@link Iterable} overload of {@link #from(String...)}. */
    public RevokeCompletion from(Iterable<String> subjectRefs) {
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String ref : subjectRefs) list.add(ref);
        if (list.isEmpty()) return RevokeCompletion.of(new RevokeResult(null, 0));
        return write(list.toArray(String[]::new));
    }

    // ════════════════════════════════════════════════════════════════
    //  Internals
    // ════════════════════════════════════════════════════════════════

    private RevokeCompletion write(String[] refs) {
        // Client-side schema validation was removed with the SchemaCache in
        // ADR 2026-04-18. Invalid subject types now fail at the SpiceDB
        // boundary as AuthxInvalidArgumentException.
        //
        // Aggregate per-RPC RevokeResults into a single result (SR:req-6).
        String lastToken = null;
        int totalCount = 0;
        for (String id : ids) {
            for (R rel : relations) {
                RevokeResult r = factory.revoke(id, rel.relationName(), refs);
                if (r.zedToken() != null) lastToken = r.zedToken();
                totalCount += r.count();
            }
        }
        return RevokeCompletion.of(new RevokeResult(lastToken, totalCount));
    }
}
