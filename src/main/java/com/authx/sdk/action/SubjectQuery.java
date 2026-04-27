package com.authx.sdk.action;

import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.LookupSubjectsRequest;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.Tuple;
import com.authx.sdk.transport.SdkTransport;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Fluent query for looking up subjects that have a permission/relation on a resource.
 */
public class SubjectQuery {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String subjectType;
    private final Executor asyncExecutor;
    private final String permissionOrRelation;
    private final boolean isPermission;
    private Consistency consistency = Consistency.minimizeLatency();
    private int limit = 0;

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public SubjectQuery(String resourceType, String resourceId, SdkTransport transport,
                        String subjectType, Executor asyncExecutor,
                        String permissionOrRelation, boolean isPermission) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.subjectType = subjectType;
        this.asyncExecutor = asyncExecutor;
        this.permissionOrRelation = permissionOrRelation;
        this.isPermission = isPermission;
    }

    /** Override the consistency level for this query. */
    public SubjectQuery withConsistency(Consistency consistency) {
        this.consistency = consistency;
        return this;
    }

    /** Limit the number of results. 0 = unlimited (default). */
    public SubjectQuery limit(int limit) {
        this.limit = limit;
        return this;
    }

    /** Execute the query and return subject ids as a list. */
    public List<String> fetch() {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        if (isPermission) {
            LookupSubjectsRequest request = new LookupSubjectsRequest(resource,
                    Permission.of(permissionOrRelation), subjectType, limit, consistency);
            return transport.lookupSubjects(request).stream()
                    .map(SubjectRef::id).toList();
        } else {
            List<String> results = transport.readRelationships(
                            resource, Relation.of(permissionOrRelation), consistency).stream()
                    .map(Tuple::subjectId)
                    .toList();
            return limit > 0 && results.size() > limit ? results.subList(0, limit) : results;
        }
    }

    /** Execute the query and return subject ids as a set. */
    public Set<String> fetchSet() {
        return new HashSet<>(fetch());
    }

    /** Execute the query and return the first subject id, if any. */
    public Optional<String> fetchFirst() {
        List<String> list = fetch();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    /** Execute the query and return the number of matching subjects. */
    public int fetchCount() {
        return fetch().size();
    }

    /** Execute the query and return whether any matching subjects exist. */
    public boolean fetchExists() {
        // Fetch with limit=1 to avoid pulling all subjects just to check existence
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        if (isPermission) {
            LookupSubjectsRequest request = new LookupSubjectsRequest(resource,
                    Permission.of(permissionOrRelation), subjectType, 1, consistency);
            return !transport.lookupSubjects(request).isEmpty();
        } else {
            List<Tuple> results = transport.readRelationships(
                    resource, Relation.of(permissionOrRelation), consistency);
            return !results.isEmpty();
        }
    }

    /** Asynchronous version of {@link #fetch()}, using the SDK's configured executor. */
    public CompletableFuture<List<String>> fetchAsync() {
        return CompletableFuture.supplyAsync(this::fetch, asyncExecutor);
    }
}
