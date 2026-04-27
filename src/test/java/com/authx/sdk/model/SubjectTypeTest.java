package com.authx.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubjectTypeTest {

    @Test
    void parse_typeOnly() {
        SubjectType st = SubjectType.parse("user");
        assertThat(st.type()).isEqualTo("user");
        assertThat(st.relation()).isNull();
        assertThat(st.wildcard()).isFalse();
        assertThat(st.toRef()).isEqualTo("user");
    }

    @Test
    void parse_subjectRelation() {
        SubjectType st = SubjectType.parse("group#member");
        assertThat(st.type()).isEqualTo("group");
        assertThat(st.relation()).isEqualTo("member");
        assertThat(st.wildcard()).isFalse();
        assertThat(st.toRef()).isEqualTo("group#member");
    }

    @Test
    void parse_wildcard() {
        SubjectType st = SubjectType.parse("user:*");
        assertThat(st.type()).isEqualTo("user");
        assertThat(st.relation()).isNull();
        assertThat(st.wildcard()).isTrue();
        assertThat(st.toRef()).isEqualTo("user:*");
    }

    @Test
    void of_type() {
        assertThat(SubjectType.of("user").toRef()).isEqualTo("user");
    }

    @Test
    void of_typeAndRelation() {
        assertThat(SubjectType.of("group", "member").toRef()).isEqualTo("group#member");
    }

    @Test
    void wildcardFactory() {
        SubjectType st = SubjectType.wildcard("user");
        assertThat(st.wildcard()).isTrue();
        assertThat(st.toRef()).isEqualTo("user:*");
    }

    @Test
    void parse_rejectsEmpty() {
        assertThatThrownBy(() -> SubjectType.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTrip() {
        for (String ref : new String[]{"user", "group#member", "user:*", "department#all_members"}) {
            assertThat(SubjectType.parse(ref).toRef()).isEqualTo(ref);
        }
    }

    // ---- inferSingleType ----

    @Test
    void inferSingleType_returnsItWhenOnlyOneNonWildcard() {
        Optional<SubjectType> inferred = SubjectType.inferSingleType(
                List.of(SubjectType.of("folder")));
        assertThat(inferred).contains(SubjectType.of("folder"));
    }

    @Test
    void inferSingleType_returnsEmptyAmongMany() {
        Optional<SubjectType> inferred = SubjectType.inferSingleType(List.of(
                SubjectType.of("user"),
                SubjectType.of("group", "member")));
        assertThat(inferred).isEmpty();
    }

    @Test
    void inferSingleType_wildcardSiblingIgnored() {
        // Common pattern: viewer = user | user:* — one non-wildcard, inferable.
        Optional<SubjectType> inferred = SubjectType.inferSingleType(List.of(
                SubjectType.of("user"),
                SubjectType.wildcard("user")));
        assertThat(inferred).contains(SubjectType.of("user"));
    }

    @Test
    void inferSingleType_pureWildcardReturnsEmpty() {
        Optional<SubjectType> inferred = SubjectType.inferSingleType(
                List.of(SubjectType.wildcard("user")));
        assertThat(inferred).isEmpty();
    }

    @Test
    void inferSingleType_emptyListReturnsEmpty() {
        assertThat(SubjectType.inferSingleType(List.of())).isEmpty();
    }

    @Test
    void inferSingleType_subjectRelationKeepsIt() {
        // group#member is still a "single type" — the returned value retains #member.
        Optional<SubjectType> inferred = SubjectType.inferSingleType(
                List.of(SubjectType.of("group", "member")));
        assertThat(inferred).contains(SubjectType.of("group", "member"));
    }
}
