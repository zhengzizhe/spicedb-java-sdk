package com.authx.sdk.action;

import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import java.util.Arrays;
import java.util.Collection;

/**
 * Fluent action for granting relations within a batch operation.
 */
public class BatchGrantAction {
    private final BatchBuilder batch;
    private final String resourceType;
    private final String resourceId;
    private final String defaultSubjectType;
    private final String[] relations;

    /** Internal — use {@link BatchBuilder#grant(String...)} entry point. */
    public BatchGrantAction(BatchBuilder batch, String resourceType, String resourceId,
                            String defaultSubjectType, String[] relations) {
        this.batch = batch;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.defaultSubjectType = defaultSubjectType;
        this.relations = relations;
    }

    /** Grant the relation(s) to the given user ids and return to the batch builder. */
    public BatchBuilder to(String... userIds) {
        return to(Arrays.asList(userIds));
    }

    /** Grant the relation(s) to the given user ids and return to the batch builder. */
    public BatchBuilder to(Collection<String> userIds) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        for (String rel : relations) {
            for (String uid : userIds) {
                batch.addUpdate(new RelationshipUpdate(
                        Operation.TOUCH,
                        resource,
                        Relation.of(rel),
                        SubjectRef.of(defaultSubjectType, uid, null)));
            }
        }
        return batch;
    }

    /** Grant the relation(s) to the given subject refs and return to the batch builder. */
    public BatchBuilder toSubjects(String... subjectRefs) {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        for (String rel : relations) {
            for (String ref : subjectRefs) {
                SubjectRef parsed = SubjectRef.parse(ref);
                batch.addUpdate(new RelationshipUpdate(
                        Operation.TOUCH,
                        resource,
                        Relation.of(rel),
                        parsed));
            }
        }
        return batch;
    }
}
