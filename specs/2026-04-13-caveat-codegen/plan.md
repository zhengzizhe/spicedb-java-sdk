# Caveat Code Generation Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate type-safe Java classes from SpiceDB caveat definitions so users never write caveat name/parameter magic strings.

**Architecture:** Three-layer change — SchemaCache gets a new `CaveatDef` record and accessors, SchemaLoader populates it from gRPC reflect response, AuthxCodegen emits one Java class per caveat plus a summary `Caveats.java`. All changes are additive; existing behavior is unaffected.

**Tech Stack:** Java 21, gRPC (authzed-api 1.5.4 — `ExpCaveat`/`ExpCaveatParameter`), JUnit 5, AssertJ.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/com/authx/sdk/cache/SchemaCache.java` | Modify | Add `CaveatDef` record, `Map<String, CaveatDef>` storage, `getCaveatNames()`, `getCaveat()` |
| `src/main/java/com/authx/sdk/transport/SchemaLoader.java` | Modify | Extract caveats from `response.getCaveatsList()` into SchemaCache |
| `src/main/java/com/authx/sdk/AuthxCodegen.java` | Modify | Add `emitCaveatClass()`, `emitCaveats()`, `mapSpiceDbType()` |
| `src/test/java/com/authx/sdk/cache/SchemaCacheCaveatTest.java` | Create | Tests for CaveatDef storage and retrieval |
| `src/test/java/com/authx/sdk/AuthxCodegenCaveatTest.java` | Create | Tests for caveat class emission, type mapping, toMap helper |

---

## Task T001: Add CaveatDef record and accessors to SchemaCache

**Files:**
- Modify: `src/main/java/com/authx/sdk/cache/SchemaCache.java`
- Create: `src/test/java/com/authx/sdk/cache/SchemaCacheCaveatTest.java`

**Steps:**

1. Write failing test `src/test/java/com/authx/sdk/cache/SchemaCacheCaveatTest.java`:

```java
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
```

2. Run test to verify it fails (CaveatDef, getCaveatNames, getCaveat, updateCaveats don't exist yet):
```
./gradlew test --tests "*.SchemaCacheCaveatTest" -x :test-app:test
```

3. Add to `SchemaCache.java` — after the existing `SubjectType` and `DefinitionCache` records, add:

```java
/** Caveat definition from the SpiceDB schema. */
public record CaveatDef(
        String name,
        Map<String, String> parameters,  // paramName → SpiceDB type string
        String expression,
        String comment
) {
    public CaveatDef {
        Objects.requireNonNull(name, "name");
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        expression = expression != null ? expression : "";
        comment = comment != null ? comment : "";
    }
}
```

Add a new field alongside the existing `cache` AtomicReference:

```java
private final AtomicReference<Map<String, CaveatDef>> caveatCache = new AtomicReference<>(Map.of());
```

Add methods:

```java
public void updateCaveats(Map<String, CaveatDef> caveats) {
    caveatCache.set(Map.copyOf(caveats));
}

public Set<String> getCaveatNames() {
    return caveatCache.get().keySet();
}

public @Nullable CaveatDef getCaveat(String name) {
    return caveatCache.get().get(name);
}
```

Add `import java.util.Objects;` if not already present (it isn't — existing code doesn't use it in SchemaCache). Add `import org.jspecify.annotations.Nullable;`.

4. Run test to verify it passes:
```
./gradlew test --tests "*.SchemaCacheCaveatTest" -x :test-app:test
```

5. Run existing SchemaCache tests to verify no regression:
```
./gradlew test --tests "*.SchemaCache*" -x :test-app:test
```

6. Commit:
```
git add -A && git commit -m "feat(schema): add CaveatDef record and accessors to SchemaCache"
```

---

## Task T002: Extract caveats in SchemaLoader

**Files:**
- Modify: `src/main/java/com/authx/sdk/transport/SchemaLoader.java`

**Steps:**

1. In `SchemaLoader.load()`, after the existing `for (var def : response.getDefinitionsList())` loop and `schemaCache.updateFromMap(definitions)` call, add caveat extraction:

```java
// ── Extract caveats ──
Map<String, SchemaCache.CaveatDef> caveats = new HashMap<>();
for (var cav : response.getCaveatsList()) {
    Map<String, String> params = new LinkedHashMap<>();
    for (var p : cav.getParametersList()) {
        params.put(p.getName(), p.getType());
    }
    caveats.put(cav.getName(), new SchemaCache.CaveatDef(
            cav.getName(), params, cav.getExpression(), cav.getComment()));
}
schemaCache.updateCaveats(caveats);
LOG.log(System.Logger.Level.INFO, "Schema loaded: {0} definitions, {1} caveats",
        definitions.size(), caveats.size());
