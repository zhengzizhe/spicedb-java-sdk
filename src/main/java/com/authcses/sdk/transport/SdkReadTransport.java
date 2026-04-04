package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;

import java.util.List;

/**
 * Transport sub-interface for relationship read operations.
 */
public interface SdkReadTransport {

    List<Tuple> readRelationships(ResourceRef resource, Relation relation, Consistency consistency);
}
