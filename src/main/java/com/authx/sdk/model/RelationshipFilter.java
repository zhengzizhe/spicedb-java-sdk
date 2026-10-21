package com.authx.sdk.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Relationship selector used by atomic write preconditions; null fields are unrestricted. */
public record RelationshipFilter(String resourceType, @Nullable String resourceId,
        @Nullable String relation, @Nullable String subjectType, @Nullable String subjectId,
        @Nullable String subjectRelation) {
    public RelationshipFilter {
        Objects.requireNonNull(resourceType, "resourceType");
        if (resourceType.isBlank()) throw new IllegalArgumentException("resourceType is blank");
        if ((subjectId != null || subjectRelation != null) && subjectType == null) {
            throw new IllegalArgumentException("A subject type is required for a subject filter");
        }
    }
}
