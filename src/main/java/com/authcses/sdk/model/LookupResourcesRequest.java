package com.authcses.sdk.model;

import java.util.Objects;

/** Request for lookupResources — find all resources a subject has a permission on. */
public record LookupResourcesRequest(
    String resourceType,
    Permission permission,
    SubjectRef subject,
    int limit,
    Consistency consistency
) {
    public LookupResourcesRequest {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(consistency, "consistency");
    }

    public LookupResourcesRequest(String resourceType, Permission permission, SubjectRef subject) {
        this(resourceType, permission, subject, 0, Consistency.minimizeLatency());
    }

    public LookupResourcesRequest(String resourceType, Permission permission, SubjectRef subject, int limit) {
        this(resourceType, permission, subject, limit, Consistency.minimizeLatency());
    }
}
