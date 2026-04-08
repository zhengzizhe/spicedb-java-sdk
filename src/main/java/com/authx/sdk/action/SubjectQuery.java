package com.authx.sdk.action;

import com.authx.sdk.model.*;
import com.authx.sdk.transport.SdkTransport;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Fluent query for looking up subjects that have a permission/relation on a resource.
 */
public class SubjectQuery {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String defaultSubjectType;
    private final Executor asyncExecutor;
    private final String permissionOrRelation;
    private final boolean isPermission;
    private Consistency consistency = Consistency.minimizeLatency();
    private int limit = 0;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public SubjectQuery(String resourceType, String resourceId, SdkTransport transport,
                        String defaultSubjectType, Executor asyncExecutor,
                        String permissionOrRelation, boolean isPermission) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
        this.asyncExecutor = asyncExecutor;
        this.permissionOrRelation = permissionOrRelation;
        this.isPermission = isPermission;
    }

    public SubjectQuery withConsistency(Consistency consistency) {
        this.consistency = consistency;
        return this;
    }

    /** Limit the number of results. 0 = unlimited (default). */
    public SubjectQuery limit(int limit) {
        this.limit = limit;
        return this;
    }

    public List<String> fetch() {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        if (isPermission) {
            var request = new LookupSubjectsRequest(resource,
                    Permission.of(permissionOrRelation), defaultSubjectType, limit, consistency);
            return transport.lookupSubjects(request).stream()
                    .map(SubjectRef::id).toList();
        } else {
            var results = transport.readRelationships(
                            resource, Relation.of(permissionOrRelation), consistency).stream()
                    .map(Tuple::subjectId)
                    .toList();
            return limit > 0 && results.size() > limit ? results.subList(0, limit) : results;
        }
    }

    public Set<String> fetchSet() {
        return new HashSet<>(fetch());
    }

    public Optional<String> fetchFirst() {
        var list = fetch();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    public int fetchCount() {
        return fetch().size();
    }

    public boolean fetchExists() {
        // Fetch with limit=1 to avoid pulling all subjects just to check existence
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        if (isPermission) {
            var request = new LookupSubjectsRequest(resource,
                    Permission.of(permissionOrRelation), defaultSubjectType, 1, consistency);
            return !transport.lookupSubjects(request).isEmpty();
        } else {
            var results = transport.readRelationships(
                    resource, Relation.of(permissionOrRelation), consistency);
            return !results.isEmpty();
        }
    }

    public CompletableFuture<List<String>> fetchAsync() {
        return CompletableFuture.supplyAsync(this::fetch, asyncExecutor);
    }
}
