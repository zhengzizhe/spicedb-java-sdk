package com.authx.sdk.transport;

import com.authx.sdk.model.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Internal transport abstraction. Real impl uses gRPC (SpiceDB).
 * InMemory impl stores relationships in a HashMap for testing.
 *
 * <p>All methods accept value objects instead of scattered String parameters.
 *
 * <p>Method groups are split into focused sub-interfaces:
 * <ul>
 *   <li>{@link SdkCheckTransport} — check, checkBulk, checkBulkMulti</li>
 *   <li>{@link SdkWriteTransport} — writeRelationships, deleteRelationships, deleteByFilter</li>
 *   <li>{@link SdkLookupTransport} — lookupSubjects, lookupResources</li>
 *   <li>{@link SdkReadTransport} — readRelationships</li>
 *   <li>{@link SdkExpandTransport} — expand</li>
 * </ul>
 */
public interface SdkTransport extends SdkCheckTransport, SdkWriteTransport, SdkLookupTransport,
                                       SdkReadTransport, SdkExpandTransport, AutoCloseable {

    @Override
    void close();

    // ---- Default implementations for optional operations ----

    /**
     * Bulk check: multiple (resource, permission, subject) tuples in one RPC.
     * Used by checkAll() to avoid N sequential calls.
     * Default implementation falls back to sequential individual checks.
     */
    @Override
    default List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
        List<CheckResult> results = new ArrayList<>(items.size());
        for (SdkTransport.BulkCheckItem item : items) {
            results.add(check(CheckRequest.of(item.resource(), item.permission(), item.subject(), consistency)));
        }
        return results;
    }

    /**
     * Delete all relationships matching the filter atomically.
     * Used by revokeAll() to avoid read-then-delete race conditions.
     *
     * @param optionalRelation null to delete ALL relations for this subject on this resource
     */
    @Override
    default WriteResult deleteByFilter(ResourceRef resource, SubjectRef subject,
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
        if (updates.isEmpty()) return new WriteResult(null, 0);
        return deleteRelationships(updates);
    }

    /**
     * Expand the permission tree for a resource/permission pair.
     * Default throws UnsupportedOperationException — only GrpcTransport implements this.
     */
    @Override
    default ExpandTree expand(ResourceRef resource, Permission permission, Consistency consistency) {
        throw new UnsupportedOperationException("expand not supported by this transport");
    }

    // ---- Nested shared records ----

    /**
     * Bulk check item: a single (resource, permission, subject) tuple.
     */
    record BulkCheckItem(ResourceRef resource, Permission permission, SubjectRef subject) {}

    /**
     * A relationship write/delete operation.
     */
    record RelationshipUpdate(
            Operation operation,
            ResourceRef resource,
            Relation relation,
            SubjectRef subject,
            CaveatRef caveat,
            Instant expiresAt
    ) {
        public enum Operation { TOUCH, DELETE }

        /**
         * Convenience constructor without caveat/expiry.
         */
        public RelationshipUpdate(Operation operation,
                                  ResourceRef resource,
                                  Relation relation,
                                  SubjectRef subject) {
            this(operation, resource, relation, subject, null, null);
        }
    }
}
