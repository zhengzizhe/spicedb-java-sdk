package com.authx.sdk.model;

import java.util.Objects;

/** An existence constraint checked atomically with every update in a write request. */
public record RelationshipPrecondition(Operation operation, RelationshipFilter filter) {
    public enum Operation { MUST_MATCH, MUST_NOT_MATCH }
    public RelationshipPrecondition {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(filter, "filter");
    }
}
