package com.authx.sdk.cache;

import com.authx.sdk.exception.InvalidRelationException;
import com.authx.sdk.model.SubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaCacheValidateSubjectTest {

    private SchemaCache cache;

    @BeforeEach
    void setUp() {
        cache = new SchemaCache();
        cache.updateFromMap(Map.of(
                "document", new SchemaCache.DefinitionCache(
                        Set.of("folder", "viewer"),
                        Set.of("view"),
                        Map.of(
                                "folder", List.of(SubjectType.of("folder")),
                                "viewer", List.of(
                                        SubjectType.of("user"),
                                        SubjectType.of("group", "member"),
                                        SubjectType.wildcard("user"))))));
    }

    @Test
    void acceptsTypedSubject() {
        assertThatCode(() -> cache.validateSubject("document", "folder", "folder:f-1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> cache.validateSubject("document", "viewer", "user:alice"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsSubjectWithRelation() {
        assertThatCode(() -> cache.validateSubject("document", "viewer", "group:eng#member"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsWildcard() {
        assertThatCode(() -> cache.validateSubject("document", "viewer", "user:*"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWrongType() {
        assertThatThrownBy(() -> cache.validateSubject("document", "folder", "user:alice"))
                .isInstanceOf(InvalidRelationException.class)
                .hasMessageContaining("document.folder")
                .hasMessageContaining("[folder]");
    }

    @Test
    void rejectsWildcardWhenNotDeclared() {
        assertThatThrownBy(() -> cache.validateSubject("document", "folder", "folder:*"))
                .isInstanceOf(InvalidRelationException.class);
    }

    @Test
    void rejectsWrongSubjectRelation() {
        assertThatThrownBy(() -> cache.validateSubject("document", "viewer", "group:eng#admin"))
                .isInstanceOf(InvalidRelationException.class)
                .hasMessageContaining("group#member");
    }

    @Test
    void emptySchemaIsFailOpen() {
        SchemaCache empty = new SchemaCache();
        assertThatCode(() -> empty.validateSubject("anything", "anything", "x:y"))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownResourceTypeIsFailOpen() {
        // Let other validators catch unknown types; don't double-reject.
        assertThatCode(() -> cache.validateSubject("widget", "owner", "user:alice"))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownRelationIsFailOpen() {
        // Same reasoning — ValidationInterceptor already flags unknown relations.
        assertThatCode(() -> cache.validateSubject("document", "unknown_rel", "user:alice"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsRefWithoutColon() {
        assertThatThrownBy(() -> cache.validateSubject("document", "folder", "f-1"))
                .isInstanceOf(InvalidRelationException.class)
                .hasMessageContaining("type:id");
    }
}
