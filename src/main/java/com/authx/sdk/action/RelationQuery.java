package com.authx.sdk.action;

import com.authx.sdk.model.*;
import com.authx.sdk.transport.SdkTransport;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Fluent query for reading relationships on a resource.
 */
public class RelationQuery {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String[] relations;
    private Consistency consistency = Consistency.minimizeLatency();

    /** Internal — use {@link com.authx.sdk.ResourceHandle} entry points. */
    public RelationQuery(String resourceType, String resourceId, SdkTransport transport,
                         String[] relations) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.relations = relations;
    }

    public RelationQuery withConsistency(Consistency consistency) {
        this.consistency = consistency;
        return this;
    }

    public List<Tuple> fetch() {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        if (relations == null || relations.length == 0) {
            return transport.readRelationships(resource, null, consistency);
        }
        List<Tuple> result = new ArrayList<>();
        for (String rel : relations) {
            result.addAll(transport.readRelationships(resource, Relation.of(rel), consistency));
        }
        return result;
    }

    public Set<Tuple> fetchSet() {
        return new HashSet<>(fetch());
    }

    public int fetchCount() {
        return fetch().size();
    }

    public boolean fetchExists() {
        return !fetch().isEmpty();
    }

    public Optional<Tuple> fetchFirst() {
        var list = fetch();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    public List<String> fetchSubjectIds() {
        return fetch().stream().map(Tuple::subjectId).toList();
    }

    public Set<String> fetchSubjectIdSet() {
        return new HashSet<>(fetchSubjectIds());
    }

    /** Group by relation name, returning full Tuples per relation. */
    public Map<String, List<Tuple>> groupByRelationTuples() {
        return fetch().stream().collect(Collectors.groupingBy(Tuple::relation));
    }

    /**
     * Group by relation name, returning subject IDs per relation.
     * Example: {"editor": ["alice","bob"], "viewer": ["charlie"]}
     */
    public Map<String, List<String>> groupByRelation() {
        return fetch().stream().collect(Collectors.groupingBy(
                Tuple::relation,
                Collectors.mapping(Tuple::subjectId, Collectors.toList())));
    }
}
