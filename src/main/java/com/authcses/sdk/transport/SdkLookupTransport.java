package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;

import java.util.List;

/**
 * Transport sub-interface for lookup operations.
 */
public interface SdkLookupTransport {

    List<String> lookupSubjects(LookupSubjectsRequest request, Consistency consistency);

    List<String> lookupResources(LookupResourcesRequest request, Consistency consistency);
}
