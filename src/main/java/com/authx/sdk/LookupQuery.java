package com.authx.sdk;

import com.authx.sdk.model.*;
import com.authx.sdk.transport.SdkTransport;

import java.util.*;

/**
 * Cross-resource lookup: find all resources of a type that a subject has a permission on.
 * Example: client.lookup("document").withPermission("view").by("alice").fetch()
 */
public class LookupQuery {

    private final String resourceType;
    private final SdkTransport transport;
    private final String defaultSubjectType;
    private String permission;
    private String subjectId;
    private String subjectType;
    private Consistency consistency = Consistency.minimizeLatency();
    private int limit = 0;

    LookupQuery(String resourceType, SdkTransport transport, String defaultSubjectType) {
        this.resourceType = resourceType;
        this.transport = transport;
        this.defaultSubjectType = defaultSubjectType;
    }

    public LookupQuery withPermission(String permission) {
        this.permission = permission;
        return this;
    }

    public LookupQuery by(String subjectId) {
        this.subjectId = subjectId;
        this.subjectType = defaultSubjectType;
        return this;
    }

    public LookupQuery withConsistency(Consistency consistency) {
        this.consistency = consistency;
        return this;
    }

    /** Limit the number of results. 0 = unlimited (default). */
    public LookupQuery limit(int limit) {
        this.limit = limit;
        return this;
    }

    public List<String> fetch() {
        if (permission == null) throw new IllegalStateException("withPermission() must be called before fetch()");
        if (subjectId == null) throw new IllegalStateException("by() must be called before fetch()");
        var request = new LookupResourcesRequest(resourceType, Permission.of(permission),
                SubjectRef.of(subjectType, subjectId, null), limit, consistency);
        return transport.lookupResources(request).stream()
                .map(ResourceRef::id).toList();
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
        return !fetch().isEmpty();
    }
}
