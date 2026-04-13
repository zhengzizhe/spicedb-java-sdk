package com.authx.sdk.cache;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class CacheAdditionalTest {

    // ---- NoopCache ----
    @Nested class NoopCacheTest {
        @SuppressWarnings("unchecked")
        private final Cache<String, String> cache = (Cache<String, String>) (Cache<?, ?>) NoopCache.INSTANCE;

        @Test void getAlwaysEmpty() {
            assertThat(cache.get("key")).isEmpty();
        }

        @Test void getIfPresentAlwaysNull() {
            assertThat(cache.getIfPresent("key")).isNull();
        }

        @Test void putDoesNotStore() {
            cache.put("key", "value");
            assertThat(cache.getIfPresent("key")).isNull();
        }

        @Test void sizeAlwaysZero() {
            cache.put("a", "b");
            assertThat(cache.size()).isZero();
        }

        @Test void statsReturnsEmpty() {
            assertThat(cache.stats()).isSameAs(CacheStats.EMPTY);
        }

        @Test void invalidateOperationsDoNotThrow() {
            assertThatNoException().isThrownBy(() -> {
                cache.invalidate("key");
                cache.invalidateAll(k -> true);
                cache.invalidateAll();
            });
        }

        @Test void cacheNoopFactoryMethod() {
            Cache<String, Integer> noop = Cache.noop();
            assertThat(noop.get("x")).isEmpty();
            assertThat(noop.size()).isZero();
        }
    }

    // ---- CacheStats ----
    @Nested class CacheStatsTest {
        @Test void emptyStats() {
            var s = CacheStats.EMPTY;
            assertThat(s.hitCount()).isZero();
            assertThat(s.missCount()).isZero();
            assertThat(s.evictionCount()).isZero();
            assertThat(s.requestCount()).isZero();
            assertThat(s.hitRate()).isEqualTo(1.0); // 0/0 defined as 1.0
        }

        @Test void hitRate() {
            var s = new CacheStats(80, 20, 5);
            assertThat(s.hitRate()).isCloseTo(0.8, within(0.001));
        }

        @Test void requestCount() {
            var s = new CacheStats(30, 70, 10);
            assertThat(s.requestCount()).isEqualTo(100);
        }

        @Test void allMisses() {
            var s = new CacheStats(0, 50, 0);
            assertThat(s.hitRate()).isCloseTo(0.0, within(0.001));
        }

        @Test void recordEquality() {
            var a = new CacheStats(10, 20, 5);
            var b = new CacheStats(10, 20, 5);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    // ---- SchemaCache ----
    @Nested class SchemaCacheTest {
        @Test void emptySchemaSkipsValidation() {
            var sc = new SchemaCache();
            // Should not throw when no schema loaded
            assertThatNoException().isThrownBy(() -> sc.validateResourceType("anything"));
            assertThatNoException().isThrownBy(() -> sc.validateRelation("doc", "editor"));
            assertThatNoException().isThrownBy(() -> sc.validatePermission("doc", "view"));
        }

        @Test void hasSchemaReturnsFalseWhenEmpty() {
            var sc = new SchemaCache();
            assertThat(sc.hasSchema()).isFalse();
        }

        @Test void hasSchemaReturnsTrueAfterUpdate() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            assertThat(sc.hasSchema()).isTrue();
        }

        @Test void validateResourceType_known() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            assertThatNoException().isThrownBy(() -> sc.validateResourceType("document"));
        }

        @Test void validateResourceType_unknown() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            assertThatThrownBy(() -> sc.validateResourceType("dokument"))
                .isInstanceOf(com.authx.sdk.exception.InvalidResourceException.class)
                .hasMessageContaining("dokument")
                .hasMessageContaining("document"); // suggestion
        }

        @Test void validateRelation_known() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor", "viewer"), Set.of("view", "edit"))));
            assertThatNoException().isThrownBy(() -> sc.validateRelation("document", "editor"));
        }

        @Test void validateRelation_unknownRelation() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            assertThatThrownBy(() -> sc.validateRelation("document", "owner"))
                .isInstanceOf(com.authx.sdk.exception.InvalidRelationException.class)
                .hasMessageContaining("owner");
        }

        @Test void validateRelation_permissionUsedAsRelation() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            assertThatThrownBy(() -> sc.validateRelation("document", "view"))
                .isInstanceOf(com.authx.sdk.exception.InvalidRelationException.class)
                .hasMessageContaining("permission");
        }

        @Test void validatePermission_known() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            assertThatNoException().isThrownBy(() -> sc.validatePermission("document", "view"));
        }

        @Test void validatePermission_unknownPermission() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            assertThatThrownBy(() -> sc.validatePermission("document", "delete"))
                .isInstanceOf(com.authx.sdk.exception.InvalidPermissionException.class)
                .hasMessageContaining("delete");
        }

        @Test void validatePermission_relationUsedAsPermission() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            assertThatThrownBy(() -> sc.validatePermission("document", "editor"))
                .isInstanceOf(com.authx.sdk.exception.InvalidPermissionException.class)
                .hasMessageContaining("relation");
        }

        @Test void getResourceTypes() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of(
                "document", new SchemaCache.DefinitionCache(Set.of("editor"), Set.of("view")),
                "folder", new SchemaCache.DefinitionCache(Set.of("owner"), Set.of("access"))
            ));
            assertThat(sc.getResourceTypes()).containsExactlyInAnyOrder("document", "folder");
        }

        @Test void getRelationsAndPermissions() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor", "viewer"), Set.of("view", "edit"))));
            assertThat(sc.getRelations("document")).containsExactlyInAnyOrder("editor", "viewer");
            assertThat(sc.getPermissions("document")).containsExactlyInAnyOrder("view", "edit");
        }

        @Test void getRelationsForUnknownTypeReturnsEmpty() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            assertThat(sc.getRelations("unknown")).isEmpty();
            assertThat(sc.getPermissions("unknown")).isEmpty();
        }

        @Test void hasResourceType() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            assertThat(sc.hasResourceType("document")).isTrue();
            assertThat(sc.hasResourceType("unknown")).isFalse();
        }

        @Test void subjectTypeToRefPrefix() {
            assertThat(new SchemaCache.SubjectType("user", null, false).toRefPrefix()).isEqualTo("user");
            assertThat(new SchemaCache.SubjectType("user", null, true).toRefPrefix()).isEqualTo("user:*");
            assertThat(new SchemaCache.SubjectType("group", "member", false).toRefPrefix()).isEqualTo("group#member");
        }

        @Test void getSubjectTypes() {
            var subjectTypes = List.of(
                new SchemaCache.SubjectType("user", null, false),
                new SchemaCache.SubjectType("group", "member", false)
            );
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"),
                Map.of("editor", subjectTypes)
            )));
            assertThat(sc.getSubjectTypes("document", "editor")).hasSize(2);
            assertThat(sc.getSubjectTypes("document", "viewer")).isEmpty();
            assertThat(sc.getSubjectTypes("unknown", "editor")).isEmpty();
        }

        @Test void validateRelation_unknownResourceTypeIsSkipped() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            // Unknown resource type should not throw
            assertThatNoException().isThrownBy(() -> sc.validateRelation("unknown_type", "any_relation"));
        }

        @Test void validatePermission_unknownResourceTypeIsSkipped() {
            var sc = new SchemaCache();
            sc.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("editor"), Set.of("view"))));
            assertThatNoException().isThrownBy(() -> sc.validatePermission("unknown_type", "any_perm"));
        }
    }

    // ---- Cache.getOrLoad default method ----
    @Nested class CacheGetOrLoadTest {
        @Test void getOrLoadReturnsCachedValue() {
            Cache<String, String> noop = Cache.noop();
            // noop always misses, so loader always called
            var result = noop.getOrLoad("key", k -> "computed-" + k);
            assertThat(result).isEqualTo("computed-key");
        }
    }
}
