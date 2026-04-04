package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;

import java.util.List;

/**
 * Transport sub-interface for lookup operations.
 */
public interface SdkLookupTransport {

    List<SubjectRef> lookupSubjects(LookupSubjectsRequest request);

    List<ResourceRef> lookupResources(LookupResourcesRequest request);
}
