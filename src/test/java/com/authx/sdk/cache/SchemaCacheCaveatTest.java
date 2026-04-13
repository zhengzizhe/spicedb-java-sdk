package com.authx.sdk.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCacheCaveatTest {

    private SchemaCache cache;

    @BeforeEach
    void setup() {
        cache = new SchemaCache();
    }

    @Test
    void emptyCacheReturnsNoCaveats() {
        assertThat(cache.getCaveatNames()).isEmpty();
        assertThat(cache.getCaveat("anything")).isNull();
    }

    @Test
    void storeCaveatAndRetrieve() {
        var params = new LinkedHashMap<String, String>();
        params.put("allowed_cidrs", "list<string>");
        params.put("client_ip", "string");
        var def = new SchemaCache.CaveatDef(
                "ip_allowlist", params,
                "client_ip in allowed_cidrs",
                "IP-based access control");

        cache.updateCaveats(Map.of("ip_allowlist", def));

        assertThat(cache.getCaveatNames()).containsExactly("ip_allowlist");
        var retrieved = cache.getCaveat("ip_allowlist");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.name()).isEqualTo("ip_allowlist");
        assertThat(retrieved.parameters()).containsKeys("allowed_cidrs", "client_ip");
        assertThat(retrieved.expression()).isEqualTo("client_ip in allowed_cidrs");
    }

    @Test
    void multipleCaveats() {
        var c1 = new SchemaCache.CaveatDef("cav_a", Map.of("x", "string"), "x != ''", "");
        var c2 = new SchemaCache.CaveatDef("cav_b", Map.of("y", "int"), "y > 0", "");
        cache.updateCaveats(Map.of("cav_a", c1, "cav_b", c2));

        assertThat(cache.getCaveatNames()).containsExactlyInAnyOrder("cav_a", "cav_b");
    }

    @Test
    void updateCaveatsReplacesAll() {
        var c1 = new SchemaCache.CaveatDef("old", Map.of(), "", "");
        cache.updateCaveats(Map.of("old", c1));
        assertThat(cache.getCaveatNames()).containsExactly("old");

        var c2 = new SchemaCache.CaveatDef("new", Map.of(), "", "");
        cache.updateCaveats(Map.of("new", c2));
        assertThat(cache.getCaveatNames()).containsExactly("new");
        assertThat(cache.getCaveat("old")).isNull();
    }
}
