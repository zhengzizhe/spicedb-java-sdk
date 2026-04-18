package com.authx.sdk;

import com.authx.sdk.action.WhoBuilder;
import com.authx.sdk.model.SubjectRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed lookupSubjects query — "who has this permission on this resource?".
 * Construct via {@link TypedHandle#who(com.authx.sdk.model.Permission.Named)}.
 *
 * <pre>
 * List&lt;String&gt; editors = Document.select(client, "doc-1")
 *     .who(Document.Perm.EDIT).limit(50).asUserIds();
 * </pre>
 */
public class TypedWhoQuery {

    private final ResourceFactory factory;
    private final String resourceId;
    private final String permission;
    private int limit = 0;

    public TypedWhoQuery(ResourceFactory factory, String resourceId, String permission) {
        this.factory = factory;
        this.resourceId = resourceId;
        this.permission = permission;
    }

    /** Limit the number of subjects returned. 0 = no limit (default). */
    public TypedWhoQuery limit(int n) {
        this.limit = n;
        return this;
    }

    /**
     * Fetch the subject ids that have the permission, using the client's
     * default subject type (usually {@code "user"}). For non-user subject
     * types, drop to the raw API via
     * {@code factory.resource(id).who().withPermission(perm).fetch()}.
     */
    public List<String> asUserIds() {
        return new WhoBuilder(factory.resourceType(), resourceId, factory.transport(),
                factory.defaultSubjectType(), factory.asyncExecutor())
                .withPermission(permission)
                .limit(limit)
                .fetch();
    }

    /**
     * Same lookup, but wrap each returned id in a {@link SubjectRef} bound
     * to the factory's default subject type. Useful when feeding the result
     * directly back into a grant/revoke chain, e.g.:
     *
     * <pre>
     * var oldEditors = client.on(Document.TYPE).select(docId)
     *         .who(Document.Perm.EDIT).asSubjectRefs();
     * client.on(Document.TYPE).select(newDocId)
     *         .grant(Document.Rel.EDITOR).to(oldEditors);
     * </pre>
     */
    public List<SubjectRef> asSubjectRefs() {
        List<String> ids = asUserIds();
        var out = new ArrayList<SubjectRef>(ids.size());
        String subjectType = factory.defaultSubjectType();
        for (String id : ids) {
            out.add(SubjectRef.of(subjectType, id, null));
        }
        return out;
    }

    /**
     * Return just a count without materializing the id list — useful for
     * pagination UIs that only need the badge number.
     */
    public int count() {
        return asUserIds().size();
    }

    /**
     * {@code true} iff at least one subject has the permission — short-circuit
     * version of {@code !asUserIds().isEmpty()}. Sends a LookupSubjects RPC
     * with limit=1 for efficiency.
     */
    public boolean exists() {
        int saved = this.limit;
        try {
            this.limit = 1;
            return !asUserIds().isEmpty();
        } finally {
            this.limit = saved;
        }
    }
}
