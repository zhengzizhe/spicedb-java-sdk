package com.authx.sdk.transport;

import com.authx.sdk.model.*;

import java.util.List;

/**
 * Transport sub-interface for relationship write and delete operations.
 */
public interface SdkWriteTransport {

    GrantResult writeRelationships(List<SdkTransport.RelationshipUpdate> updates);

    RevokeResult deleteRelationships(List<SdkTransport.RelationshipUpdate> updates);

    RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject, Relation optionalRelation);
}
