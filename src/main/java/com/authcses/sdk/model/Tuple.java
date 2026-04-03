package com.authcses.sdk.model;

/**
 * A relationship tuple: resource#relation@subject.
 * Example: document:doc-123#editor@user:alice
 */
public record Tuple(
        String resourceType, String resourceId,
        String relation,
        String subjectType, String subjectId, String subjectRelation
) {
    public String subject() {
        if (subjectRelation != null) return subjectType + ":" + subjectId + "#" + subjectRelation;
        return subjectType + ":" + subjectId;
    }

    public String resource() {
        return resourceType + ":" + resourceId;
    }
}
