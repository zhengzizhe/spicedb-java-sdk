package com.authx.sdk;

import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.ExpandTree;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.google.errorprone.annotations.CheckReturnValue;
import java.util.Collection;

/**
 * A typed handle scoped to one or more resource ids of a specific type.
 * Callers obtain handles through {@code client.on(Document).select(...)}:
 * <pre>
 * client.on(Document).select("doc-1").check(Document.Perm.VIEW).by(User, "alice");
 * </pre>
 */
public class TypedHandle<R extends Enum<R> & Relation.Named,
                         P extends Enum<P> & Permission.Named> {

    protected final ResourceFactory factory;
    protected final String[] ids;
    /**
     * Permission enum class — optional. Populated when the handle is
     * built via {@link TypedResourceEntry#select(String...)} so that
     * {@link #checkAll()} can iterate every permission without the
     * caller having to pass permission metadata explicitly.
     */
    protected final Class<P> permClass;

    TypedHandle(ResourceFactory factory, String[] ids) {
        this(factory, ids, null);
    }

    TypedHandle(ResourceFactory factory, String[] ids, Class<P> permClass) {
        this.factory = factory;
        this.ids = ids;
        this.permClass = permClass;
    }

    // ────────────────────────────────────────────────────────────────
    //  Grant / revoke
    // ────────────────────────────────────────────────────────────────

    /**
     * Start a {@link WriteFlow} in GRANT mode. Accumulate multiple
     * (relation, subject) pairs — and optionally mix in {@code .revoke()}
     * / {@code .from()} — then commit them in one atomic
     * {@code WriteRelationships} RPC. Requires exactly one selected
     * resource id; for multi-id atomic writes use
     * {@link CrossResourceBatchBuilder}.
     *
     * <pre>
     * // Grant-only
     * client.on(DOCUMENT).select(docId)
     *     .grant(Document.Rel.VIEWER)
     *     .to(USER, "alice")
     *     .to(GROUP, "eng", Group.Rel.MEMBER)
     *     .commit();
     *
     * // Mixed — change role atomically
     * client.on(DOCUMENT).select(docId)
     *     .revoke(Document.Rel.EDITOR).from(USER, "alice")
     *     .grant(Document.Rel.VIEWER).to(USER, "alice")
     *     .commit();
     * </pre>
     */
    @CheckReturnValue
    public WriteFlow grant(R relation) {
        requireSingleId("grant");
        return newFlow().grant(relation);
    }

    /** Start a {@link WriteFlow} in REVOKE mode. See {@link #grant(Enum)}. */
    @CheckReturnValue
    public WriteFlow revoke(R relation) {
        requireSingleId("revoke");
        return newFlow().revoke(relation);
    }

    private WriteFlow newFlow() {
        return new WriteFlow(factory.resourceType(), ids[0],
                factory.transport(), factory.schemaCache());
    }

    private void requireSingleId(String method) {
        if (ids.length != 1) {
            throw new IllegalStateException(
                    method + "() requires exactly one selected resource id; got "
                            + ids.length + " — use CrossResourceBatchBuilder for multi-resource writes");
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Check
    // ────────────────────────────────────────────────────────────────

    /**
     * Single-permission check — the 99% path. Terminate with
     * {@link TypedCheckAction#by(String)} for a plain boolean, or
     * {@link TypedCheckAction#detailedBy(String)} for a
     * {@link com.authx.sdk.model.CheckResult} carrying caveat /
     * CONDITIONAL_PERMISSION information.
     */
    public TypedCheckAction check(P permission) {
        return new TypedCheckAction(factory, ids, new String[]{permission.permissionName()});
    }

    /**
     * Multi-permission check — returns a plan that can resolve against
     * one or more subjects and produce a
     * {@link com.authx.sdk.model.CheckMatrix} via {@code .byAll(...)}.
     * Use for batch UIs (permission sidebar, list views, dashboards).
     */
    @SafeVarargs
    public final TypedCheckAction check(P... permissions) {
        return new TypedCheckAction(factory, ids, SdkRefs.permissionNames(permissions, "check(...)"));
    }

    /** Collection overload — {@code .check(EnumSet.of(Perm.VIEW, Perm.EDIT))}. */
    public TypedCheckAction check(Collection<P> permissions) {
        return new TypedCheckAction(factory, ids, SdkRefs.permissionNames(permissions, "check(Collection)"));
    }

    /**
     * Check <b>every</b> permission declared on the type's enum in one
     * {@code CheckBulkPermissions} RPC and return the results as a typed
     * {@link java.util.EnumMap}. Saves the caller from listing each
     * {@code Perm.X} manually.
     *
     * <pre>
     * EnumMap&lt;Document.Perm, Boolean&gt; toolbar =
     *     client.on(DOCUMENT).select(docId).checkAll().by(USER, userId);
     * </pre>
     */
    public TypedCheckAllAction<P> checkAll() {
        if (permClass == null) {
            throw new IllegalStateException(
                    "checkAll() without args requires the handle to be built via " +
                    "client.on(ResourceType).select(...).");
        }
        return new TypedCheckAllAction<>(factory, ids, permClass);
    }

    // ────────────────────────────────────────────────────────────────
    //  lookupSubjects — resource -> subjects
    // ────────────────────────────────────────────────────────────────

    /**
     * Typed lookupSubjects — "who (of the given subject type) has this
     * permission on the selected resource?". Requires exactly one selected
     * resource id (SpiceDB's LookupSubjects is a per-resource RPC).
     *
     * @param subjectType the subject object type to look up, e.g.
     *                    {@code "user"} or {@code "service"} — SpiceDB's
     *                    LookupSubjects always filters by object type, so
     *                    the caller must specify it.
     */
    public TypedWhoQuery lookupSubjects(String subjectType, P permission) {
        if (ids.length != 1) {
            throw new IllegalStateException(
                    "lookupSubjects(...) requires exactly one selected resource id; got " + ids.length);
        }
        return new TypedWhoQuery(factory, ids[0], subjectType, permission.permissionName());
    }

    /**
     * Typed subject-type overload of {@link #lookupSubjects(String, Enum)}:
     * {@code client.on(Document).select(id).lookupSubjects(User, Document.Perm.EDIT)}.
     * The subject type name is read from the {@code ResourceType} descriptor.
     */
    public <R2 extends Enum<R2> & Relation.Named,
            P2 extends Enum<P2> & Permission.Named>
    TypedWhoQuery lookupSubjects(ResourceType<R2, P2> subjectType, P permission) {
        return lookupSubjects(subjectType.name(), permission);
    }

    // ────────────────────────────────────────────────────────────────
    //  Relations / expand
    // ────────────────────────────────────────────────────────────────

    /** Read relationships on the selected typed resource. */
    @SafeVarargs
    public final RelationQuery relations(R... relations) {
        requireSingleId("relations");
        if (relations == null) {
            throw new IllegalArgumentException("relations(...) requires non-null relation values");
        }
        String[] names = new String[relations.length];
        for (int i = 0; i < relations.length; i++) {
            names[i] = relations[i].relationName();
        }
        return new RelationQuery(factory.resourceType(), ids[0], factory.transport(), names);
    }

    /** Expand the selected typed permission tree. */
    public ExpandTree expand(P permission) {
        requireSingleId("expand");
        return factory.transport().expand(
                ResourceRef.of(factory.resourceType(), ids[0]),
                Permission.of(permission),
                Consistency.full());
    }
}
