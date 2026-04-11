package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;

import java.util.Collection;

/**
 * A typed handle scoped to one or more resource ids of a specific type.
 * Construct directly from a {@link ResourceFactory} (usually via
 * {@code client.on(TYPE)}) — no base class required, no wrapper object
 * to hold as a field:
 *
 * <pre>
 * var handle = new TypedHandle&lt;Document.Rel, Document.Perm&gt;(
 *         client.on(Document.TYPE), new String[]{"doc-1"});
 * boolean ok = handle.check(Document.Perm.VIEW).by("alice");
 * </pre>
 *
 * <p>In practice callers go through the generated static API on the
 * constants class instead:
 * <pre>
 * Document.select(client, "doc-1").check(Document.Perm.VIEW).by("alice");
 * </pre>
 *
 * <p>This class is the same shape the 1.x {@code TypedResourceFactory}
 * exposed as an inner class, pulled out to top-level so the generated
 * {@code Document.java} (and any business-side helpers) can instantiate
 * it without needing to subclass a ResourceFactory.
 */
public class TypedHandle<R extends Relation.Named, P extends Permission.Named> {

    protected final ResourceFactory factory;
    protected final String[] ids;
    /**
     * Permission enum class — optional. Populated when the handle is
     * built via {@link TypedResourceEntry#select(String...)} so that
     * {@link #checkAll()} can iterate every permission without the
     * caller having to pass {@code Xxx.Perm.class} explicitly.
     */
    protected final Class<? extends P> permClass;

    public TypedHandle(ResourceFactory factory, String[] ids) {
        this(factory, ids, null);
    }

    public TypedHandle(ResourceFactory factory, String[] ids, Class<? extends P> permClass) {
        this.factory = factory;
        this.ids = ids;
        this.permClass = permClass;
    }

    // ────────────────────────────────────────────────────────────────
    //  Grant / revoke
    // ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public TypedGrantAction<R> grant(R... relations) {
        return new TypedGrantAction<>(factory, ids, relations);
    }

    /** Collection overload — hand it a {@code List<Rel>}, {@code EnumSet<Rel>}, etc. */
    @SuppressWarnings("unchecked")
    public TypedGrantAction<R> grant(Collection<R> relations) {
        return new TypedGrantAction<>(factory, ids, (R[]) relations.toArray(Relation.Named[]::new));
    }

    @SuppressWarnings("unchecked")
    public TypedRevokeAction<R> revoke(R... relations) {
        return new TypedRevokeAction<>(factory, ids, relations);
    }

    /** Collection overload for revoke. */
    @SuppressWarnings("unchecked")
    public TypedRevokeAction<R> revoke(Collection<R> relations) {
        return new TypedRevokeAction<>(factory, ids, (R[]) relations.toArray(Relation.Named[]::new));
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
        String[] perms = new String[permissions.length];
        for (int i = 0; i < permissions.length; i++) perms[i] = permissions[i].permissionName();
        return new TypedCheckAction(factory, ids, perms);
    }

    /** Collection overload — {@code .check(EnumSet.of(Perm.VIEW, Perm.EDIT))}. */
    public TypedCheckAction check(Collection<P> permissions) {
        String[] perms = new String[permissions.size()];
        int i = 0;
        for (P p : permissions) perms[i++] = p.permissionName();
        return new TypedCheckAction(factory, ids, perms);
    }

    /**
     * Check <b>every</b> permission declared on the type's enum in one
     * {@code CheckBulkPermissions} RPC and return the results as a typed
     * {@link java.util.EnumMap}. Saves the caller from listing each
     * {@code Perm.X} manually.
     *
     * <pre>
     * EnumMap&lt;Document.Perm, Boolean&gt; toolbar =
     *     Document.select(client, docId).checkAll(Document.Perm.class).by(userId);
     * </pre>
     */
    public <E extends Enum<E> & Permission.Named> TypedCheckAllAction<E> checkAll(Class<E> permClass) {
        return new TypedCheckAllAction<>(factory, ids, permClass);
    }

    /**
     * Parameterless overload — only works when the handle was produced
     * via {@link TypedResourceEntry#select(String...)}, which wires the
     * permission enum class through from {@link ResourceType#permClass()}.
     * Throws if the handle has no enum class attached (i.e. was
     * constructed directly without a {@link ResourceType}).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public TypedCheckAllAction checkAll() {
        if (permClass == null) {
            throw new IllegalStateException(
                    "checkAll() without args requires the handle to be built via " +
                    "client.on(ResourceType).select(...); use checkAll(Perm.class) otherwise.");
        }
        return new TypedCheckAllAction(factory, ids, (Class) permClass);
    }

    // ────────────────────────────────────────────────────────────────
    //  Who (lookupSubjects)
    // ────────────────────────────────────────────────────────────────

    /**
     * Typed lookupSubjects — "who has this permission on the selected
     * resource?". Requires exactly one selected resource id (SpiceDB's
     * LookupSubjects is a per-resource RPC).
     */
    public TypedWhoQuery who(P permission) {
        if (ids.length != 1) {
            throw new IllegalStateException(
                    "who(Perm) requires exactly one selected resource id; got " + ids.length);
        }
        return new TypedWhoQuery(factory, ids[0], permission.permissionName());
    }
}
