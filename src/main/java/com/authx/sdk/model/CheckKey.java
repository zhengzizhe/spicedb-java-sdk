package com.authx.sdk.model;

/**
 * Cache key for permission check results, combining resource, permission, and subject into a single composite key.
 *
 * @param resource      the resource being checked
 * @param permission    the permission being tested
 * @param subject       the subject whose access is being checked
 * @param resourceIndex pre-computed {@code "type:id"} string used for cache indexing and invalidation
 */
public record CheckKey(ResourceRef resource, Permission permission, SubjectRef subject, String resourceIndex) {

    /**
     * Creates a {@code CheckKey} that automatically computes the {@code resourceIndex}
     * from the resource's type and id.
     *
     * @param resource   the resource being checked
     * @param permission the permission being tested
     * @param subject    the subject whose access is being checked
     */
    public CheckKey(ResourceRef resource, Permission permission, SubjectRef subject) {
        this(resource, permission, subject, resource.type() + ":" + resource.id());
    }

    /**
     * Factory method to create a {@code CheckKey} with automatic resource index computation.
     *
     * @param resource   the resource being checked
     * @param permission the permission being tested
     * @param subject    the subject whose access is being checked
     * @return a new {@code CheckKey}
     */
    public static CheckKey of(ResourceRef resource, Permission permission, SubjectRef subject) {
        return new CheckKey(resource, permission, subject);
    }
}
