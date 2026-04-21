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
 * Cross-resource lookup query: find all resources of a type that a subject has
 * a permission on.
 *
 * <pre>
 * List&lt;String&gt; docs = client.lookup("document")
 *     .withPermission("view")
 *     .by("user:alice")                  // canonical string
 *     .fetch();
 *
 * List&lt;String&gt; docs = client.lookup("document")
 *     .withPermission("view")
 *     .by(SubjectRef.of("user", "alice")) // strongly typed
 *     .fetch();
 * </pre>
 *
 * <p>The SDK does not assume a default subject type — the subject reference
 * must always carry a type.
 */
public class LookupQuery {

    private final String resourceType;
    private final SdkTransport transport;
    private String permission;
    private SubjectRef subject;
    private Consistency consistency = Consistency.minimizeLatency();
    private int limit = 0;

    LookupQuery(String resourceType, SdkTransport transport) {
        this.resourceType = resourceType;
        this.transport = transport;
    }

    /** Set the permission to look up (required). */
    public LookupQuery withPermission(String permission) {
        this.permission = permission;
        return this;
    }

    /** Set the subject to look up resources for. */
    public LookupQuery by(SubjectRef subject) {
        this.subject = subject;
        return this;
    }

    /** Canonical-string form of {@link #by(SubjectRef)} — {@code "user:alice"} etc. */
    public LookupQuery by(String subjectRef) {
        return by(SubjectRef.parse(subjectRef));
    }

    /**
     * Typed subject form: {@code lookup("document").withPermission("view").by(User.TYPE, "alice")}.
     * Constructs the canonical subject ref and routes through {@link #by(String)}.
     */
    public <R extends Enum<R> & com.authx.sdk.model.Relation.Named,
            P extends Enum<P> & Permission.Named>
    LookupQuery by(ResourceType<R, P> subjectType, String id) {
        return by(subjectType.name() + ":" + id);
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
        if (subject == null) throw new IllegalStateException("by() must be called before fetch()");
        var request = new LookupResourcesRequest(resourceType, Permission.of(permission),
                subject, limit, consistency);
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
