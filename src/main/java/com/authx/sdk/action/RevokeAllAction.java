package com.authx.sdk.action;

import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.RevokeResult;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;

import java.util.Arrays;
import java.util.Collection;

/**
 * Fluent action for revoking <b>all</b> matching relationships from a subject
 * using filter-based delete. Used by {@code client.resource(...).revokeAll()}.
 *
 * <p>Subjects come in as programmatic {@link SubjectRef} values or canonical
 * strings. The SDK does not assume a default subject type.
 */
public class RevokeAllAction {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String[] relations;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public RevokeAllAction(String resourceType, String resourceId, SdkTransport transport,
                           String[] relations) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.relations = relations;
    }

    /** Revoke all matching relationships from the given {@link SubjectRef subjects}. */
    public RevokeResult from(SubjectRef... subjects) {
        return from(Arrays.asList(subjects));
    }

    /** Collection overload of {@link #from(SubjectRef...)}. */
    public RevokeResult from(Collection<SubjectRef> subjects) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        int totalDeleted = 0;
        String lastToken = null;

        for (SubjectRef subject : subjects) {
            if (relations == null || relations.length == 0) {
                var result = transport.deleteByFilter(resource, subject, null);
                totalDeleted += result.count();
                if (result.zedToken() != null) lastToken = result.zedToken();
            } else {
                for (String rel : relations) {
                    var result = transport.deleteByFilter(resource, subject, Relation.of(rel));
                    totalDeleted += result.count();
                    if (result.zedToken() != null) lastToken = result.zedToken();
                }
            }
        }

        return new RevokeResult(lastToken, totalDeleted);
    }

    /**
     * Revoke all matching relationships from the given canonical subject
     * strings.
     *
     * @throws IllegalArgumentException if any string is not a valid subject ref
     */
    public RevokeResult from(String... subjectRefs) {
        return from(Arrays.stream(subjectRefs).map(SubjectRef::parse).toArray(SubjectRef[]::new));
    }
}
