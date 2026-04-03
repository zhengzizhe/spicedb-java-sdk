package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;

import java.util.List;
import java.util.Map;

/**
 * Internal transport abstraction. Real impl uses gRPC (SpiceDB) + HTTP (platform).
 * InMemory impl stores relationships in a HashMap for testing.
 */
public interface SdkTransport extends AutoCloseable {

    // ---- Permission checks ----
    CheckResult check(String resourceType, String resourceId,
                      String permission, String subjectType, String subjectId,
                      Consistency consistency);

    /**
     * Check with caveat context (e.g., IP address, time-based conditions).
     */
    default CheckResult check(String resourceType, String resourceId,
                              String permission, String subjectType, String subjectId,
                              Consistency consistency, Map<String, Object> context) {
        return check(resourceType, resourceId, permission, subjectType, subjectId, consistency);
    }

    BulkCheckResult checkBulk(String resourceType, String resourceId,
                              String permission, List<String> subjectIds, String defaultSubjectType,
                              Consistency consistency);

    // ---- Relationship write/delete ----
    GrantResult writeRelationships(List<RelationshipUpdate> updates);

    RevokeResult deleteRelationships(List<RelationshipUpdate> updates);

    // ---- Relationship read ----
    List<Tuple> readRelationships(String resourceType, String resourceId,
                                 String relation, Consistency consistency);

    // ---- Lookup ----
    List<String> lookupSubjects(String resourceType, String resourceId,
                                String permission, String subjectType,
                                Consistency consistency);

    List<String> lookupResources(String resourceType, String permission,
                                 String subjectType, String subjectId,
                                 Consistency consistency);

    // ---- Expand ----
    default ExpandTree expand(String resourceType, String resourceId,
                              String permission, Consistency consistency) {
        throw new UnsupportedOperationException("expand not supported by this transport");
    }

    @Override
    void close();

    /**
     * A relationship write/delete operation.
     */
    record RelationshipUpdate(
            Operation operation,
            String resourceType, String resourceId,
            String relation,
            String subjectType, String subjectId, String subjectRelation,
            String caveatName, Map<String, Object> caveatContext,
            java.time.Instant expiresAt
    ) {
        public enum Operation { TOUCH, DELETE }

        /**
         * Simple constructor without caveat/expiry.
         */
        public RelationshipUpdate(Operation operation,
                                  String resourceType, String resourceId,
                                  String relation,
                                  String subjectType, String subjectId, String subjectRelation) {
            this(operation, resourceType, resourceId, relation,
                    subjectType, subjectId, subjectRelation, null, null, null);
        }
    }
}
