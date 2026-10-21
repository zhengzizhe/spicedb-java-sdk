package com.authx.sdk.model;

import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import java.util.List;

/** Atomic relationship updates and optional existence constraints. */
public record WriteRequest(List<RelationshipUpdate> updates, List<RelationshipPrecondition> preconditions) {
    public WriteRequest {
        updates = List.copyOf(updates);
        preconditions = List.copyOf(preconditions);
    }
    /** Compatibility constructor for an unconditional write. */
    public WriteRequest(List<RelationshipUpdate> updates) { this(updates, List.of()); }
}
