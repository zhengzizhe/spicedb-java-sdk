package com.authx.sdk.model;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Immutable reference to a SpiceDB subject.
 *
 * <p>A subject is the "who" side of a relationship tuple — {@code user:alice},
 * {@code group:eng#member}, {@code user:*}, or any other
 * {@code type:id[#relation]} tuple defined by your schema.
 *
 * <h2>Preferred usage: strings</h2>
 *
 * Everywhere the SDK accepts a subject (e.g. {@link com.authx.sdk.action.GrantAction#to},
 * {@link com.authx.sdk.action.RevokeAction#from}) it also accepts a plain
 * {@link String} in SpiceDB canonical format. For most business code this is
 * the simplest path:
 *
 * <pre>
 * client.on(Document).select("d-1").grant(Rel.EDITOR).to("user:alice");
 * client.on(Document).select("d-1").grant(Rel.VIEWER).to("user:*");
 * client.on(Document).select("d-1").grant(Rel.VIEWER).to("group:eng#member");
 * </pre>
 *
 * <h2>When to use {@code SubjectRef}</h2>
 *
 * <ul>
 *   <li><b>Returns</b> — methods like {@link com.authx.sdk.AuthxClient}'s
 *       {@code lookupSubjects(...)} return {@code List<SubjectRef>}; use the
 *       record fields directly rather than parsing strings.</li>
 *   <li><b>Programmatic construction</b> — when {@code type} / {@code id}
 *       come from variables:
 *       <pre>SubjectRef.of(u.type(), u.id())</pre>
 *       is clearer than string concatenation and avoids escaping concerns.</li>
 *   <li><b>Compile-time type checking</b> — if you want the type to be a
 *       known identifier (from a generated constant) rather than a free
 *       string.</li>
 * </ul>
 *
 * <h2>Factories</h2>
 * <ul>
 *   <li>{@link #of(String, String)} — the common {@code type:id} case.</li>
 *   <li>{@link #of(String, String, String)} — with explicit relation for
 *       subject sets like {@code group:eng#member}.</li>
 *   <li>{@link #wildcard(String)} — the {@code type:*} wildcard.</li>
 *   <li>{@link #parse(String)} — round-trip from canonical string.</li>
 * </ul>
 *
 * @param type     subject object type (e.g. {@code "user"}, {@code "group"})
 * @param id       subject object id (e.g. {@code "alice"}), or {@code "*"} for wildcards
 * @param relation optional subject relation (nullable), e.g. {@code "member"} in {@code group:eng#member}
 */
public record SubjectRef(String type, String id, @Nullable String relation) {
    public SubjectRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        // relation is nullable
    }

    /**
     * Creates a {@code SubjectRef} with no relation — the common
     * {@code type:id} case.
     *
     * @param type the subject object type (e.g. {@code "user"})
     * @param id   the subject object id (e.g. {@code "alice"})
     * @return a new {@code SubjectRef} with {@code relation = null}
     */
    public static SubjectRef of(String type, String id) {
        return new SubjectRef(type, id, null);
    }

    /**
     * Creates a {@code SubjectRef} with an explicit relation, for subject sets
     * such as {@code group:eng#member}.
     *
     * @param type     the subject object type
     * @param id       the subject object id
     * @param relation the subject relation (nullable; pass {@code null} for {@code type:id})
     * @return a new {@code SubjectRef}
     */
    public static SubjectRef of(String type, String id, @Nullable String relation) {
        return new SubjectRef(type, id, relation);
    }

    /**
     * Creates a wildcard subject reference matching all objects of the given type.
     *
     * @param type the subject object type
     * @return a new {@code SubjectRef} with id {@code "*"} and no relation
     */
    public static SubjectRef wildcard(String type) {
        return new SubjectRef(type, "*", null);
    }

/**
     * Parses a subject reference string such as {@code "group:eng#member"},
     * {@code "user:alice"}, or {@code "user:*"}.
     *
     * @param ref the string to parse in the format {@code type:id} or {@code type:id#relation}
     * @return the parsed {@code SubjectRef}
     * @throws IllegalArgumentException if the string does not contain a colon separator
     */
    public static SubjectRef parse(String ref) {
        int colonIdx = ref.indexOf(':');
        if (colonIdx < 0) {
            throw new IllegalArgumentException(
                    "Invalid subject ref: " + ref + " (expected type:id or type:id#relation)");
        }
        String type = ref.substring(0, colonIdx);
        String rest = ref.substring(colonIdx + 1);
        int hashIdx = rest.indexOf('#');
        if (hashIdx >= 0) {
            return new SubjectRef(type, rest.substring(0, hashIdx), rest.substring(hashIdx + 1));
        }
        return new SubjectRef(type, rest, null);
    }

    /** Format as {@code "type:id"} or {@code "type:id#relation"}. Inverse of {@link #parse(String)}. */
    public String toRefString() {
        if (relation != null) return type + ":" + id + "#" + relation;
        return type + ":" + id;
    }
}
