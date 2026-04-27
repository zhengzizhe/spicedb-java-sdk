package com.authx.sdk.transport;

import com.authx.sdk.model.*;
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
    public CheckResult check(CheckRequest request) {
        // In-memory: check if any relationship grants this permission.
        // Since we don't have schema to compute permissions, we match permission == relation.
        String key = tupleKey(request.resource().type(), request.resource().id(),
                request.permission().name(),
                request.subject().type(), request.subject().id(), null);
        if (store.containsKey(key)) {
            return CheckResult.allowed(currentToken());
        }
        return CheckResult.denied(currentToken());
    }

    @Override
    public BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects) {
        Map<String, CheckResult> results = new LinkedHashMap<>();
        for (SubjectRef sub : subjects) {
            CheckRequest subRequest = CheckRequest.of(request.resource(), request.permission(), sub, request.consistency());
            results.put(sub.id(), check(subRequest));
        }
        return new BulkCheckResult(results);
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        for (SdkTransport.RelationshipUpdate u : updates) {
            String key = tupleKey(u.resource().type(), u.resource().id(), u.relation().name(),
                    u.subject().type(), u.subject().id(), u.subject().relation());
            if (u.operation() == RelationshipUpdate.Operation.DELETE) {
                store.remove(key);
            } else {
                store.put(key, new Tuple(u.resource().type(), u.resource().id(), u.relation().name(),
                        u.subject().type(), u.subject().id(), u.subject().relation()));
            }
        }
        return new GrantResult(nextToken(), updates.size());
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        for (SdkTransport.RelationshipUpdate u : updates) {
            String key = tupleKey(u.resource().type(), u.resource().id(), u.relation().name(),
                    u.subject().type(), u.subject().id(), u.subject().relation());
            store.remove(key);
        }
        return new RevokeResult(nextToken(), updates.size());
    }

    @Override
    public List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency) {
        return store.values().stream()
                .filter(t -> t.resourceType().equals(resource.type()))
                .filter(t -> t.resourceId().equals(resource.id()))
                .filter(t -> relation == null || t.relation().equals(relation.name()))
                .toList();
    }

    @Override
    public List<SubjectRef> lookupSubjects(LookupSubjectsRequest request) {
        // In-memory: match on relation == permission (no recursive permission computation)
        List<SubjectRef> all = store.values().stream()
                .filter(t -> t.resourceType().equals(request.resource().type()))
                .filter(t -> t.resourceId().equals(request.resource().id()))
                .filter(t -> t.relation().equals(request.permission().name()))
                .filter(t -> t.subjectType().equals(request.subjectType()))
                .map(t -> SubjectRef.of(t.subjectType(), t.subjectId(), t.subjectRelation()))
                .toList();
        return request.limit() > 0 && all.size() > request.limit() ? all.subList(0, request.limit()) : all;
    }

    @Override
    public List<ResourceRef> lookupResources(LookupResourcesRequest request) {
        // In-memory: match on relation == permission (no recursive permission computation)
        List<ResourceRef> all = store.values().stream()
                .filter(t -> t.resourceType().equals(request.resourceType()))
                .filter(t -> t.relation().equals(request.permission().name()))
                .filter(t -> t.subjectType().equals(request.subject().type()))
                .filter(t -> t.subjectId().equals(request.subject().id()))
                .map(t -> ResourceRef.of(t.resourceType(), t.resourceId()))
                .toList();
        return request.limit() > 0 && all.size() > request.limit() ? all.subList(0, request.limit()) : all;
    }

    @Override
    public ExpandTree expand(ResourceRef resource, Permission permission, Consistency consistency) {
        // In-memory: collect all direct subjects with the given relation (= permission),
        // return as a single leaf node. No recursive permission computation.
        List<String> subjects = store.values().stream()
                .filter(t -> t.resourceType().equals(resource.type()))
                .filter(t -> t.resourceId().equals(resource.id()))
                .filter(t -> t.relation().equals(permission.name()))
                .map(t -> {
                    String ref = t.subjectType() + ":" + t.subjectId();
                    return t.subjectRelation() != null ? ref + "#" + t.subjectRelation() : ref;
                })
                .toList();
        return new ExpandTree("leaf", resource.type(), resource.id(), permission.name(), List.of(), subjects);
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
