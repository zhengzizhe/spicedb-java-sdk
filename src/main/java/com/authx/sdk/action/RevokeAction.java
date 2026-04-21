package com.authx.sdk.action;

import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.RevokeResult;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Fluent action for revoking specific relations from subjects.
 *
 * <p>Subjects come in as programmatic {@link SubjectRef} values or canonical
 * strings ({@code "user:alice"}, {@code "group:eng#member"}, {@code "user:*"}).
 * The SDK does not assume a default subject type.
 */
public class RevokeAction {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String[] relations;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public RevokeAction(String resourceType, String resourceId, SdkTransport transport,
                        String[] relations) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.relations = relations;
    }

    /** Revoke the relation(s) from the given {@link SubjectRef subjects}. */
    public RevokeResult from(SubjectRef... subjects) {
        return from(Arrays.asList(subjects));
    }

    /** Collection overload of {@link #from(SubjectRef...)}. */
    public RevokeResult from(Collection<SubjectRef> subjects) {
        return deleteRelationships(List.copyOf(subjects));
    }

    /**
     * Revoke the relation(s) from the given canonical subject strings.
     * See {@link GrantAction#to(String...)} for the accepted format.
     *
     * @throws IllegalArgumentException if any string is not a valid subject ref
     */
    public RevokeResult from(String... subjectRefs) {
        return deleteRelationships(Arrays.stream(subjectRefs).map(SubjectRef::parse).toList());
    }

    private RevokeResult deleteRelationships(List<SubjectRef> subjects) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        List<RelationshipUpdate> updates = new ArrayList<>();
        for (String rel : relations) {
            for (SubjectRef sub : subjects) {
                updates.add(new RelationshipUpdate(
                        Operation.DELETE,
                        resource,
                        Relation.of(rel),
                        sub));
            }
        }
        return transport.deleteRelationships(updates);
    }
}
