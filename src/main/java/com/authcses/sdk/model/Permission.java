package com.authcses.sdk.model;

import java.util.Objects;

/** Type-safe permission name. Distinguishes permissions from relations at compile time. */
public record Permission(String name) {
    public Permission { Objects.requireNonNull(name, "name"); }
    public static Permission of(String name) { return new Permission(name); }
}
