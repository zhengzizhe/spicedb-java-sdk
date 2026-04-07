package com.authx.sdk.model;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable reference to a SpiceDB subject (e.g. {@code user:alice} or {@code department:eng#member}).
 *
 * @param type     the subject object type (e.g. {@code "user"})
 * @param id       the subject object id (e.g. {@code "alice"}), or {@code "*"} for wildcards
 * @param relation optional subject relation (nullable), e.g. {@code "member"}
 */
public record SubjectRef(String type, String id, @Nullable String relation) {
    public SubjectRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        // relation is nullable
    }

    /**
     * Creates a user subject reference with type {@code "user"} and no relation.
     *
     * @param id the user id
     * @return a new {@code SubjectRef} of type {@code "user"}
     */
    public static SubjectRef user(String id) { return new SubjectRef("user", id, null); }

    /**
     * Creates a wildcard subject reference matching all objects of the given type.
     *
     * @param type the subject object type
     * @return a new {@code SubjectRef} with id {@code "*"}
     */
    public static SubjectRef wildcard(String type) { return new SubjectRef(type, "*", null); }

    /**
     * Factory method to create a {@code SubjectRef} with an explicit relation.
     *
     * @param type     the subject object type
     * @param id       the subject object id
     * @param relation the subject relation (nullable)
     * @return a new {@code SubjectRef}
     */
    public static SubjectRef of(String type, String id, String relation) { return new SubjectRef(type, id, relation); }

    /**
     * Parses a subject reference string such as {@code "department:eng#all_members"},
     * {@code "user:alice"}, or {@code "user:*"}.
     *
     * @param ref the string to parse in the format {@code type:id} or {@code type:id#relation}
     * @return the parsed {@code SubjectRef}
     * @throws IllegalArgumentException if the string does not contain a colon separator
     */
    public static SubjectRef parse(String ref) {
        int colonIdx = ref.indexOf(':');
        if (colonIdx < 0) throw new IllegalArgumentException("Invalid subject ref: " + ref + " (expected type:id or type:id#relation)");
        String type = ref.substring(0, colonIdx);
        String rest = ref.substring(colonIdx + 1);
        int hashIdx = rest.indexOf('#');
        if (hashIdx >= 0) {
            return new SubjectRef(type, rest.substring(0, hashIdx), rest.substring(hashIdx + 1));
        }
        return new SubjectRef(type, rest, null);
    }

    /** Format as "type:id" or "type:id#relation" */
    public String toRefString() {
        if (relation != null) return type + ":" + id + "#" + relation;
        return type + ":" + id;
    }
}
