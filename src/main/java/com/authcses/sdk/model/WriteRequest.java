package com.authcses.sdk.model;

import com.authcses.sdk.transport.SdkTransport.RelationshipUpdate;
import java.util.List;
import java.util.Objects;

public record WriteRequest(List<RelationshipUpdate> updates) {
    public WriteRequest { Objects.requireNonNull(updates, "updates"); }
}
