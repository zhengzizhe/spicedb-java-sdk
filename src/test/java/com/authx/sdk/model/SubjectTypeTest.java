package com.authx.sdk.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubjectTypeTest {

    @Test
    void parse_typeOnly() {
        var st = SubjectType.parse("user");
        assertThat(st.type()).isEqualTo("user");
        assertThat(st.relation()).isNull();
        assertThat(st.wildcard()).isFalse();
        assertThat(st.toRef()).isEqualTo("user");
    }

    @Test
    void parse_subjectRelation() {
        var st = SubjectType.parse("group#member");
        assertThat(st.type()).isEqualTo("group");
        assertThat(st.relation()).isEqualTo("member");
        assertThat(st.wildcard()).isFalse();
        assertThat(st.toRef()).isEqualTo("group#member");
    }

    @Test
    void parse_wildcard() {
        var st = SubjectType.parse("user:*");
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
        var st = SubjectType.wildcard("user");
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
}
