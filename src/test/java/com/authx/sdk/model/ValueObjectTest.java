package com.authx.sdk.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ValueObjectTest {

    @Test void resourceRef_of() {
        var ref = ResourceRef.of("document", "doc-1");
        assertThat(ref.type()).isEqualTo("document");
        assertThat(ref.id()).isEqualTo("doc-1");
    }

    @Test void resourceRef_rejectsNull() {
        assertThatThrownBy(() -> ResourceRef.of(null, "id"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void resourceRef_equality() {
        assertThat(ResourceRef.of("document", "d1")).isEqualTo(ResourceRef.of("document", "d1"));
        assertThat(ResourceRef.of("document", "d1")).isNotEqualTo(ResourceRef.of("folder", "d1"));
    }

    @Test void subjectRef_user() {
        var ref = SubjectRef.user("alice");
        assertThat(ref.type()).isEqualTo("user");
        assertThat(ref.id()).isEqualTo("alice");
        assertThat(ref.relation()).isNull();
    }

    @Test void subjectRef_of_twoArgs_hasNullRelation() {
        // The common type:id case — no need to pass an explicit `null` for relation.
        var ref = SubjectRef.of("user", "alice");
        assertThat(ref.type()).isEqualTo("user");
        assertThat(ref.id()).isEqualTo("alice");
        assertThat(ref.relation()).isNull();
        assertThat(ref).isEqualTo(SubjectRef.of("user", "alice", null));
    }

    @Test void subjectRef_of_twoArgs_worksForAnyType() {
        // Deliberately not using "user" — the SDK does not assume any specific
        // business type. Schema-defined types like department / service /
        // organization all work identically.
        assertThat(SubjectRef.of("department", "eng"))
                .isEqualTo(SubjectRef.of("department", "eng", null));
        assertThat(SubjectRef.of("service", "bot-scraper").toRefString())
                .isEqualTo("service:bot-scraper");
    }

    @Test void subjectRef_parse_simple() {
        var ref = SubjectRef.parse("user:alice");
        assertThat(ref.type()).isEqualTo("user");
        assertThat(ref.id()).isEqualTo("alice");
        assertThat(ref.relation()).isNull();
    }

    @Test void subjectRef_parse_withRelation() {
        var ref = SubjectRef.parse("department:eng#all_members");
        assertThat(ref.type()).isEqualTo("department");
        assertThat(ref.id()).isEqualTo("eng");
        assertThat(ref.relation()).isEqualTo("all_members");
    }

    @Test void subjectRef_parse_invalid() {
        assertThatThrownBy(() -> SubjectRef.parse("invalid"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void subjectRef_wildcard() {
        var ref = SubjectRef.wildcard("user");
        assertThat(ref.type()).isEqualTo("user");
        assertThat(ref.id()).isEqualTo("*");
    }

    @Test void subjectRef_toRefString() {
        assertThat(SubjectRef.user("alice").toRefString()).isEqualTo("user:alice");
        assertThat(SubjectRef.parse("department:eng#all_members").toRefString())
            .isEqualTo("department:eng#all_members");
    }

    @Test void checkKey_equality() {
        var k1 = CheckKey.of(ResourceRef.of("document", "d1"), Permission.of("view"), SubjectRef.user("alice"));
        var k2 = CheckKey.of(ResourceRef.of("document", "d1"), Permission.of("view"), SubjectRef.user("alice"));
        assertThat(k1).isEqualTo(k2);
        assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    }

    @Test void checkKey_inequality() {
        var k1 = CheckKey.of(ResourceRef.of("document", "d1"), Permission.of("view"), SubjectRef.user("alice"));
        var k2 = CheckKey.of(ResourceRef.of("document", "d1"), Permission.of("edit"), SubjectRef.user("alice"));
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test void checkKey_resourceIndex() {
        var k = CheckKey.of(ResourceRef.of("document", "d1"), Permission.of("view"), SubjectRef.user("alice"));
        assertThat(k.resourceIndex()).isEqualTo("document:d1");
    }

    @Test void permission_of() {
        assertThat(Permission.of("view").name()).isEqualTo("view");
    }

    @Test void permission_equality() {
        assertThat(Permission.of("view")).isEqualTo(Permission.of("view"));
        assertThat(Permission.of("view")).isNotEqualTo(Permission.of("edit"));
    }

    @Test void relation_of() {
        assertThat(Relation.of("editor").name()).isEqualTo("editor");
    }

    @Test void caveatRef() {
        var c = new CaveatRef("ip_range", java.util.Map.of("allowed", "10.0.0.0/8"));
        assertThat(c.name()).isEqualTo("ip_range");
        assertThat(c.context()).containsKey("allowed");
    }

    @Test void caveatRef_nullContext() {
        var c = new CaveatRef("simple_caveat", null);
        assertThat(c.name()).isEqualTo("simple_caveat");
        assertThat(c.context()).isNull();
    }

    @Test void caveatRef_rejectsNullName() {
        assertThatThrownBy(() -> new CaveatRef(null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("name");
    }
}
