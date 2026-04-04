package com.authcses.sdk.model;

import java.util.Objects;

/** Request for lookupSubjects — find all subjects with a permission on a resource. */
public record LookupSubjectsRequest(
    ResourceRef resource,
    Permission permission,
    String subjectType,
    int limit
) {
    public LookupSubjectsRequest {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(subjectType, "subjectType");
    }

    public LookupSubjectsRequest(ResourceRef resource, Permission permission, String subjectType) {
        this(resource, permission, subjectType, 0);
    }
}
