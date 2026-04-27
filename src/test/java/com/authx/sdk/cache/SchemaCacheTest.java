package com.authx.sdk.cache;

import com.authx.sdk.model.SubjectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCacheTest {

    @Test
    void emptyByDefault() {
        com.authx.sdk.cache.SchemaCache c = new SchemaCache();
        assertThat(c.hasSchema()).isFalse();
        assertThat(c.getResourceTypes()).isEmpty();
        assertThat(c.getRelations("document")).isEmpty();
        assertThat(c.getPermissions("document")).isEmpty();
        assertThat(c.getSubjectTypes("document", "viewer")).isEmpty();
        assertThat(c.getCaveatNames()).isEmpty();
        assertThat(c.getCaveat("ip_allowlist")).isNull();
    }

    @Test
    void updateFromMap_populatesDefinitions() {
        com.authx.sdk.cache.SchemaCache c = new SchemaCache();
        Map<String, SchemaCache.DefinitionCache> defs = Map.of(
                "document", new SchemaCache.DefinitionCache(
                        Set.of("folder", "viewer"),
                        Set.of("view", "edit"),
                        Map.of(
                                "folder", List.of(SubjectType.of("folder")),
                                "viewer", List.of(
                                        SubjectType.of("user"),
                                        SubjectType.wildcard("user")))));
        c.updateFromMap(defs);

        assertThat(c.hasSchema()).isTrue();
        assertThat(c.getResourceTypes()).containsExactlyInAnyOrder("document");
        assertThat(c.getRelations("document")).containsExactlyInAnyOrder("folder", "viewer");
        assertThat(c.getPermissions("document")).containsExactlyInAnyOrder("view", "edit");
        assertThat(c.getSubjectTypes("document", "folder"))
                .containsExactly(SubjectType.of("folder"));
        assertThat(c.getSubjectTypes("document", "viewer"))
                .containsExactly(SubjectType.of("user"), SubjectType.wildcard("user"));
        assertThat(c.hasResourceType("document")).isTrue();
        assertThat(c.hasResourceType("widget")).isFalse();
    }

    @Test
    void updateCaveats_populates() {
        com.authx.sdk.cache.SchemaCache c = new SchemaCache();
        c.updateCaveats(Map.of(
                "ip_allowlist", new SchemaCache.CaveatDef(
                        "ip_allowlist",
                        Map.of("cidrs", "list<string>"),
                        "client_ip in cidrs",
                        "")));
        assertThat(c.getCaveatNames()).containsExactly("ip_allowlist");
        com.authx.sdk.cache.SchemaCache.CaveatDef def = c.getCaveat("ip_allowlist");
        assertThat(def).isNotNull();
        assertThat(def.parameters()).containsEntry("cidrs", "list<string>");
        assertThat(def.expression()).isEqualTo("client_ip in cidrs");
    }
}
