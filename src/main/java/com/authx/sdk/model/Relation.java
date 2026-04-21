package com.authx.sdk.model;

import java.util.List;
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

        /**
         * Allowed subject shapes on this relation, as declared in the SpiceDB
         * schema. Codegen enums override this to return the metadata emitted
         * from {@link SubjectType#parse(String)}; hand-written enums without
         * codegen metadata get the empty default.
         *
         * <p>Used by the SDK for:
         * <ul>
         *   <li>runtime subject validation ({@code SchemaCache.validateSubject})</li>
         *   <li>single-type subject inference ({@code .to(id)})</li>
         *   <li>business-code introspection ({@code Document.Rel.VIEWER.subjectTypes()})</li>
         * </ul>
         *
         * @return declared subject shapes; empty when no schema metadata is attached.
         */
        default List<SubjectType> subjectTypes() { return List.of(); }
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
