package com.authcses.sdk.model;

import java.util.Objects;

/** Immutable reference to a resource (e.g., document:doc-1). Replaces scattered (String type, String id) params. */
public record ResourceRef(String type, String id) {
    public ResourceRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }
    public static ResourceRef of(String type, String id) {
        return new ResourceRef(type, id);
    }
}
