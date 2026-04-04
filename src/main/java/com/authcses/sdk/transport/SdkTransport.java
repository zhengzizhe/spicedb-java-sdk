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

    /**
     * Bulk check: multiple (resource, permission, subject) tuples in one RPC.
     * Used by checkAll() to avoid N sequential calls.
     */
    record BulkCheckItem(String resourceType, String resourceId,
                         String permission, String subjectType, String subjectId) {}

    default List<CheckResult> checkBulkMulti(List<BulkCheckItem> items,
                                              Consistency consistency) {
        List<CheckResult> results = new java.util.ArrayList<>(items.size());
        for (var item : items) {
            results.add(check(item.resourceType(), item.resourceId(),
                    item.permission(), item.subjectType(), item.subjectId(),
                    consistency));
        }
        return results;
    }

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

    /** Lookup subjects with limit. 0 = unlimited. */
    default List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency, int limit) {
        var all = lookupSubjects(resourceType, resourceId, permission, subjectType, consistency);
        return limit > 0 && all.size() > limit ? all.subList(0, limit) : all;
    }

    List<String> lookupResources(String resourceType, String permission,
                                 String subjectType, String subjectId,
                                 Consistency consistency);

    /** Lookup resources with limit. 0 = unlimited. */
    default List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency, int limit) {
        var all = lookupResources(resourceType, permission, subjectType, subjectId, consistency);
        return limit > 0 && all.size() > limit ? all.subList(0, limit) : all;
    }

    // ---- Filter-based delete (atomic, no TOCTOU) ----

    /**
     * Delete all relationships matching the filter atomically.
     * Used by revokeAll() to avoid read-then-delete race conditions.
     *
     * @param optionalRelation null to delete ALL relations for this subject on this resource
     */
    default RevokeResult deleteByFilter(String resourceType, String resourceId,
                                        String subjectType, String subjectId,
                                        String optionalRelation) {
        // Default fallback: read-then-delete (InMemoryTransport, etc.)
        List<Tuple> existing;
        if (optionalRelation != null) {
            existing = readRelationships(resourceType, resourceId, optionalRelation, Consistency.full());
        } else {
            existing = readRelationships(resourceType, resourceId, null, Consistency.full());
        }
        List<RelationshipUpdate> updates = existing.stream()
                .filter(t -> t.subjectType().equals(subjectType) && t.subjectId().equals(subjectId))
                .map(t -> new RelationshipUpdate(
                        RelationshipUpdate.Operation.DELETE,
                        t.resourceType(), t.resourceId(), t.relation(),
                        t.subjectType(), t.subjectId(), t.subjectRelation()))
                .toList();
        if (updates.isEmpty()) return new RevokeResult(null, 0);
        return deleteRelationships(updates);
    }

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
