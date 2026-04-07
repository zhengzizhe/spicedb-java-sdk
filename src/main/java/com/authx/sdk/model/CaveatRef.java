package com.authx.sdk.model;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Reference to a SpiceDB caveat for conditional permissions.
 *
 * @param name    the caveat name as defined in the SpiceDB schema
 * @param context optional context values for caveat evaluation (nullable)
 */
public record CaveatRef(String name, @Nullable Map<String, Object> context) {
    public CaveatRef { Objects.requireNonNull(name, "name"); }
}
