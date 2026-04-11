package com.authx.sdk.cache;

import com.authx.sdk.cache.SchemaCache.DefinitionCache;
import com.authx.sdk.cache.SchemaCache.SubjectType;
import com.authx.sdk.exception.InvalidRelationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaCacheValidateSubjectTest {

    private SchemaCache cache;

    @BeforeEach
    void setup() {
        // Build a small synthetic schema:
        //   document.editor   accepts  user, group#member
        //   document.viewer   accepts  user, group#member, user:*  (wildcard)
        //   document.folder   accepts  folder
        //   task.document     accepts  document        (cross-type)
        var documentDef = new DefinitionCache(
                Set.of("editor", "viewer", "folder"),
                Set.of("view", "edit"),
                Map.of(
                        "editor", List.of(
                                new SubjectType("user", null, false),
                                new SubjectType("group", "member", false)),
                        "viewer", List.of(
                                new SubjectType("user", null, false),
                                new SubjectType("group", "member", false),
                                new SubjectType("user", null, true)),  // user:* wildcard
                        "folder", List.of(
                                new SubjectType("folder", null, false))));

        var taskDef = new DefinitionCache(
                Set.of("document", "assignee"),
                Set.of("view"),
                Map.of(
                        "document", List.of(new SubjectType("document", null, false)),
                        "assignee", List.of(new SubjectType("user", null, false))));

        cache = new SchemaCache();
        cache.updateFromMap(Map.of(
                "document", documentDef,
                "task", taskDef));
    }

    @Test
    void validSubject_passesWithoutThrow() {
        assertThatCode(() -> cache.validateSubject("document", "editor", "user:alice"))
                .doesNotThrowAnyException();
        assertThatCode(() -> cache.validateSubject("document", "editor", "group:eng#member"))
                .doesNotThrowAnyException();
        assertThatCode(() -> cache.validateSubject("document", "viewer", "user:*"))
                .doesNotThrowAnyException();
        assertThatCode(() -> cache.validateSubject("document", "folder", "folder:f-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void crossTypeSubject_isAcceptedWhenSchemaDeclaresIt() {
        // task.document accepts "document" subjects
        assertThatCode(() -> cache.validateSubject("task", "document", "document:doc-5"))
                .doesNotThrowAnyException();
    }

    @Test
    void wrongSubjectType_throwsWithAllowedListInMessage() {
        assertThatThrownBy(() -> cache.validateSubject("document", "editor", "folder:f-1"))
                .isInstanceOf(InvalidRelationException.class)
                .hasMessageContaining("document.editor does not accept subject")
                .hasMessageContaining("folder:f-1")
                .hasMessageContaining("user")
                .hasMessageContaining("group#member");
    }

    @Test
    void wildcardToNonWildcardRelation_throws() {
        // editor accepts user but NOT user:* wildcard
        assertThatThrownBy(() -> cache.validateSubject("document", "editor", "user:*"))
                .isInstanceOf(InvalidRelationException.class)
                .hasMessageContaining("does not accept subject \"user:*\"");
    }

    @Test
    void wrongSubRelation_throws() {
        // group#member is allowed, but group#admin is not
        assertThatThrownBy(() -> cache.validateSubject("document", "editor", "group:eng#admin"))
                .isInstanceOf(InvalidRelationException.class)
                .hasMessageContaining("group:eng#admin");
    }

    @Test
    void unknownResourceType_isFailOpen() {
        // Unknown resource type: validator can't check anything, must not throw
        assertThatCode(() -> cache.validateSubject("unknown", "anything", "user:bob"))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownRelation_isFailOpen() {
        // Unknown relation on a known type: fail open (other validators catch it)
        assertThatCode(() -> cache.validateSubject("document", "nonexistent", "user:bob"))
                .doesNotThrowAnyException();
    }

    @Test
    void emptySchemaCache_isFailOpen() {
        var empty = new SchemaCache();
        assertThatCode(() -> empty.validateSubject("document", "editor", "user:alice"))
                .doesNotThrowAnyException();
    }

    @Test
    void malformedRef_throws() {
        assertThatThrownBy(() -> cache.validateSubject("document", "editor", "missing_colon"))
                .isInstanceOf(InvalidRelationException.class)
                .hasMessageContaining("Invalid subject reference");
    }
}
