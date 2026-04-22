package com.authx.sdk;

import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.SubjectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaClientTest {

    @Test
    void delegatesToCache() {
        var cache = new SchemaCache();
        cache.updateFromMap(Map.of("document", new SchemaCache.DefinitionCache(
                Set.of("folder", "viewer"),
                Set.of("view"),
                Map.of("folder", List.of(SubjectType.of("folder"))))));
        var sc = new SchemaClient(cache);
        assertThat(sc.isLoaded()).isTrue();
        assertThat(sc.resourceTypes()).containsExactly("document");
        assertThat(sc.relationsOf("document")).containsExactlyInAnyOrder("folder", "viewer");
        assertThat(sc.permissionsOf("document")).containsExactly("view");
        assertThat(sc.hasResourceType("document")).isTrue();
        assertThat(sc.subjectTypesOf("document", "folder"))
                .containsExactly(SubjectType.of("folder"));
    }

    @Test
    void nullCacheBehavesEmpty() {
        var sc = new SchemaClient(null);
        assertThat(sc.isLoaded()).isFalse();
        assertThat(sc.resourceTypes()).isEmpty();
        assertThat(sc.getCaveatNames()).isEmpty();
        assertThat(sc.hasResourceType("document")).isFalse();
        assertThat(sc.relationsOf("document")).isEmpty();
        assertThat(sc.permissionsOf("document")).isEmpty();
        assertThat(sc.subjectTypesOf("document", "viewer")).isEmpty();
        assertThat(sc.allSubjectTypes("document")).isEmpty();
        assertThat(sc.getCaveat("x")).isNull();
    }

    @Test
    void authxClientSchemaAccessorNonNullOnInMemory() {
        try (var client = AuthxClient.inMemory()) {
            var schema = client.schema();
            assertThat(schema).isNotNull();
            assertThat(schema.isLoaded()).isFalse();
        }
    }
}
