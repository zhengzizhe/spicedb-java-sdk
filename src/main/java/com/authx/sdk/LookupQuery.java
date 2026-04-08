package com.authx.sdk;

import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.LookupResourcesRequest;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cross-resource lookup query: find all resources of a type that a subject has a permission on.
 *
 * <pre>
 * List&lt;String&gt; docs = client.lookup("document")
 *     .withPermission("view")
 *     .by("alice")
 *     .fetch();
 * </pre>
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

    /** Set the permission to look up (required). */
    public LookupQuery withPermission(String permission) {
        this.permission = permission;
        return this;
    }

    /** Set the subject (user) to look up resources for, using the default subject type. */
    public LookupQuery by(String subjectId) {
        this.subjectId = subjectId;
        this.subjectType = defaultSubjectType;
        return this;
    }

    /** Set the subject to look up resources for, using an explicit subject reference. */
    public LookupQuery by(SubjectRef subject) {
        this.subjectId = subject.id();
        this.subjectType = subject.type();
        return this;
    }

    /** Override the consistency level for this lookup. */
    public LookupQuery withConsistency(Consistency consistency) {
        this.consistency = consistency;
        return this;
    }

    /** Limit the number of results. 0 = unlimited (default). */
    public LookupQuery limit(int limit) {
        this.limit = limit;
        return this;
    }

    /** Execute the lookup and return matching resource ids as a list. */
    public List<String> fetch() {
        if (permission == null) throw new IllegalStateException("withPermission() must be called before fetch()");
        if (subjectId == null) throw new IllegalStateException("by() must be called before fetch()");
        var request = new LookupResourcesRequest(resourceType, Permission.of(permission),
                SubjectRef.of(subjectType, subjectId, null), limit, consistency);
        return transport.lookupResources(request).stream()
                .map(ResourceRef::id).toList();
    }

    /** Execute the lookup and return matching resource ids as a set. */
    public Set<String> fetchSet() {
        return new HashSet<>(fetch());
    }

    /** Execute the lookup and return the first matching resource id, if any. */
    public Optional<String> fetchFirst() {
        var list = fetch();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    /** Execute the lookup and return the number of matching resources. */
    public int fetchCount() {
        return fetch().size();
    }

    /** Execute the lookup and return whether any matching resources exist. */
    public boolean fetchExists() {
        return !fetch().isEmpty();
    }
}
