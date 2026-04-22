package com.authx.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RelationNamedSubjectTypesTest {

    /** Legacy enum without subjectTypes metadata — default returns empty. */
    enum LegacyRel implements Relation.Named {
        EDITOR("editor");
        private final String v;
        LegacyRel(String v) { this.v = v; }
        @Override public String relationName() { return v; }
    }

    /** Codegen-style enum with per-value subjectTypes metadata. */
    enum SchemaRel implements Relation.Named {
        FOLDER("folder", "folder"),
        VIEWER("viewer", "user", "group#member", "user:*");
        private final String v;
        private final List<SubjectType> subjectTypes;
        SchemaRel(String v, String... sts) {
            this.v = v;
            this.subjectTypes = Arrays.stream(sts).map(SubjectType::parse).toList();
        }
        @Override public String relationName() { return v; }
        @Override public List<SubjectType> subjectTypes() { return subjectTypes; }
    }

    @Test
    void defaultIsEmpty() {
        assertThat(LegacyRel.EDITOR.subjectTypes()).isEmpty();
    }

    @Test
    void overrideReturnsAttached() {
        assertThat(SchemaRel.FOLDER.subjectTypes())
                .containsExactly(SubjectType.of("folder"));
        assertThat(SchemaRel.VIEWER.subjectTypes())
                .containsExactly(
                        SubjectType.of("user"),
                        SubjectType.of("group", "member"),
                        SubjectType.wildcard("user"));
    }
}
