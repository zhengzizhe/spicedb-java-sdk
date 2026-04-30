package com.authx.sdk.transport;

import com.authx.sdk.model.*;

import java.util.List;

/**
 * Transport sub-interface for relationship write and delete operations.
 */
public interface SdkWriteTransport {

    WriteResult writeRelationships(List<SdkTransport.RelationshipUpdate> updates);

    WriteResult deleteRelationships(List<SdkTransport.RelationshipUpdate> updates);

    WriteResult deleteByFilter(ResourceRef resource, SubjectRef subject, Relation optionalRelation);
}
