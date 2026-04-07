package com.authx.sdk.model.enums;

/**
 * Outcome of a SpiceDB permission check, mapping to the CheckPermissionResponse permissionship field.
 */
public enum Permissionship {
    /** The subject has the requested permission. */
    HAS_PERMISSION,
    /** The subject does not have the requested permission. */
    NO_PERMISSION,
    /** The permission is conditional and requires caveat context evaluation. */
    CONDITIONAL_PERMISSION;
}
