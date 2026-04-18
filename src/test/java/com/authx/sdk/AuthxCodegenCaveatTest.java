package com.authx.sdk;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthxCodegenCaveatTest {

    // ── Type mapping ──

    @Test
    void mapSpiceDbType_primitives() {
        assertThat(AuthxCodegen.mapSpiceDbType("string")).isEqualTo("String");
        assertThat(AuthxCodegen.mapSpiceDbType("int")).isEqualTo("Long");
        assertThat(AuthxCodegen.mapSpiceDbType("uint")).isEqualTo("Long");
        assertThat(AuthxCodegen.mapSpiceDbType("double")).isEqualTo("Double");
        assertThat(AuthxCodegen.mapSpiceDbType("bool")).isEqualTo("Boolean");
        assertThat(AuthxCodegen.mapSpiceDbType("any")).isEqualTo("Object");
    }

    @Test
    void mapSpiceDbType_list() {
        assertThat(AuthxCodegen.mapSpiceDbType("list<string>")).isEqualTo("List<String>");
        assertThat(AuthxCodegen.mapSpiceDbType("list<int>")).isEqualTo("List<Long>");
    }

    @Test
    void mapSpiceDbType_map() {
        assertThat(AuthxCodegen.mapSpiceDbType("map<string, any>")).isEqualTo("Map<String, Object>");
    }

    @Test
    void mapSpiceDbType_unknown() {
        assertThat(AuthxCodegen.mapSpiceDbType("bytes")).isEqualTo("bytes");
    }

    // ── Per-caveat class emission ──

    @Test
    void emitCaveatClass_structure() {
        var params = new LinkedHashMap<String, String>();
        params.put("allowed_cidrs", "list<string>");
        params.put("client_ip", "string");

        String code = AuthxCodegen.emitCaveatClass(
                "ip_allowlist", params,
                "client_ip in allowed_cidrs", "IP allowlist caveat",
                "com.example.perms");

        // Package and imports
        assertThat(code).contains("package com.example.perms;");
        assertThat(code).contains("import com.authx.sdk.model.CaveatRef;");

        // NAME constant
        assertThat(code).contains("public static final String NAME = \"ip_allowlist\";");

        // Parameter constants
        assertThat(code).contains("public static final String ALLOWED_CIDRS = \"allowed_cidrs\";");
        assertThat(code).contains("public static final String CLIENT_IP = \"client_ip\";");

        // Javadoc type hints
        assertThat(code).contains("List&lt;String&gt;");

        // ref() and context() methods
        assertThat(code).contains("public static CaveatRef ref(Object... keyValues)");
        assertThat(code).contains("public static Map<String, Object> context(Object... keyValues)");

        // toMap helper
        assertThat(code).contains("private static Map<String, Object> toMap(Object... kv)");

        // CEL expression in javadoc
        assertThat(code).contains("client_ip in allowed_cidrs");

        // Private constructor
        assertThat(code).contains("private IpAllowlist()");
    }

    @Test
    void emitCaveatClass_emptyParams() {
        String code = AuthxCodegen.emitCaveatClass(
                "always_allow", Map.of(), "true", "", "com.example.perms");
        assertThat(code).contains("public static final String NAME = \"always_allow\";");
        assertThat(code).contains("public static CaveatRef ref(Object... keyValues)");
    }

    // ── Caveats summary class ──

    @Test
    void emitCaveats_summaryClass() {
        String code = AuthxCodegen.emitCaveats(
                "com.example.perms", Set.of("ip_allowlist", "time_window"));

        assertThat(code).contains("package com.example.perms;");
        assertThat(code).contains("public static final String IP_ALLOWLIST = \"ip_allowlist\";");
        assertThat(code).contains("public static final String TIME_WINDOW = \"time_window\";");
        assertThat(code).contains("private Caveats()");
    }

    // ── toMap helper validation (tested via generated code shape) ──

    @Test
    void emitCaveatClass_toMapThrowsOnOddLength() {
        var params = new LinkedHashMap<String, String>();
        params.put("x", "string");
        String code = AuthxCodegen.emitCaveatClass(
                "test_cav", params, "", "", "com.example");
        // Verify the generated toMap checks odd length
        assertThat(code).contains("kv.length % 2 != 0");
        // Verify it checks String keys
        assertThat(code).contains("!(kv[i] instanceof String)");
    }
}
