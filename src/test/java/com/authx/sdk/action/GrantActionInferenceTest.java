package com.authx.sdk.action;

import com.authx.sdk.ResourceType;
import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.exception.InvalidRelationException;
import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.InMemoryTransport;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
        SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of(relation), Set.of(), Map.of(relation, sts))));
        return new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{relation}, cache);
    }

    @Test
    void singleTypeInferred() {
        GrantAction a = action("folder", List.of(SubjectType.of("folder")));
        // "f-1" has no colon → inference → rewritten to "folder:f-1" → accepted.
        a.to("f-1");
    }

    @Test
    void canonicalStillWorks() {
        GrantAction a = action("folder", List.of(SubjectType.of("folder")));
        a.to("folder:f-1");
    }

    @Test
    void wildcardSiblingStillInfers() {
        // viewer accepts user | user:* — inference picks user.
        GrantAction a = action("viewer", List.of(
                SubjectType.of("user"),
                SubjectType.wildcard("user")));
        a.to("alice"); // rewritten to "user:alice"
    }

    @Test
    void multiTypeRelationThrows() {
        GrantAction a = action("viewer", List.of(
                SubjectType.of("user"),
                SubjectType.of("group", "member")));
        assertThatThrownBy(() -> a.to("alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous")
                .hasMessageContaining("to(ResourceType");
    }

    @Test
    void wildcardOnlyRelationThrows() {
        GrantAction a = action("link_viewer", List.of(SubjectType.wildcard("user")));
        assertThatThrownBy(() -> a.to("alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toWildcard");
    }

    @Test
    void nullCacheFallsBackToCanonicalParseWhichRejectsBareId() {
        // No schema → no inference possible. The single-id path delegates
        // back to the canonical varargs form; SubjectRef.parse rejects
        // bare ids (no colon, no "type:id" form).
        GrantAction a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"folder"}, null);
        assertThatThrownBy(() -> a.to("alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void crossRelationSameTypeInferable() {
        // Two relations, both declaring "folder" → inference still works
        // because both point to the same type.
        SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("folder", "parent"), Set.of(),
                Map.of(
                        "folder", List.of(SubjectType.of("folder")),
                        "parent", List.of(SubjectType.of("folder"))))));
        GrantAction a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"folder", "parent"}, cache);
        a.to("f-1");
    }

    @Test
    void crossRelationDifferentTypesThrows() {
        SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("folder", "space"), Set.of(),
                Map.of(
                        "folder", List.of(SubjectType.of("folder")),
                        "space", List.of(SubjectType.of("space"))))));
        GrantAction a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"folder", "space"}, cache);
        assertThatThrownBy(() -> a.to("x-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differing declared types");
    }

    // ---- Typed overloads: to(ResourceType, id) ----

    /** Stand-in relation enum for typed tests. */
    enum R implements Relation.Named {
        NOOP("noop");
        private final String v;
        R(String v) { this.v = v; }
        @Override public String relationName() { return v; }
    }

    /** Stand-in permission enum for typed tests. */
    enum P implements Permission.Named {
        NOOP("noop");
        private final String v;
        P(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    @Test
    void typedOverloadBuildsCanonicalRef() {
        // Schema declares viewer accepts only user — the typed overload
        // should pass the subject validator because it constructs user:alice.
        SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.of("user"))))));
        GrantAction a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        ResourceType<GrantActionInferenceTest.R, GrantActionInferenceTest.P> userType = ResourceType.of("user", R.class, P.class);
        a.to(userType, "alice"); // no throw
    }

    @Test
    void typedOverloadStillValidatesAgainstSchema() {
        // Schema declares viewer accepts only user — passing folder via the
        // typed overload must fail-fast (routes through writeRelationships
        // which runs schemaCache.validateSubject).
        SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.of("user"))))));
        GrantAction a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        ResourceType<GrantActionInferenceTest.R, GrantActionInferenceTest.P> folderType = ResourceType.of("folder", R.class, P.class);
        assertThatThrownBy(() -> a.to(folderType, "f-1"))
                .isInstanceOf(InvalidRelationException.class);
    }

    @Test
    void typedOverloadWithSubRelationBuildsCanonicalRef() {
        // Schema declares viewer accepts group#member — the 3-arg typed
        // overload must pass the validator because it constructs
        // "group:eng#member".
        SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.of("group", "member"))))));
        GrantAction a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        ResourceType<GrantActionInferenceTest.R, GrantActionInferenceTest.P> groupType = ResourceType.of("group", R.class, P.class);
        a.to(groupType, "eng", "member"); // no throw
    }

    @Test
    void typedOverloadWithWrongSubRelationFailsValidation() {
        // Schema only declares group#member. Passing group#owner through the
        // 3-arg overload must fail-fast.
        SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.of("group", "member"))))));
        GrantAction a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        ResourceType<GrantActionInferenceTest.R, GrantActionInferenceTest.P> groupType = ResourceType.of("group", R.class, P.class);
        assertThatThrownBy(() -> a.to(groupType, "eng", "owner"))
                .isInstanceOf(InvalidRelationException.class);
    }

    @Test
    void toWildcardBuildsStarRef() {
        // Schema declares viewer accepts user:* — toWildcard(user) writes
        // "user:*" (which passes wildcard validation).
        SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.wildcard("user"))))));
        GrantAction a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        ResourceType<GrantActionInferenceTest.R, GrantActionInferenceTest.P> userType = ResourceType.of("user", R.class, P.class);
        a.toWildcard(userType); // no throw
    }

    @Test
    void toWildcardRejectedWhenSchemaDisallowsWildcard() {
        // Schema declares only typed user (no user:*) — wildcard must fail.
        SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.of("user"))))));
        GrantAction a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        ResourceType<GrantActionInferenceTest.R, GrantActionInferenceTest.P> userType = ResourceType.of("user", R.class, P.class);
        assertThatThrownBy(() -> a.toWildcard(userType))
                .isInstanceOf(InvalidRelationException.class);
    }

    @Test
    void iterableTypedOverloadWritesN() {
        // Batch typed subjects: same subject type, many ids.
        SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.of("user"))))));
        GrantAction a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        ResourceType<GrantActionInferenceTest.R, GrantActionInferenceTest.P> userType = ResourceType.of("user", R.class, P.class);
        GrantResult result = a.to(userType, List.of("alice", "bob", "carol"));
        assertThat(result.count()).isEqualTo(3);
    }

    @Test
    void iterableTypedOverloadEmptyYieldsZero() {
        SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.of("user"))))));
        GrantAction a = new GrantAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        ResourceType<GrantActionInferenceTest.R, GrantActionInferenceTest.P> userType = ResourceType.of("user", R.class, P.class);
        GrantResult result = a.to(userType, List.<String>of());
        assertThat(result.count()).isZero();
    }
}
