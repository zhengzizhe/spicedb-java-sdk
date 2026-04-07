package com.authx.sdk.model;

import java.util.Objects;

/**
 * Type-safe permission name that distinguishes permissions from relations at compile time.
 *
 * <p>Codegen enums should implement {@link Named}:
 * <pre>
 * public enum DocumentPermission implements Permission.Named {
 *     VIEW("view"), EDIT("edit"), DELETE("delete");
 *     private final String value;
 *     DocumentPermission(String v) { this.value = v; }
 *     &#64;Override public String permissionName() { return value; }
 * }
 * </pre>
 *
 * @param name the permission name as defined in the SpiceDB schema (e.g. {@code "view"})
 */
public record Permission(String name) {

    /** Interface for codegen enums to provide type-safe permission constants. */
    public interface Named {
        /** Returns the permission name string. */
        String permissionName();

        /**
         * Converts this named enum constant to a {@link Permission} value object.
         *
         * @return a new {@code Permission} wrapping this constant's name
         */
        default Permission toPermission() { return Permission.of(permissionName()); }
    }

    public Permission { Objects.requireNonNull(name, "name"); }

    /**
     * Creates a {@code Permission} from a string name.
     *
     * @param name the permission name
     * @return a new {@code Permission}
     */
    public static Permission of(String name) { return new Permission(name); }

    /**
     * Creates a {@code Permission} from a {@link Named} enum constant.
     *
     * @param named the named enum constant
     * @return a new {@code Permission}
     */
    public static Permission of(Named named) { return new Permission(named.permissionName()); }
}
