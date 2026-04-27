package com.authx.sdk;

import com.authx.sdk.action.WhoBuilder;
import com.authx.sdk.model.SubjectRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed lookupSubjects query — "who (of the given subject type) has this
 * permission on this resource?".
 *
 * <p>Construct via {@link TypedHandle#who(String, com.authx.sdk.model.Permission.Named)}.
 *
 * <pre>
 * List&lt;String&gt; editors = Document.select(client, "doc-1")
 *     .who("user", Document.Perm.EDIT)
 *     .limit(50)
 *     .fetchIds();
 * </pre>
 */
public class TypedWhoQuery {

    private final ResourceFactory factory;
    private final String resourceId;
    private final String subjectType;
    private final String permission;
    private int limit = 0;

    public TypedWhoQuery(ResourceFactory factory, String resourceId,
                          String subjectType, String permission) {
        this.factory = factory;
        this.resourceId = resourceId;
        this.subjectType = subjectType;
        this.permission = permission;
    }

    /** Limit the number of subjects returned. 0 = no limit (default). */
    public TypedWhoQuery limit(int n) {
        this.limit = n;
        return this;
    }

    /** Fetch the subject ids (of the bound subject type) that have the permission. */
    public List<String> fetchIds() {
        return new WhoBuilder(factory.resourceType(), resourceId, factory.transport(),
                subjectType, factory.asyncExecutor())
                .withPermission(permission)
                .limit(limit)
                .fetch();
    }

    /**
     * Same lookup, but wrap each returned id in a {@link SubjectRef} bound to
     * the configured subject type. Useful when feeding the result directly
     * back into a grant/revoke chain:
     *
     * <pre>
     * List&lt;String&gt; oldEditors = client.on(Document).select(docId)
     *         .who("user", Document.Perm.EDIT).asSubjectRefs();
     * client.on(Document).select(newDocId)
     *         .grant(Document.Rel.EDITOR).to(oldEditors);
     * </pre>
     */
    public List<SubjectRef> asSubjectRefs() {
        List<String> ids = fetchIds();
        java.util.ArrayList<com.authx.sdk.model.SubjectRef> out = new ArrayList<SubjectRef>(ids.size());
        for (String id : ids) {
            out.add(SubjectRef.of(subjectType, id));
        }
        return out;
    }

    /** Return just a count without materializing the id list. */
    public int count() {
        return fetchIds().size();
    }

    /**
     * {@code true} iff at least one subject has the permission — short-circuit
     * version of {@code !fetchIds().isEmpty()}. Sends a LookupSubjects RPC
     * with limit=1 for efficiency.
     */
    public boolean exists() {
        int saved = this.limit;
        try {
            this.limit = 1;
            return !fetchIds().isEmpty();
        } finally {
            this.limit = saved;
        }
    }
}
