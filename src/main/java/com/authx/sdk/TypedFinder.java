package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.SubjectRef;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed lookupResources query — "which resources of this type can this
 * subject perform permission X on?". Construct via
 * {@code Document.findBy(client, subject)} or equivalent generated
 * static method on the resource type's constants class.
 *
 * <pre>
 * List&lt;String&gt; myDocs = Document.findBy(client, SubjectRef.of("user", "alice"))
 *     .limit(100)
 *     .can(Document.Perm.VIEW);
 * </pre>
 */
public class TypedFinder<P extends Permission.Named> {

    private final ResourceFactory factory;
    private final SubjectRef subject;
    private int limit = 0;

    public TypedFinder(ResourceFactory factory, SubjectRef subject) {
        this.factory = factory;
        this.subject = subject;
    }

    /** Limit the number of resources returned. 0 = no limit. */
    public TypedFinder<P> limit(int n) {
        this.limit = n;
        return this;
    }

    /**
     * Fetch the ids of resources of the bound type that the subject can
     * perform {@code permission} on (reverse lookup via
     * {@code LookupResources}).
     */
    public List<String> can(P permission) {
        return factory.lookup()
                .withPermission(permission.permissionName())
                .by(subject)
                .limit(limit)
                .fetch();
    }

    /**
     * Multi-permission reverse lookup — returns a map keyed on each
     * permission. One {@code LookupResources} RPC per permission (SpiceDB
     * does not bulk multiple permissions into a single call). When you only
     * need a single flat id list, use {@link #can(Permission.Named)}.
     *
     * <pre>
     * Map&lt;Document.Perm, List&lt;String&gt;&gt; byPerm =
     *     client.on(Document).findByUser(userId).limit(200)
     *           .can(Document.Perm.VIEW, Document.Perm.EDIT);
     *
     * List&lt;String&gt; viewable = byPerm.get(Document.Perm.VIEW);
     * List&lt;String&gt; editable = byPerm.get(Document.Perm.EDIT);
     * </pre>
     */
    @SafeVarargs
    public final Map<P, List<String>> can(P... permissions) {
        if (permissions == null || permissions.length == 0) return Map.of();
        LinkedHashMap<P, List<String>> out = new LinkedHashMap<P, List<String>>(permissions.length);
        for (P p : permissions) {
            out.put(p, can(p));
        }
        return out;
    }

    /** Collection overload for {@link #can(Permission.Named...)}. */
    public Map<P, List<String>> can(Collection<P> permissions) {
        if (permissions == null || permissions.isEmpty()) return Map.of();
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
        if (permissions == null || permissions.length == 0) return List.of();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (P p : permissions) seen.addAll(can(p));
        return List.copyOf(seen);
    }

    /**
     * Intersection reverse lookup — ids that the subject can perform
     * <b>every</b> given permission on. Sends one RPC per permission and
     * intersects client-side.
     */
    @SafeVarargs
    public final List<String> canAll(P... permissions) {
        if (permissions == null || permissions.length == 0) return List.of();
        Set<String> acc = new LinkedHashSet<>(can(permissions[0]));
        for (int i = 1; i < permissions.length && !acc.isEmpty(); i++) {
            acc.retainAll(new HashSet<>(can(permissions[i])));
        }
        return List.copyOf(acc);
    }
}
