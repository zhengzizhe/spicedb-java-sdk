package com.authx.sdk.model;

import java.util.Objects;

/** Request for lookupSubjects — find all subjects with a permission on a resource. */
public record LookupSubjectsRequest(
    ResourceRef resource,
    Permission permission,
    String subjectType,
    int limit,
    Consistency consistency
) {
    public LookupSubjectsRequest {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(consistency, "consistency");
    }

    public LookupSubjectsRequest withConsistency(Consistency c) {
        return c == consistency ? this : new LookupSubjectsRequest(resource, permission, subjectType, limit, c);
    }

    public LookupSubjectsRequest(ResourceRef resource, Permission permission, String subjectType) {
        this(resource, permission, subjectType, 0, Consistency.minimizeLatency());
    }

    public LookupSubjectsRequest(ResourceRef resource, Permission permission, String subjectType, int limit) {
        this(resource, permission, subjectType, limit, Consistency.minimizeLatency());
    }
}
