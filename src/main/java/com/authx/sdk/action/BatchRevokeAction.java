package com.authx.sdk.action;

import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

/**
 * Fluent action for revoking relations within a batch operation.
 *
 * <p>Subjects come in as programmatic {@link SubjectRef} values or canonical
 * strings. The SDK does not assume a default subject type.
 */
public class BatchRevokeAction {
    private final BatchBuilder batch;
    private final String resourceType;
    private final String resourceId;
    private final String[] relations;

    /** Internal — use {@link BatchBuilder#revoke(String...)} entry point. */
    public BatchRevokeAction(BatchBuilder batch, String resourceType, String resourceId,
                             String[] relations) {
        this.batch = batch;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.relations = relations;
    }

    /** Revoke the relation(s) from the given {@link SubjectRef subjects}. */
    public BatchBuilder from(SubjectRef... subjects) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        for (String rel : relations) {
            for (SubjectRef sub : subjects) {
                batch.addUpdate(new RelationshipUpdate(
                        Operation.DELETE, resource, Relation.of(rel), sub));
            }
        }
        return batch;
    }

    /**
     * Revoke the relation(s) from the given canonical subject strings.
     * See {@link GrantAction#to(String...)} for the accepted format.
     */
    public BatchBuilder from(String... subjectRefs) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        for (String rel : relations) {
            for (String ref : subjectRefs) {
                batch.addUpdate(new RelationshipUpdate(
                        Operation.DELETE, resource, Relation.of(rel), SubjectRef.parse(ref)));
            }
        }
        return batch;
    }
}
