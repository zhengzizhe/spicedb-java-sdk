package com.authx.sdk.model.enums;

/**
 * SDK operation types used for telemetry recording and metrics labeling.
 */
public enum SdkAction {
    /** Single permission check. */
    CHECK,
    /** Bulk permission check across multiple subjects or permissions. */
    CHECK_BULK,
    /** Write (grant) relationships. */
    WRITE,
    /** Delete (revoke) relationships. */
    DELETE,
    /** Read relationships. */
    READ,
    /** Lookup subjects with a permission on a resource. */
    LOOKUP_SUBJECTS,
    /** Lookup resources a subject has a permission on. */
    LOOKUP_RESOURCES,
    /** Expand the permission tree for a resource. */
    EXPAND;
}
