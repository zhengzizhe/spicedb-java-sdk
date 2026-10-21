package com.authx.sdk.transport;

import com.authx.sdk.model.*;

import java.util.List;

/**
 * Transport sub-interface for relationship read operations.
 */
public interface SdkReadTransport {

    List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency);

    /** Implementations must retain caveat and expiry rather than manufacture missing metadata. */
    default List<RelationshipRecord> readRelationshipDetails(ResourceRef resource, Relation relation, Consistency consistency) {
        throw new UnsupportedOperationException("Detailed relationship reads are unsupported");
    }
}
