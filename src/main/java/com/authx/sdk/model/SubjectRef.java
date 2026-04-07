package com.authx.sdk.model;

import java.util.Objects;

/** Immutable reference to a subject. Replaces (String subjectType, String subjectId, String subjectRelation) and the existing Ref class. */
public record SubjectRef(String type, String id, String relation) {
    public SubjectRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        // relation is nullable
    }
    public static SubjectRef user(String id) { return new SubjectRef("user", id, null); }
    public static SubjectRef wildcard(String type) { return new SubjectRef(type, "*", null); }
    public static SubjectRef of(String type, String id, String relation) { return new SubjectRef(type, id, relation); }

    /** Parse "department:eng#all_members" or "user:alice" or "user:*" */
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
