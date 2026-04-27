package com.authx.sdk.action;

import com.authx.sdk.ResourceType;
import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.InMemoryTransport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mirrors {@link GrantActionInferenceTest} on the revoke side. Every
 * {@code to(...)} overload on {@link GrantAction} has a {@code from(...)}
 * twin on {@link RevokeAction}; the two must behave identically so
 * business code can reach for the same single-type inference / typed /
 * wildcard / Iterable forms regardless of direction.
 */
class RevokeActionTypedFromTest {

    enum R implements Relation.Named {
        NOOP("noop");
        private final String v;
        R(String v) { this.v = v; }
        @Override public String relationName() { return v; }
    }

    enum P implements Permission.Named {
        NOOP("noop");
        private final String v;
        P(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    // ---- from(String id) single-type inference ----

    @Test
    void singleTypeInferred() {
        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("folder"), Set.of(),
                Map.of("folder", List.of(SubjectType.of("folder"))))));
        com.authx.sdk.action.RevokeAction a = new RevokeAction("document", "d-1", new InMemoryTransport(),
                new String[]{"folder"}, cache);
        a.from("f-1"); // rewritten to "folder:f-1"
    }

    @Test
    void canonicalStillWorks() {
        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("folder"), Set.of(),
                Map.of("folder", List.of(SubjectType.of("folder"))))));
        com.authx.sdk.action.RevokeAction a = new RevokeAction("document", "d-1", new InMemoryTransport(),
                new String[]{"folder"}, cache);
        a.from("folder:f-1");
    }

    @Test
    void multiTypeRelationThrows() {
        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(
                        SubjectType.of("user"),
                        SubjectType.of("group", "member"))))));
        com.authx.sdk.action.RevokeAction a = new RevokeAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        assertThatThrownBy(() -> a.from("alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous")
                .hasMessageContaining("from(ResourceType");
    }

    @Test
    void wildcardOnlyRelationThrows() {
        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("link_viewer"), Set.of(),
                Map.of("link_viewer", List.of(SubjectType.wildcard("user"))))));
        com.authx.sdk.action.RevokeAction a = new RevokeAction("document", "d-1", new InMemoryTransport(),
                new String[]{"link_viewer"}, cache);
        assertThatThrownBy(() -> a.from("alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fromWildcard");
    }

    @Test
    void nullCacheFallsBackToCanonicalParseWhichRejectsBareId() {
        com.authx.sdk.action.RevokeAction a = new RevokeAction("document", "d-1", new InMemoryTransport(),
                new String[]{"folder"}, null);
        assertThatThrownBy(() -> a.from("alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- from(ResourceType, id) ----

    @Test
    void typedOverloadBuildsCanonicalRef() {
        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.of("user"))))));
        com.authx.sdk.action.RevokeAction a = new RevokeAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        com.authx.sdk.ResourceType<com.authx.sdk.action.RevokeActionTypedFromTest.R,com.authx.sdk.action.RevokeActionTypedFromTest.P> userType = ResourceType.of("user", R.class, P.class);
        a.from(userType, "alice"); // no throw
    }

    @Test
    void typedOverloadStillValidatesAgainstSchema() {
        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.of("user"))))));
        com.authx.sdk.action.RevokeAction a = new RevokeAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        com.authx.sdk.ResourceType<com.authx.sdk.action.RevokeActionTypedFromTest.R,com.authx.sdk.action.RevokeActionTypedFromTest.P> folderType = ResourceType.of("folder", R.class, P.class);
        assertThatThrownBy(() -> a.from(folderType, "f-1"))
                .isInstanceOf(com.authx.sdk.exception.InvalidRelationException.class);
    }

    // ---- from(ResourceType, id, subjectRelation) ----

    @Test
    void typedOverloadWithSubRelation() {
        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.of("group", "member"))))));
        com.authx.sdk.action.RevokeAction a = new RevokeAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        com.authx.sdk.ResourceType<com.authx.sdk.action.RevokeActionTypedFromTest.R,com.authx.sdk.action.RevokeActionTypedFromTest.P> groupType = ResourceType.of("group", R.class, P.class);
        a.from(groupType, "eng", "member");
    }

    // ---- fromWildcard(ResourceType) ----

    @Test
    void fromWildcardBuildsStarRef() {
        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.wildcard("user"))))));
        com.authx.sdk.action.RevokeAction a = new RevokeAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        com.authx.sdk.ResourceType<com.authx.sdk.action.RevokeActionTypedFromTest.R,com.authx.sdk.action.RevokeActionTypedFromTest.P> userType = ResourceType.of("user", R.class, P.class);
        a.fromWildcard(userType);
    }

    // ---- from(ResourceType, Iterable<String>) ----

    @Test
    void iterableTypedOverloadWritesN() {
        com.authx.sdk.cache.SchemaCache cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("viewer"), Set.of(),
                Map.of("viewer", List.of(SubjectType.of("user"))))));
        com.authx.sdk.action.RevokeAction a = new RevokeAction("document", "d-1", new InMemoryTransport(),
                new String[]{"viewer"}, cache);
        com.authx.sdk.ResourceType<com.authx.sdk.action.RevokeActionTypedFromTest.R,com.authx.sdk.action.RevokeActionTypedFromTest.P> userType = ResourceType.of("user", R.class, P.class);
        com.authx.sdk.model.RevokeResult result = a.from(userType, List.of("alice", "bob", "carol"));
        assertThat(result.count()).isEqualTo(3);
    }
}
