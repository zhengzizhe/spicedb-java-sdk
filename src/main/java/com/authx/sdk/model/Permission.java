package com.authx.sdk.model;

import java.util.Objects;

/**
 * Type-safe permission name. Distinguishes permissions from relations at compile time.
 *
 * <p>Codegen enums should implement {@link Named}:
 * <pre>
 * public enum DocumentPermission implements Permission.Named {
 *     VIEW("view"), EDIT("edit"), DELETE("delete");
 *     private final String value;
 *     DocumentPermission(String v) { this.value = v; }
 *     @Override public String permissionName() { return value; }
 * }
 * </pre>
 */
public record Permission(String name) {

    /** Interface for codegen enums. Implement this to pass enums where Permission is expected. */
    public interface Named {
        String permissionName();
        default Permission toPermission() { return Permission.of(permissionName()); }
    }

    public Permission { Objects.requireNonNull(name, "name"); }
    public static Permission of(String name) { return new Permission(name); }
    public static Permission of(Named named) { return new Permission(named.permissionName()); }
}
