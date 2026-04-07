package com.authx.sdk.model;

import java.util.Objects;

/**
 * Type-safe relation name that distinguishes relations from permissions at compile time.
 *
 * <p>Codegen enums should implement {@link Named}:
 * <pre>
 * public enum DocumentRelation implements Relation.Named {
 *     OWNER("owner"), EDITOR("editor"), VIEWER("viewer");
 *     private final String value;
 *     DocumentRelation(String v) { this.value = v; }
 *     &#64;Override public String relationName() { return value; }
 * }
 * </pre>
 *
 * @param name the relation name as defined in the SpiceDB schema (e.g. {@code "editor"})
 */
public record Relation(String name) {

    /** Interface for codegen enums to provide type-safe relation constants. */
    public interface Named {
        /** Returns the relation name string. */
        String relationName();

        /**
         * Converts this named enum constant to a {@link Relation} value object.
         *
         * @return a new {@code Relation} wrapping this constant's name
         */
        default Relation toRelation() { return Relation.of(relationName()); }
    }

    public Relation { Objects.requireNonNull(name, "name"); }

    /**
     * Creates a {@code Relation} from a string name.
     *
     * @param name the relation name
     * @return a new {@code Relation}
     */
    public static Relation of(String name) { return new Relation(name); }

    /**
     * Creates a {@code Relation} from a {@link Named} enum constant.
     *
     * @param named the named enum constant
     * @return a new {@code Relation}
     */
    public static Relation of(Named named) { return new Relation(named.relationName()); }
}
