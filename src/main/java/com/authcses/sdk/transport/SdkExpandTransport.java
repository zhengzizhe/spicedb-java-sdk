package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;

/**
 * Transport sub-interface for permission tree expansion.
 */
public interface SdkExpandTransport {

    ExpandTree expand(ResourceRef resource, Permission permission, Consistency consistency);
}
