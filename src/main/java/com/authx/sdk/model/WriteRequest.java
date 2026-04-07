package com.authx.sdk.model;

import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import java.util.List;
import java.util.Objects;

/**
 * Request to write a batch of relationship updates to SpiceDB atomically.
 *
 * @param updates the list of relationship updates to apply
 */
public record WriteRequest(List<RelationshipUpdate> updates) {
    public WriteRequest { Objects.requireNonNull(updates, "updates"); }
}
