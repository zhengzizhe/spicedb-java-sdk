package com.authx.sdk.model;

import java.util.Objects;

/**
 * Stable SDK representation of one SpiceDB schema diff entry.
 *
 * @param kind symbolic diff kind, e.g. {@code DEFINITION_ADDED}
 * @param target human-readable schema element affected by the change
 */
public record SchemaDiff(String kind, String target) {

    public SchemaDiff {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
    }
}
