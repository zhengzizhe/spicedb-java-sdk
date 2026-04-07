package com.authx.sdk.model;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable request for a single permission check against SpiceDB.
 *
 * @param resource      the resource to check access on
 * @param permission    the permission to verify
 * @param subject       the subject requesting access
 * @param consistency   the consistency level for the read
 * @param caveatContext optional context values for caveat evaluation (nullable)
 */
public record CheckRequest(
    ResourceRef resource,
    Permission permission,
    SubjectRef subject,
    Consistency consistency,
    @Nullable Map<String, Object> caveatContext
) {
    public CheckRequest {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(consistency, "consistency");
    }

    /**
     * Creates a check request without caveat context.
     *
     * @param resource    the resource to check access on
     * @param permission  the permission to verify
     * @param subject     the subject requesting access
     * @param consistency the consistency level for the read
     * @return a new {@code CheckRequest}
     */
    public static CheckRequest of(ResourceRef resource, Permission permission, SubjectRef subject, Consistency consistency) {
        return new CheckRequest(resource, permission, subject, consistency, null);
    }

    /**
     * Creates a check request from raw string parameters, used internally by {@code AuthxClient}.
     *
     * @param resourceType the resource object type (e.g. {@code "document"})
     * @param resourceId   the resource object id (e.g. {@code "doc-1"})
     * @param permission   the permission name (e.g. {@code "view"})
     * @param subjectType  the subject type (e.g. {@code "user"})
     * @param subjectId    the subject id (e.g. {@code "alice"})
     * @param consistency  the consistency level for the read
     * @return a new {@code CheckRequest}
     */
    public static CheckRequest of(String resourceType, String resourceId, String permission,
                                     String subjectType, String subjectId, Consistency consistency) {
        return new CheckRequest(
            ResourceRef.of(resourceType, resourceId),
            Permission.of(permission),
            SubjectRef.of(subjectType, subjectId, null),
            consistency, null);
    }

    /**
     * Creates a check request from raw string parameters, including subject relation.
     *
     * @param resourceType    the resource object type
     * @param resourceId      the resource object id
     * @param permission      the permission name
     * @param subjectType     the subject type
     * @param subjectId       the subject id
     * @param subjectRelation the subject relation (e.g. {@code "member"})
     * @param consistency     the consistency level for the read
     * @return a new {@code CheckRequest}
     */
    public static CheckRequest of(String resourceType, String resourceId, String permission,
                                     String subjectType, String subjectId, String subjectRelation,
                                     Consistency consistency) {
        return new CheckRequest(
            ResourceRef.of(resourceType, resourceId),
            Permission.of(permission),
            SubjectRef.of(subjectType, subjectId, subjectRelation),
            consistency, null);
    }

    /**
     * Returns a copy of this request with a different consistency level.
     *
     * @param c the new consistency level
     * @return this instance if the consistency is unchanged, otherwise a new {@code CheckRequest}
     */
    public CheckRequest withConsistency(Consistency c) {
        return c == consistency ? this : new CheckRequest(resource, permission, subject, c, caveatContext);
    }

    /**
     * Converts this request into a {@link CheckKey} suitable for cache lookups.
     *
     * @return a new {@code CheckKey} derived from this request's resource, permission, and subject
     */
    public CheckKey toKey() {
        return new CheckKey(resource, permission, subject);
    }
}
