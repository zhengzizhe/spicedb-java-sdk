package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.SubjectRef;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed lookupResources query — "which resources of this type can this
 * subject perform permission X on?". Construct via
 * {@code client.on(Document).lookupResources(subject)}.
 *
 * <pre>
 * List&lt;String&gt; myDocs = client.on(Document).lookupResources(SubjectRef.of("user", "alice"))
 *     .limit(100)
 *     .can(Document.Perm.VIEW);
 * </pre>
 */
public class TypedFinder<P extends Permission.Named> {

    private final ResourceFactory factory;
    private final SubjectRef subject;
    private int limit = 0;

    TypedFinder(ResourceFactory factory, SubjectRef subject) {
        this.factory = factory;
        this.subject = subject;
    }

    /** Limit the number of resources returned. 0 = no limit. */
    public TypedFinder<P> limit(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        this.limit = n;
        return this;
    }

    /**
     * Fetch the ids of resources of the bound type that the subject can
     * perform {@code permission} on (reverse lookup via
     * {@code LookupResources}).
     */
    public List<String> can(P permission) {
        return ResourceLookupSupport.can(factory, subject, permission.permissionName(), limit);
    }

    /**
     * Multi-permission reverse lookup — returns a map keyed on each
     * permission. One {@code LookupResources} RPC per permission (SpiceDB
     * does not bulk multiple permissions into a single call). When you only
     * need a single flat id list, use {@link #can(Permission.Named)}.
     *
     * <pre>
     * Map&lt;Document.Perm, List&lt;String&gt;&gt; byPerm =
     *     client.on(Document).lookupResources(User, userId).limit(200)
     *           .can(Document.Perm.VIEW, Document.Perm.EDIT);
     *
     * List&lt;String&gt; viewable = byPerm.get(Document.Perm.VIEW);
     * List&lt;String&gt; editable = byPerm.get(Document.Perm.EDIT);
     * </pre>
     */
    @SafeVarargs
    public final Map<P, List<String>> can(P... permissions) {
        SdkRefs.requireNotEmpty(permissions, "can(...)", "permission");
        LinkedHashMap<P, List<String>> out = new LinkedHashMap<P, List<String>>(permissions.length);
        for (P p : permissions) {
            out.put(p, can(p));
        }
        return out;
    }

    /** Collection overload for {@link #can(Permission.Named...)}. */
    public Map<P, List<String>> can(Collection<P> permissions) {
        SdkRefs.requireNotEmpty(permissions, "can(Collection)", "permission");
        LinkedHashMap<P, List<String>> out = new LinkedHashMap<P, List<String>>(permissions.size());
        for (P p : permissions) {
            out.put(p, can(p));
        }
        return out;
    }

    /**
     * Union reverse lookup — ids that the subject can perform <b>any</b> of
     * the given permissions on. Preserves insertion order of first
     * occurrence. Sends one RPC per permission under the hood.
     */
    @SafeVarargs
    public final List<String> canAny(P... permissions) {
        return ResourceLookupSupport.canAny(
                factory, subject, SdkRefs.permissionNames(permissions, "canAny(...)"), limit, "canAny(...)");
    }

    /**
     * Intersection reverse lookup — ids that the subject can perform
     * <b>every</b> given permission on. Sends one RPC per permission and
     * intersects client-side.
     */
    @SafeVarargs
    public final List<String> canAll(P... permissions) {
        return ResourceLookupSupport.canAll(
                factory, subject, SdkRefs.permissionNames(permissions, "canAll(...)"), limit, "canAll(...)");
    }
}
