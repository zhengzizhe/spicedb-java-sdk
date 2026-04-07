package com.authx.sdk.model;

import com.authx.sdk.model.enums.Permissionship;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;

/**
 * Result of a single permission check.
 */
public record CheckResult(
        Permissionship permissionship,
        @Nullable String zedToken,
        Optional<Instant> expiresAt
) {
    public static CheckResult allowed(String zedToken) {
        return new CheckResult(Permissionship.HAS_PERMISSION, zedToken, Optional.empty());
    }

    public static CheckResult denied(String zedToken) {
        return new CheckResult(Permissionship.NO_PERMISSION, zedToken, Optional.empty());
    }

    public boolean hasPermission() {
        return permissionship == Permissionship.HAS_PERMISSION;
    }

    public boolean isConditional() {
        return permissionship == Permissionship.CONDITIONAL_PERMISSION;
    }
}
