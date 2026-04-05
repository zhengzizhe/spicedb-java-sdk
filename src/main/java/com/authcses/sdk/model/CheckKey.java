package com.authcses.sdk.model;

/** Cache key for check results. Replaces 5-String key concatenation. */
public record CheckKey(ResourceRef resource, Permission permission, SubjectRef subject) {
    public static CheckKey of(ResourceRef resource, Permission permission, SubjectRef subject) {
        return new CheckKey(resource, permission, subject);
    }
    /** Index key for O(k) resource invalidation in IndexedCache. */
    public String resourceIndex() {
        return resource.type() + ":" + resource.id();
    }
}
