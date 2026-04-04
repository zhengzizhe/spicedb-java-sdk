package com.authcses.sdk.model;

import java.util.Objects;

/** Request for lookupResources — find all resources a subject has a permission on. */
public record LookupResourcesRequest(
    String resourceType,
    Permission permission,
    SubjectRef subject,
    int limit
) {
    public LookupResourcesRequest {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(subject, "subject");
    }

    public LookupResourcesRequest(String resourceType, Permission permission, SubjectRef subject) {
        this(resourceType, permission, subject, 0);
    }
}
