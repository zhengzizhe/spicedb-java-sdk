package com.authx.sdk;

import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.LookupSubjectsRequest;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed lookupSubjects query — "who (of the given subject type) has this
 * permission on this resource?".
 *
 * <p>Construct via {@link TypedHandle#lookupSubjects(String, Enum)}.
 *
 * <pre>
 * List&lt;String&gt; editors = client.on(Document).select("doc-1")
 *     .lookupSubjects("user", Document.Perm.EDIT)
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

    TypedWhoQuery(ResourceFactory factory, String resourceId,
                          String subjectType, String permission) {
        this.factory = factory;
        this.resourceId = resourceId;
        this.subjectType = subjectType;
        this.permission = permission;
    }

    /** Limit the number of subjects returned. 0 = no limit (default). */
    public TypedWhoQuery limit(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("limit must be >= 0");
        }
        this.limit = n;
        return this;
    }

    /** Fetch the subject ids (of the bound subject type) that have the permission. */
    public List<String> fetchIds() {
        return fetchIds(limit);
    }

    private List<String> fetchIds(int effectiveLimit) {
        LookupSubjectsRequest request = new LookupSubjectsRequest(
                ResourceRef.of(factory.resourceType(), resourceId),
                Permission.of(permission),
                subjectType,
                effectiveLimit,
                Consistency.minimizeLatency());
        return factory.transport().lookupSubjects(request).stream()
                .map(SubjectRef::id)
                .toList();
    }

    /**
     * Same lookup, but wrap each returned id in a {@link SubjectRef} bound to
     * the configured subject type. Useful when feeding the result directly
     * back into a grant/revoke chain:
     *
     * <pre>
     * List&lt;String&gt; oldEditors = client.on(Document).select(docId)
     *         .lookupSubjects("user", Document.Perm.EDIT).asSubjectRefs();
     * client.on(Document).select(newDocId)
     *         .grant(Document.Rel.EDITOR).to(oldEditors);
     * </pre>
     */
    public List<SubjectRef> asSubjectRefs() {
        List<String> ids = fetchIds();
        ArrayList<SubjectRef> out = new ArrayList<SubjectRef>(ids.size());
        for (String id : ids) {
            out.add(SubjectRef.of(subjectType, id));
        }
        return out;
    }

    /** Return the number of matching subject ids. */
    public int count() {
        return fetchIds().size();
    }

    /**
     * {@code true} iff at least one subject has the permission — short-circuit
     * version of {@code !fetchIds().isEmpty()}. Sends a LookupSubjects RPC
     * with limit=1 for efficiency.
     */
    public boolean exists() {
        return !fetchIds(1).isEmpty();
    }
}
