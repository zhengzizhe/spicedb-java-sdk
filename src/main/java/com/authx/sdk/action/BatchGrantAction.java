package com.authx.sdk.action;

import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

/**
 * Fluent action for granting relations within a batch operation.
 *
 * <p>Subjects come in as programmatic {@link SubjectRef} values or canonical
 * strings. The SDK does not assume a default subject type.
 */
public class BatchGrantAction {
    private final BatchBuilder batch;
    private final String resourceType;
    private final String resourceId;
    private final String[] relations;

    /** Internal — use {@link BatchBuilder#grant(String...)} entry point. */
    public BatchGrantAction(BatchBuilder batch, String resourceType, String resourceId,
                            String[] relations) {
        this.batch = batch;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.relations = relations;
    }

    /** Grant the relation(s) to the given {@link SubjectRef subjects}. */
    public BatchBuilder to(SubjectRef... subjects) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        for (String rel : relations) {
            for (SubjectRef sub : subjects) {
                batch.addUpdate(new RelationshipUpdate(
                        Operation.TOUCH, resource, Relation.of(rel), sub));
            }
        }
        return batch;
    }

    /**
     * Grant the relation(s) to the given canonical subject strings.
     * See {@link GrantAction#to(String...)} for the accepted format.
     */
    public BatchBuilder to(String... subjectRefs) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        for (String rel : relations) {
            for (String ref : subjectRefs) {
                batch.addUpdate(new RelationshipUpdate(
                        Operation.TOUCH, resource, Relation.of(rel), SubjectRef.parse(ref)));
            }
        }
        return batch;
    }

    /** {@link Iterable} overload of {@link #to(String...)}. */
    public BatchBuilder to(Iterable<String> subjectRefs) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        for (String rel : relations) {
            for (String ref : subjectRefs) {
                batch.addUpdate(new RelationshipUpdate(
                        Operation.TOUCH, resource, Relation.of(rel), SubjectRef.parse(ref)));
            }
        }
        return batch;
    }
}
