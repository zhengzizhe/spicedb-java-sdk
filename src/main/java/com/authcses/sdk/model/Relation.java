package com.authcses.sdk.model;

import java.util.Objects;

/**
 * Type-safe relation name. Distinguishes relations from permissions at compile time.
 *
 * <p>Codegen enums should implement {@link Named}:
 * <pre>
 * public enum DocumentRelation implements Relation.Named {
 *     OWNER("owner"), EDITOR("editor"), VIEWER("viewer");
 *     private final String value;
 *     DocumentRelation(String v) { this.value = v; }
 *     @Override public String relationName() { return value; }
 * }
 * </pre>
 */
public record Relation(String name) {

    /** Interface for codegen enums. Implement this to pass enums where Relation is expected. */
    public interface Named {
        String relationName();
        default Relation toRelation() { return Relation.of(relationName()); }
    }

    public Relation { Objects.requireNonNull(name, "name"); }
    public static Relation of(String name) { return new Relation(name); }
    public static Relation of(Named named) { return new Relation(named.relationName()); }
}
