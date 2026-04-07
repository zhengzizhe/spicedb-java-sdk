package com.authx.sdk.transport;

import com.authx.sdk.model.*;

/**
 * Transport sub-interface for permission tree expansion.
 */
public interface SdkExpandTransport {

    ExpandTree expand(ResourceRef resource, Permission permission, Consistency consistency);
}