```

Replace the existing log line `"Schema loaded: {0} definitions"` with the new one above.

Add `import java.util.LinkedHashMap;` to the imports.

2. Run existing tests to verify no regression:
```
./gradlew test -x :test-app:test
```

3. Commit:
```
git add -A && git commit -m "feat(schema): extract caveat definitions in SchemaLoader"
```

---

## Task T003: Add caveat class emission to AuthxCodegen

**Files:**
- Modify: `src/main/java/com/authx/sdk/AuthxCodegen.java`
- Create: `src/test/java/com/authx/sdk/AuthxCodegenCaveatTest.java`

**Steps:**

1. Write failing test `src/test/java/com/authx/sdk/AuthxCodegenCaveatTest.java`:

```java
package com.authx.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

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
        assertThat(code).contains("String");

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
```

2. Run test to verify it fails:
```
./gradlew test --tests "*.AuthxCodegenCaveatTest" -x :test-app:test
```

3. Add the following methods to `AuthxCodegen.java`:

First, add the type mapping method (package-private for testing):

```java
/** Map a SpiceDB caveat parameter type to a Java type hint for Javadoc. */
static String mapSpiceDbType(String spiceType) {
    return switch (spiceType) {
        case "string" -> "String";
        case "int", "uint" -> "Long";
        case "double" -> "Double";
        case "bool" -> "Boolean";
        case "any" -> "Object";
        default -> {
            if (spiceType.startsWith("list<") && spiceType.endsWith(">")) {
                String inner = spiceType.substring(5, spiceType.length() - 1);
                yield "List<" + mapSpiceDbType(inner) + ">";
            }
            if (spiceType.startsWith("map<") && spiceType.endsWith(">")) {
                String inner = spiceType.substring(4, spiceType.length() - 1);
                int comma = inner.indexOf(',');
                if (comma > 0) {
                    String k = inner.substring(0, comma).trim();
                    String v = inner.substring(comma + 1).trim();
                    yield "Map<" + mapSpiceDbType(k) + ", " + mapSpiceDbType(v) + ">";
                }
            }
            yield spiceType; // unknown — return raw
        }
    };
}
```

Then, add the caveat class emitter (package-private for testing):

```java
static String emitCaveatClass(String caveatName, Map<String, String> parameters,
                               String expression, String comment, String packageName) {
    String className = toPascalCase(caveatName);
    var sb = new StringBuilder();
    sb.append("package ").append(packageName).append(";\n\n");
    sb.append("import com.authx.sdk.model.CaveatRef;\n\n");
    sb.append("import java.util.LinkedHashMap;\n");
    sb.append("import java.util.Map;\n\n");

    // Class javadoc
    sb.append("/**\n");
    sb.append(" * Typed caveat <b>").append(caveatName).append("</b>.\n");
    if (!comment.isEmpty()) {
        sb.append(" * <p>").append(comment).append("\n");
    }
    if (!expression.isEmpty()) {
        sb.append(" * <p>CEL: {@code ").append(expression).append("}\n");
    }
    sb.append(" * Generated by AuthxCodegen — do not edit.\n");
    sb.append(" */\n");
    sb.append("public final class ").append(className).append(" {\n\n");

    // NAME constant
    sb.append("    public static final String NAME = \"").append(caveatName).append("\";\n\n");

    // Parameter name constants with Javadoc type hints
    for (var entry : parameters.entrySet()) {
        String javaType = mapSpiceDbType(entry.getValue());
        sb.append("    /** Parameter {@code ").append(entry.getKey())
          .append("} — expected type: {@code ").append(javaType.replace("<", "&lt;").replace(">", "&gt;"))
          .append("} */\n");
        sb.append("    public static final String ").append(toConstant(entry.getKey()))
          .append(" = \"").append(entry.getKey()).append("\";\n\n");
    }

    // ref() method
    sb.append("    /** Build a {@link CaveatRef} for grant-time binding. */\n");
    sb.append("    public static CaveatRef ref(Object... keyValues) {\n");
    sb.append("        return new CaveatRef(NAME, toMap(keyValues));\n");
    sb.append("    }\n\n");

    // context() method
    sb.append("    /** Build a context map for check-time evaluation. */\n");
    sb.append("    public static Map<String, Object> context(Object... keyValues) {\n");
    sb.append("        return toMap(keyValues);\n");
    sb.append("    }\n\n");

    // toMap helper
    sb.append("    private static Map<String, Object> toMap(Object... kv) {\n");
    sb.append("        if (kv.length % 2 != 0) {\n");
    sb.append("            throw new IllegalArgumentException(\n");
    sb.append("                    \"keyValues must have even length (alternating key, value pairs)\");\n");
    sb.append("        }\n");
    sb.append("        var map = new LinkedHashMap<String, Object>();\n");
    sb.append("        for (int i = 0; i < kv.length; i += 2) {\n");
    sb.append("            if (!(kv[i] instanceof String)) {\n");
    sb.append("                throw new IllegalArgumentException(\n");
    sb.append("                        \"Key at index \" + i + \" must be a String, got: \" + kv[i].getClass().getName());\n");
    sb.append("            }\n");
    sb.append("            map.put((String) kv[i], kv[i + 1]);\n");
    sb.append("        }\n");
    sb.append("        return map;\n");
    sb.append("    }\n\n");

    // Private constructor
    sb.append("    private ").append(className).append("() {}\n");
    sb.append("}\n");
    return sb.toString();
}
```

Then, add the summary class emitter:

```java
/** Emit a flat {@code Caveats.java} of string constants. */
static String emitCaveats(String packageName, Set<String> allCaveats) {
    var sb = new StringBuilder();
    sb.append("package ").append(packageName).append(";\n\n");
    sb.append("/**\n")
      .append(" * Canonical caveat names from the SpiceDB schema.\n")
      .append(" * Generated by AuthxCodegen — do not edit.\n")
      .append(" */\n");
    sb.append("public final class Caveats {\n\n");
    for (String name : allCaveats.stream().sorted().toList()) {
        sb.append("    public static final String ").append(toConstant(name))
          .append(" = \"").append(name).append("\";\n");
    }
    sb.append("\n    private Caveats() {}\n");
    sb.append("}\n");
    return sb.toString();
}
```

4. Run test to verify it passes:
```
./gradlew test --tests "*.AuthxCodegenCaveatTest" -x :test-app:test
```

5. Commit:
```
git add -A && git commit -m "feat(codegen): add caveat class emission and type mapping"
```

---

## Task T004: Wire caveat generation into AuthxCodegen.generate()

**Files:**
- Modify: `src/main/java/com/authx/sdk/AuthxCodegen.java`

**Steps:**

1. In the `generate()` method, after the existing `ResourceTypes.java` generation block, add caveat generation. The schema cache is accessible via `client.schema()` which returns a `SchemaCache`. Add this block:

```java
// ── Caveat classes ──
Set<String> caveatNames = schema.getCaveatNames();
if (!caveatNames.isEmpty()) {
    for (String caveatName : caveatNames) {
        var cavDef = schema.getCaveat(caveatName);
        if (cavDef == null) continue;
        String file = emitCaveatClass(cavDef.name(), cavDef.parameters(),
                cavDef.expression(), cavDef.comment(), packageName);
        Path path = basePkgDir.resolve(toPascalCase(caveatName) + ".java");
        Files.writeString(path, file);
        LOG.log(System.Logger.Level.INFO, "  Generated caveat: " + path);
    }

    // Caveats.java summary
    Path caveatsPath = basePkgDir.resolve("Caveats.java");
    Files.writeString(caveatsPath, emitCaveats(packageName, caveatNames));
    LOG.log(System.Logger.Level.INFO, "  Generated: " + caveatsPath);
}
```

2. Run full test suite:
```
./gradlew test -x :test-app:test
```

3. Commit:
```
git add -A && git commit -m "feat(codegen): wire caveat generation into generate() entry point"
```

---

## Task T005: Final verification

**Steps:**

1. Run full test suite including test-app:
```
./gradlew test
```

2. Run compile to ensure no warnings:
```
./gradlew compileJava compileTestJava
```

3. Verify test count increased and 0 failures.

4. Commit any final fixes if needed.
