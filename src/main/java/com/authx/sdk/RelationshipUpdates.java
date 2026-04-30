package com.authx.sdk;

import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;
import java.util.ArrayList;
import java.util.List;

/** Package-private relationship update fan-out helper for write builders. */
final class RelationshipUpdates {

    private RelationshipUpdates() {}

    static void addTo(ArrayList<RelationshipUpdate> updates, String resourceType, String resourceId,
                      Operation operation, String[] relations, SubjectRef[] subjects) {
        ensureCapacity(updates, 1, relations.length, subjects.length);
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        addForResource(updates, resource, operation, relations, subjects);
    }

    static void addTo(ArrayList<RelationshipUpdate> updates, String resourceType, List<String> resourceIds,
                      Operation operation, String[] relations, SubjectRef[] subjects) {
        ensureCapacity(updates, resourceIds.size(), relations.length, subjects.length);
        for (String resourceId : resourceIds) {
            ResourceRef resource = ResourceRef.of(resourceType, resourceId);
            addForResource(updates, resource, operation, relations, subjects);
        }
    }

    private static void addForResource(ArrayList<RelationshipUpdate> updates, ResourceRef resource,
                                       Operation operation, String[] relations, SubjectRef[] subjects) {
        for (String relation : relations) {
            Relation rel = Relation.of(relation);
            for (SubjectRef subject : subjects) {
                updates.add(new RelationshipUpdate(operation, resource, rel, subject));
            }
        }
    }

    private static void ensureCapacity(ArrayList<RelationshipUpdate> updates, int resourceCount,
                                       int relationCount, int subjectCount) {
        long additional = (long) resourceCount * relationCount * subjectCount;
        if (additional > Integer.MAX_VALUE || updates.size() + additional > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("batch operation would create too many relationship updates");
        }
        updates.ensureCapacity(updates.size() + (int) additional);
    }
}
