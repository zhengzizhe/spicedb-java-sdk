package com.authx.sdk.model;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Declared subject shape allowed on a relation, as reported by
 * SpiceDB's ReflectSchema. Produced by codegen and attached to
 * {@link Relation.Named#subjectTypes()} so the SDK (and business
 * code) can introspect which subjects a relation accepts.
 *
 * <p>Three canonical shapes:
 * <ul>
 *   <li>{@code user}              — typed subject, no sub-relation</li>
 *   <li>{@code group#member}      — typed subject with sub-relation</li>
 *   <li>{@code user:*}            — public wildcard</li>
 * </ul>
 *
 * @param type     subject definition name (e.g. {@code "user"})
 * @param relation optional sub-relation for subject-sets (nullable)
 * @param wildcard {@code true} iff this is a {@code type:*} declaration
 */
public record SubjectType(String type, @Nullable String relation, boolean wildcard) {
    public SubjectType {
        Objects.requireNonNull(type, "type");
    }

    /**
     * Parse {@code "user"} / {@code "group#member"} / {@code "user:*"}.
     *
     * @throws IllegalArgumentException when {@code s} is empty.
     */
    public static SubjectType parse(String s) {
        Objects.requireNonNull(s, "s");
        if (s.isEmpty()) {
            throw new IllegalArgumentException("empty SubjectType ref");
        }
        if (s.endsWith(":*")) {
            return new SubjectType(s.substring(0, s.length() - 2), null, true);
        }
        int hash = s.indexOf('#');
        if (hash >= 0) {
            return new SubjectType(s.substring(0, hash), s.substring(hash + 1), false);
        }
        return new SubjectType(s, null, false);
    }

    /** Typed subject with no sub-relation. */
    public static SubjectType of(String type) {
        return new SubjectType(type, null, false);
    }

    /** Typed subject with a sub-relation (e.g. {@code group#member}). */
    public static SubjectType of(String type, String relation) {
        return new SubjectType(type, relation, false);
    }

    /** Public wildcard declaration (e.g. {@code user:*}). */
    public static SubjectType wildcard(String type) {
        return new SubjectType(type, null, true);
    }

    /** Canonical string form. Inverse of {@link #parse(String)}. */
    public String toRef() {
        if (wildcard) return type + ":*";
        if (relation != null && !relation.isEmpty()) return type + "#" + relation;
        return type;
    }

    /**
     * If exactly one non-wildcard {@link SubjectType} is declared in the
     * given list, return it. Otherwise return empty. Wildcards are
     * ignored for the uniqueness count — a relation that allows
     * {@code user | user:*} infers as {@code user}. Wildcard-only
     * declarations return empty (you cannot infer a concrete id from
     * {@code user:*}).
     *
     * <p>Used by {@code WriteFlow.to(String id)} (and the revoke
     * mirror) to decide whether a bare id can be wrapped into a
     * canonical {@code type:id} subject without forcing the caller to
     * name the type. Multi-type relations — the majority in practice —
     * return empty, and the caller is expected to fall back to
     * {@code to(ResourceType, id)}.
     *
     * @param candidates declared subject shapes for a relation; safe to
     *                   pass an empty list (returns empty).
     * @return the sole non-wildcard subject type, or {@link Optional#empty()}
     *         when zero, multiple, or only-wildcard are declared.
     */
    public static Optional<SubjectType> inferSingleType(List<SubjectType> candidates) {
        SubjectType sole = null;
        for (SubjectType st : candidates) {
            if (st.wildcard) continue;
            if (sole != null) return Optional.empty();
            sole = st;
        }
        return Optional.ofNullable(sole);
    }
}
