package com.authx.sdk.model;

import com.authx.sdk.model.enums.Permissionship;
import org.jspecify.annotations.Nullable;

/**
 * Result of a single SpiceDB permission check.
 *
 * @param permissionship the check outcome (has permission, no permission, or conditional)
 * @param zedToken       the ZedToken returned by SpiceDB for consistency chaining (nullable)
 */
public record CheckResult(
        Permissionship permissionship,
        @Nullable String zedToken
) {
    /**
     * Creates a result indicating the subject has the requested permission.
     *
     * @param zedToken the ZedToken from the SpiceDB response
     * @return a {@code CheckResult} with {@link Permissionship#HAS_PERMISSION}
     */
    public static CheckResult allowed(String zedToken) {
        return new CheckResult(Permissionship.HAS_PERMISSION, zedToken);
    }

    /**
     * Creates a result indicating the subject does not have the requested permission.
     *
     * @param zedToken the ZedToken from the SpiceDB response
     * @return a {@code CheckResult} with {@link Permissionship#NO_PERMISSION}
     */
    public static CheckResult denied(String zedToken) {
        return new CheckResult(Permissionship.NO_PERMISSION, zedToken);
    }

    /**
     * Returns {@code true} if the subject has the requested permission.
     *
     * @return whether the check result is {@link Permissionship#HAS_PERMISSION}
     */
    public boolean hasPermission() {
        return permissionship == Permissionship.HAS_PERMISSION;
    }

    /**
     * Returns {@code true} if the permission is conditional (requires caveat evaluation).
     *
     * @return whether the check result is {@link Permissionship#CONDITIONAL_PERMISSION}
     */
    public boolean isConditional() {
        return permissionship == Permissionship.CONDITIONAL_PERMISSION;
    }
}
