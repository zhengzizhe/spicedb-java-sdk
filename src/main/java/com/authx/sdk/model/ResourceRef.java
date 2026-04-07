package com.authx.sdk.model;

import java.util.Objects;

/**
 * Immutable reference to a SpiceDB resource object (e.g. {@code document:doc-1}).
 *
 * @param type the resource object type (e.g. {@code "document"})
 * @param id   the resource object id (e.g. {@code "doc-1"})
 */
public record ResourceRef(String type, String id) {
    public ResourceRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }

    /**
     * Factory method to create a {@code ResourceRef}.
     *
     * @param type the resource object type
     * @param id   the resource object id
     * @return a new {@code ResourceRef}
     */
    public static ResourceRef of(String type, String id) {
        return new ResourceRef(type, id);
    }
}
