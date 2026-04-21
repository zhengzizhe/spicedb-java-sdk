package com.authx.sdk.action;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.InMemoryTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link GrantAction#to(String)} single-id overload — the bare-id
 * form that business code uses when the schema leaves exactly one
 * non-wildcard subject type. Multi-type relations must force the caller
 * to {@code to(ResourceType, id)} with a clear error rather than guessing
 * a default type; wildcard-only relations must point at
 * {@code toWildcard(ResourceType)}.
 *
 * <p>Note: the behaviour under inference is distinct from {@code to(String...)}
 * — the varargs path does <i>canonical</i> parsing (rejects bare ids), the
 * single-id path first looks for schema inference then falls back to the
 * canonical parse when no schema is attached. Tests below pin both sides.
 */
class GrantActionInferenceTest {

    private GrantAction action(String relation, List<SubjectType> sts) {
        var cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of(relation), Set.of(), Map.of(relation, sts))));
        return new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{relation}, cache);
    }

    @Test
    void singleTypeInferred() {
        var a = action("folder", List.of(SubjectType.of("folder")));
        // "f-1" has no colon → inference → rewritten to "folder:f-1" → accepted.
        a.to("f-1");
    }

    @Test
    void canonicalStillWorks() {
        var a = action("folder", List.of(SubjectType.of("folder")));
        a.to("folder:f-1");
    }

    @Test
    void wildcardSiblingStillInfers() {
        // viewer accepts user | user:* — inference picks user.
        var a = action("viewer", List.of(
                SubjectType.of("user"),
                SubjectType.wildcard("user")));
        a.to("alice"); // rewritten to "user:alice"
    }

    @Test
    void multiTypeRelationThrows() {
        var a = action("viewer", List.of(
                SubjectType.of("user"),
                SubjectType.of("group", "member")));
        assertThatThrownBy(() -> a.to("alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous")
                .hasMessageContaining("to(ResourceType");
    }

    @Test
    void wildcardOnlyRelationThrows() {
        var a = action("link_viewer", List.of(SubjectType.wildcard("user")));
        assertThatThrownBy(() -> a.to("alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toWildcard");
    }

    @Test
    void nullCacheFallsBackToCanonicalParseWhichRejectsBareId() {
        // No schema → no inference possible. The single-id path delegates
        // back to the canonical varargs form; SubjectRef.parse rejects
        // bare ids (no colon, no "type:id" form).
        var a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"folder"}, null);
        assertThatThrownBy(() -> a.to("alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void crossRelationSameTypeInferable() {
        // Two relations, both declaring "folder" → inference still works
        // because both point to the same type.
        var cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("folder", "parent"), Set.of(),
                Map.of(
                        "folder", List.of(SubjectType.of("folder")),
                        "parent", List.of(SubjectType.of("folder"))))));
        var a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"folder", "parent"}, cache);
        a.to("f-1");
    }

    @Test
    void crossRelationDifferentTypesThrows() {
        var cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("folder", "space"), Set.of(),
                Map.of(
                        "folder", List.of(SubjectType.of("folder")),
                        "space", List.of(SubjectType.of("space"))))));
        var a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"folder", "space"}, cache);
        assertThatThrownBy(() -> a.to("x-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differing declared types");
    }
}
