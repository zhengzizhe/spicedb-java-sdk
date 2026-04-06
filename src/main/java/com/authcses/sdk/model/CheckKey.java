package com.authcses.sdk.model;

/** Cache key for check results. Replaces 5-String key concatenation. */
public record CheckKey(ResourceRef resource, Permission permission, SubjectRef subject, String resourceIndex) {

    /**
     * Primary constructor — pre-computes resourceIndex to avoid repeated String concatenation
     * on every cache lookup/invalidation.
     */
    public CheckKey(ResourceRef resource, Permission permission, SubjectRef subject) {
        this(resource, permission, subject, resource.type() + ":" + resource.id());
    }

    public static CheckKey of(ResourceRef resource, Permission permission, SubjectRef subject) {
        return new CheckKey(resource, permission, subject);
    }
}
