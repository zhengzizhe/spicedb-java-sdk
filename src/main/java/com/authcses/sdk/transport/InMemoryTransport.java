package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory transport for unit testing. No external services needed.
 * <p>
 * Behavior:
 * - grant/revoke → stores in a ConcurrentHashMap
 * - check → exact match on (resource, relation, subject), no permission recursion
 * - who/lookup → filters stored relationships
 * - No schema validation
 */
public class InMemoryTransport implements SdkTransport {

    // Key: "resourceType:resourceId#relation@subjectType:subjectId[#subjectRelation]"
    private final ConcurrentHashMap<String, Tuple> store = new ConcurrentHashMap<>();
    private final AtomicLong tokenCounter = new AtomicLong(0);

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency) {
        // In-memory: check if any relationship grants this permission.
        // Since we don't have schema to compute permissions, we match permission == relation.
        String key = tupleKey(resourceType, resourceId, permission, subjectType, subjectId, null);
        if (store.containsKey(key)) {
            return CheckResult.allowed(currentToken());
        }
        return CheckResult.denied(currentToken());
    }

    @Override
    public BulkCheckResult checkBulk(String resourceType, String resourceId,
                                     String permission, List<String> subjectIds, String defaultSubjectType,
                                     Consistency consistency) {
        Map<String, CheckResult> results = new LinkedHashMap<>();
        for (String sid : subjectIds) {
            results.put(sid, check(resourceType, resourceId, permission, defaultSubjectType, sid, consistency));
        }
        return new BulkCheckResult(results);
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        for (var u : updates) {
            String key = tupleKey(u.resourceType(), u.resourceId(), u.relation(),
                    u.subjectType(), u.subjectId(), u.subjectRelation());
            if (u.operation() == RelationshipUpdate.Operation.DELETE) {
                store.remove(key);
            } else {
                store.put(key, new Tuple(u.resourceType(), u.resourceId(), u.relation(),
                        u.subjectType(), u.subjectId(), u.subjectRelation()));
            }
        }
        return new GrantResult(nextToken(), updates.size());
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        for (var u : updates) {
            String key = tupleKey(u.resourceType(), u.resourceId(), u.relation(),
                    u.subjectType(), u.subjectId(), u.subjectRelation());
            store.remove(key);
        }
        return new RevokeResult(nextToken(), updates.size());
    }

    @Override
    public List<Tuple> readRelationships(String resourceType, String resourceId,
                                         String relation, Consistency consistency) {
        return store.values().stream()
                .filter(t -> t.resourceType().equals(resourceType))
                .filter(t -> t.resourceId().equals(resourceId))
                .filter(t -> relation == null || t.relation().equals(relation))
                .toList();
    }

    @Override
    public List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency) {
        // In-memory: match on relation == permission (no recursive permission computation)
        return store.values().stream()
                .filter(t -> t.resourceType().equals(resourceType))
                .filter(t -> t.resourceId().equals(resourceId))
                .filter(t -> t.relation().equals(permission))
                .filter(t -> t.subjectType().equals(subjectType))
                .map(Tuple::subjectId)
                .toList();
    }

    @Override
    public List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency) {
        // In-memory: match on relation == permission (no recursive permission computation)
        return store.values().stream()
                .filter(t -> t.resourceType().equals(resourceType))
                .filter(t -> t.relation().equals(permission))
                .filter(t -> t.subjectType().equals(subjectType))
                .filter(t -> t.subjectId().equals(subjectId))
                .map(Tuple::resourceId)
                .toList();
    }

    @Override
    public void close() {
        store.clear();
    }

    /**
     * Returns a snapshot of all stored tuples (for test assertions).
     */
    public Collection<Tuple> allTuples() {
        return Collections.unmodifiableCollection(store.values());
    }

    public int size() {
        return store.size();
    }

    private String tupleKey(String resType, String resId, String relation,
                            String subType, String subId, String subRelation) {
        String key = resType + ":" + resId + "#" + relation + "@" + subType + ":" + subId;
        if (subRelation != null) key += "#" + subRelation;
        return key;
    }

    private String currentToken() {
        return "zed_mem_" + tokenCounter.get();
    }

    private String nextToken() {
        return "zed_mem_" + tokenCounter.incrementAndGet();
    }
}
