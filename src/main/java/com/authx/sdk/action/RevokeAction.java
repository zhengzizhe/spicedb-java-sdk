package com.authx.sdk.action;

import com.authx.sdk.model.*;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import java.util.*;

/**
 * Fluent action for revoking specific relations from subjects.
 */
public class RevokeAction {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final String[] relations;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public RevokeAction(String resourceType, String resourceId, SdkTransport transport,
                        String defaultSubjectType, String[] relations) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.relations = relations;
    }

    public RevokeResult from(String... userIds) {
        return from(Arrays.asList(userIds));
    }

    public RevokeResult from(Collection<String> userIds) {
        return deleteRelationships(userIds.stream()
                .map(id -> SubjectRef.of(defaultSubjectType, id, null))
                .toList());
    }

    public RevokeResult fromSubjects(String... subjectRefs) {
        return fromSubjects(Arrays.asList(subjectRefs));
    }

    public RevokeResult fromSubjects(Collection<String> subjectRefs) {
        return deleteRelationships(subjectRefs.stream().map(SubjectRef::parse).toList());
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
