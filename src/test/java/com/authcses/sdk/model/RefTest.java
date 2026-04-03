package com.authcses.sdk.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RefTest {

    @Test
    void parse_bareId_defaultsToUser() {
        Ref ref = Ref.parse("alice");
        assertEquals("user", ref.type());
        assertEquals("alice", ref.id());
        assertNull(ref.relation());
    }

    @Test
    void parse_typeAndId() {
        Ref ref = Ref.parse("user:alice");
        assertEquals("user", ref.type());
        assertEquals("alice", ref.id());
        assertNull(ref.relation());
    }

    @Test
    void parse_typeIdAndRelation() {
        Ref ref = Ref.parse("department:eng#member");
        assertEquals("department", ref.type());
        assertEquals("eng", ref.id());
        assertEquals("member", ref.relation());
    }

    @Test
    void toSubjectString_withoutRelation() {
        assertEquals("user:alice", new Ref("user", "alice", null).toSubjectString());
    }

    @Test
    void toSubjectString_withRelation() {
        assertEquals("department:eng#member", new Ref("department", "eng", "member").toSubjectString());
    }

    @Test
    void parse_roundTrip() {
        String input = "department:eng#member";
        assertEquals(input, Ref.parse(input).toSubjectString());
    }
}
