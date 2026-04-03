package com.authcses.sdk.model;

/**
 * A parsed reference to a SpiceDB object or subject.
 * Formats: "user:alice", "department:eng#member", "alice" (bare ID → default subject type).
 */
public record Ref(String type, String id, String relation) {

    /**
     * Parse a string reference into a Ref.
     * <ul>
     *   <li>"alice" → Ref("user", "alice", null) — bare ID defaults to "user"</li>
     *   <li>"user:alice" → Ref("user", "alice", null)</li>
     *   <li>"department:eng#member" → Ref("department", "eng", "member")</li>
     * </ul>
     */
    public static Ref parse(String s) {
        java.util.Objects.requireNonNull(s, "subject reference must not be null");
        int colon = s.indexOf(':');
        if (colon < 0) return new Ref("user", s, null);
        String type = s.substring(0, colon);
        String rest = s.substring(colon + 1);
        int hash = rest.indexOf('#');
        if (hash < 0) return new Ref(type, rest, null);
        return new Ref(type, rest.substring(0, hash), rest.substring(hash + 1));
    }

    /**
     * Format as "type:id" or "type:id#relation".
     */
    public String toSubjectString() {
        if (relation != null) return type + ":" + id + "#" + relation;
        return type + ":" + id;
    }
}
