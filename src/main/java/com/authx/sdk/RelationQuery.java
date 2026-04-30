package com.authx.sdk;

import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.Tuple;
import com.authx.sdk.transport.SdkTransport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fluent query for reading relationships on a selected resource.
 */
public class RelationQuery {
    private final String resourceType;
    private final String resourceId;
    private final SdkTransport transport;
    private final String[] relations;
    private Consistency consistency = Consistency.minimizeLatency();

    RelationQuery(String resourceType, String resourceId, SdkTransport transport,
                  String[] relations) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.transport = transport;
        this.relations = Objects.requireNonNull(relations, "relations").clone();
    }

    /** Override the consistency level for this query. */
    public RelationQuery withConsistency(Consistency consistency) {
        this.consistency = Objects.requireNonNull(consistency, "consistency");
        return this;
    }

    /** Execute the query and return matching relationship tuples. */
    public List<Tuple> fetch() {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        if (relations.length == 0) {
            return transport.readRelationships(resource, null, consistency);
        }
        List<Tuple> result = new ArrayList<Tuple>();
        for (String rel : relations) {
            result.addAll(transport.readRelationships(resource, Relation.of(rel), consistency));
        }
        return result;
    }

    /** Execute the query and return matching tuples as a set. */
    public Set<Tuple> fetchSet() {
        return new HashSet<Tuple>(fetch());
    }

    /** Execute the query and return the number of matching tuples. */
    public int fetchCount() {
        return fetch().size();
    }

    /** Execute the query and return whether any matching tuples exist. */
    public boolean fetchExists() {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        if (relations.length == 0) {
            return !transport.readRelationships(resource, null, consistency).isEmpty();
        }
        for (String rel : relations) {
            if (!transport.readRelationships(resource, Relation.of(rel), consistency).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Execute the query and return the first matching tuple, if any. */
    public Optional<Tuple> fetchFirst() {
        ResourceRef resource = ResourceRef.of(resourceType, resourceId);
        if (relations.length == 0) {
            List<Tuple> list = transport.readRelationships(resource, null, consistency);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
        }
        for (String rel : relations) {
            List<Tuple> list = transport.readRelationships(resource, Relation.of(rel), consistency);
            if (!list.isEmpty()) {
                return Optional.of(list.getFirst());
            }
        }
        return Optional.empty();
    }

    /** Execute the query and return only the subject ids from matching tuples. */
    public List<String> fetchSubjectIds() {
        return fetch().stream().map(Tuple::subjectId).toList();
    }

    /** Execute the query and return subject ids as a set. */
    public Set<String> fetchSubjectIdSet() {
        return new HashSet<String>(fetchSubjectIds());
    }

    /** Group by relation name, returning full tuples per relation. */
    public Map<String, List<Tuple>> groupByRelationTuples() {
        return fetch().stream().collect(Collectors.groupingBy(Tuple::relation));
    }

    /** Group by relation name, returning subject ids per relation. */
    public Map<String, List<String>> groupByRelation() {
        return fetch().stream().collect(Collectors.groupingBy(
                Tuple::relation,
                Collectors.mapping(Tuple::subjectId, Collectors.toList())));
    }
}
