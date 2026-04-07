package com.authx.sdk.transport;

import com.authx.sdk.model.*;

import java.util.List;

/**
 * Transport sub-interface for lookup operations.
 */
public interface SdkLookupTransport {

    List<SubjectRef> lookupSubjects(LookupSubjectsRequest request);

    List<ResourceRef> lookupResources(LookupResourcesRequest request);
}
