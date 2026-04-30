package com.authx.sdk.transport;

import java.util.List;

/**
 * Operation metadata derived from the first relationship update in a batch.
 *
 * <p>Transport wrappers use this only for policy, telemetry, and logging
 * labels. The actual write payload remains the original update list.
 */
record RelationshipBatchInfo(
        String resourceType,
        String resourceId,
        String relation,
        String subjectType,
        String subjectId,
        String subjectRef
) {
    static RelationshipBatchInfo from(List<SdkTransport.RelationshipUpdate> updates) {
        if (updates.isEmpty()) {
            return new RelationshipBatchInfo("", "", null, "", "", null);
        }
        SdkTransport.RelationshipUpdate first = updates.getFirst();
        return new RelationshipBatchInfo(
                first.resource().type(),
                first.resource().id(),
                first.relation().name(),
                first.subject().type(),
                first.subject().id(),
                first.subject().toRefString());
    }
}
