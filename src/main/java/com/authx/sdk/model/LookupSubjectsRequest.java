package com.authx.sdk.model;

import java.util.Objects;

/**
 * Request for the LookupSubjects API -- finds all subjects of a given type that have a permission on a resource.
 *
 * @param resource    the resource to check access against
 * @param permission  the permission subjects must have
 * @param subjectType the type of subjects to search for (e.g. {@code "user"})
 * @param limit       maximum number of results to return (0 for unlimited)
 * @param consistency the consistency level for the read
 */
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

    /**
     * Returns a copy of this request with a different consistency level.
     *
     * @param c the new consistency level
     * @return this instance if unchanged, otherwise a new {@code LookupSubjectsRequest}
     */
    public LookupSubjectsRequest withConsistency(Consistency c) {
        return c == consistency ? this : new LookupSubjectsRequest(resource, permission, subjectType, limit, c);
    }

    /**
     * Creates a request with no limit and {@link Consistency#minimizeLatency()} consistency.
     *
     * @param resource    the resource to check access against
     * @param permission  the permission subjects must have
     * @param subjectType the type of subjects to search for
     */
    public LookupSubjectsRequest(ResourceRef resource, Permission permission, String subjectType) {
        this(resource, permission, subjectType, 0, Consistency.minimizeLatency());
    }

    /**
     * Creates a request with the given limit and {@link Consistency#minimizeLatency()} consistency.
     *
     * @param resource    the resource to check access against
     * @param permission  the permission subjects must have
     * @param subjectType the type of subjects to search for
     * @param limit       maximum number of results to return
     */
    public LookupSubjectsRequest(ResourceRef resource, Permission permission, String subjectType, int limit) {
        this(resource, permission, subjectType, limit, Consistency.minimizeLatency());
    }
}
