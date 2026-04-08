package com.authx.sdk.action;

import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import java.util.Arrays;
import java.util.Collection;

/**
 * Fluent action for revoking relations within a batch operation.
 */
public class BatchRevokeAction {
    private final BatchBuilder batch;
    private final String resourceType;
    private final String resourceId;
    private final String defaultSubjectType;
    private final String[] relations;

    /** Internal — use {@link BatchBuilder#revoke(String...)} entry point. */
    public BatchRevokeAction(BatchBuilder batch, String resourceType, String resourceId,
                             String defaultSubjectType, String[] relations) {
        this.batch = batch;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.defaultSubjectType = defaultSubjectType;
        this.relations = relations;
    }

    /** Revoke the relation(s) from the given user ids and return to the batch builder. */
    public BatchBuilder from(String... userIds) {
        return from(Arrays.asList(userIds));
    }

    /** Revoke the relation(s) from the given user ids and return to the batch builder. */
    public BatchBuilder from(Collection<String> userIds) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        for (String rel : relations) {
            for (String uid : userIds) {
                batch.addUpdate(new RelationshipUpdate(
                        Operation.DELETE,
                        resource,
                        Relation.of(rel),
                        SubjectRef.of(defaultSubjectType, uid, null)));
            }
        }
        return batch;
    }

    /** Revoke the relation(s) from the given subject refs and return to the batch builder. */
    public BatchBuilder fromSubjects(String... subjectRefs) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        for (String rel : relations) {
            for (String ref : subjectRefs) {
                SubjectRef parsed = SubjectRef.parse(ref);
                batch.addUpdate(new RelationshipUpdate(
                        Operation.DELETE,
                        resource,
                        Relation.of(rel),
                        parsed));
            }
        }
        return batch;
    }
}
