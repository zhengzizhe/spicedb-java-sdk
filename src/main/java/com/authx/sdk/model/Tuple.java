package com.authx.sdk.model;

import org.jspecify.annotations.Nullable;

/**
 * A SpiceDB relationship tuple in the form {@code resource#relation@subject}
 * (e.g. {@code document:doc-123#editor@user:alice}).
 *
 * @param resourceType    the resource object type (e.g. {@code "document"})
 * @param resourceId      the resource object id (e.g. {@code "doc-123"})
 * @param relation        the relation name (e.g. {@code "editor"})
 * @param subjectType     the subject object type (e.g. {@code "user"})
 * @param subjectId       the subject object id (e.g. {@code "alice"})
 * @param subjectRelation optional subject relation (nullable)
 */
public record Tuple(
        String resourceType, String resourceId,
        String relation,
        String subjectType, String subjectId, @Nullable String subjectRelation
) {
    /**
     * Returns the subject as a formatted string (e.g. {@code "user:alice"} or {@code "group:eng#member"}).
     *
     * @return the subject reference string
     */
    public String subject() {
        if (subjectRelation != null) return subjectType + ":" + subjectId + "#" + subjectRelation;
        return subjectType + ":" + subjectId;
    }

    /**
     * Returns the resource as a formatted string (e.g. {@code "document:doc-123"}).
     *
     * @return the resource reference string
     */
    public String resource() {
        return resourceType + ":" + resourceId;
    }
}
