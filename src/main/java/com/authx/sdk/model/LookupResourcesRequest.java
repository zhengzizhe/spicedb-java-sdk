package com.authx.sdk.model;

import java.util.Objects;

/**
 * Request for the LookupResources API -- finds all resources of a given type that a subject has a permission on.
 *
 * @param resourceType the type of resources to search (e.g. {@code "document"})
 * @param permission   the permission the subject must have
 * @param subject      the subject whose accessible resources are being looked up
 * @param limit        maximum number of results to return (0 for unlimited)
 * @param consistency  the consistency level for the read
 */
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

    /**
     * Returns a copy of this request with a different consistency level.
     *
     * @param c the new consistency level
     * @return this instance if unchanged, otherwise a new {@code LookupResourcesRequest}
     */
    public LookupResourcesRequest withConsistency(Consistency c) {
        return c == consistency ? this : new LookupResourcesRequest(resourceType, permission, subject, limit, c);
    }

    /**
     * Creates a request with no limit and {@link Consistency#minimizeLatency()} consistency.
     *
     * @param resourceType the type of resources to search
     * @param permission   the permission the subject must have
     * @param subject      the subject whose accessible resources are being looked up
     */
    public LookupResourcesRequest(String resourceType, Permission permission, SubjectRef subject) {
        this(resourceType, permission, subject, 0, Consistency.minimizeLatency());
    }

    /**
     * Creates a request with the given limit and {@link Consistency#minimizeLatency()} consistency.
     *
     * @param resourceType the type of resources to search
     * @param permission   the permission the subject must have
     * @param subject      the subject whose accessible resources are being looked up
     * @param limit        maximum number of results to return
     */
    public LookupResourcesRequest(String resourceType, Permission permission, SubjectRef subject, int limit) {
        this(resourceType, permission, subject, limit, Consistency.minimizeLatency());
    }
}
