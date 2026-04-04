package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;

import java.util.List;
import java.util.Map;

/**
 * Internal transport abstraction. Real impl uses gRPC (SpiceDB).
 * InMemory impl stores relationships in a HashMap for testing.
 *
 * <p>All methods accept value objects instead of scattered String parameters.
 */
public interface SdkTransport extends AutoCloseable {

    // ---- Permission checks ----
    CheckResult check(CheckRequest request);

    BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects);

    /**
     * Bulk check: multiple (resource, permission, subject) tuples in one RPC.
     * Used by checkAll() to avoid N sequential calls.
     */
    record BulkCheckItem(ResourceRef resource, Permission permission, SubjectRef subject) {}

    default List<CheckResult> checkBulkMulti(List<BulkCheckItem> items,
                                              Consistency consistency) {
        List<CheckResult> results = new java.util.ArrayList<>(items.size());
        for (var item : items) {
            results.add(check(CheckRequest.of(item.resource(), item.permission(), item.subject(), consistency)));
        }
        return results;
    }

    // ---- Relationship write/delete ----
    GrantResult writeRelationships(List<RelationshipUpdate> updates);

    RevokeResult deleteRelationships(List<RelationshipUpdate> updates);

    // ---- Relationship read ----
    List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency);

    // ---- Lookup ----
    List<String> lookupSubjects(LookupSubjectsRequest request, Consistency consistency);

    List<String> lookupResources(LookupResourcesRequest request, Consistency consistency);

    // ---- Filter-based delete (atomic, no TOCTOU) ----

    /**
     * Delete all relationships matching the filter atomically.
     * Used by revokeAll() to avoid read-then-delete race conditions.
     *
     * @param optionalRelation null to delete ALL relations for this subject on this resource
     */
    default RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                        Relation optionalRelation) {
        // Default fallback: read-then-delete (InMemoryTransport, etc.)
        List<Tuple> existing;
        if (optionalRelation != null) {
            existing = readRelationships(resource, optionalRelation, Consistency.full());
        } else {
            existing = readRelationships(resource, null, Consistency.full());
        }
        List<RelationshipUpdate> updates = existing.stream()
                .filter(t -> t.subjectType().equals(subject.type()) && t.subjectId().equals(subject.id()))
                .map(t -> new RelationshipUpdate(
                        RelationshipUpdate.Operation.DELETE,
                        ResourceRef.of(t.resourceType(), t.resourceId()),
                        Relation.of(t.relation()),
                        SubjectRef.of(t.subjectType(), t.subjectId(), t.subjectRelation())))
                .toList();
        if (updates.isEmpty()) return new RevokeResult(null, 0);
        return deleteRelationships(updates);
    }

    // ---- Expand ----
    default ExpandTree expand(ResourceRef resource, Permission permission, Consistency consistency) {
        throw new UnsupportedOperationException("expand not supported by this transport");
    }

    @Override
    void close();

    /**
     * A relationship write/delete operation.
     */
    record RelationshipUpdate(
            Operation operation,
            ResourceRef resource,
            Relation relation,
            SubjectRef subject,
            String caveatName, Map<String, Object> caveatContext,
            java.time.Instant expiresAt
    ) {
        public enum Operation { TOUCH, DELETE }

        /**
         * Simple constructor without caveat/expiry.
         */
        public RelationshipUpdate(Operation operation,
                                  ResourceRef resource,
                                  Relation relation,
                                  SubjectRef subject) {
            this(operation, resource, relation, subject, null, null, null);
        }
    }
}
