# Caveat Code Generation

## Summary

Extend `AuthxCodegen` to generate type-safe Java classes for SpiceDB caveat definitions, eliminating magic strings when using caveats with `withCaveat()` and `withContext()`.

## Data Source

`ExperimentalReflectSchemaResponse.getCaveatsList()` returns `List<ExpCaveat>`, each with:
- `getName()` — caveat name (e.g., `ip_allowlist`)
- `getParametersList()` → `ExpCaveatParameter` with `getName()` and `getType()`
- `getExpression()` — CEL expression
- `getComment()` — schema comment

## Requirements

### req-1: SchemaLoader extracts caveat definitions

`SchemaLoader.load()` must iterate `response.getCaveatsList()` and store each caveat's name, parameters (name → type string), expression, and comment into `SchemaCache`.

**Success criteria:** After `load()`, `schemaCache.getCaveats()` returns all caveats from the schema.

### req-2: SchemaCache stores caveat metadata

Add a new record and storage:

```java
public record CaveatDef(
    String name,
    Map<String, String> parameters,  // paramName → typeString
    String expression,
    String comment
) {}
```

New methods:
- `Set<String> getCaveatNames()`
- `CaveatDef getCaveat(String name)`

**Success criteria:** Round-trip — data loaded by SchemaLoader is retrievable from SchemaCache by name.

### req-3: AuthxCodegen generates per-caveat Java classes

For each caveat in the schema, generate `{PascalCaseName}.java` with:
- `public static final String NAME` — the caveat name
- `public static final String {PARAM_NAME}` — constant per parameter
- `public static CaveatRef ref(Object... keyValues)` — builds `CaveatRef` for grant-time binding
- `public static Map<String, Object> context(Object... keyValues)` — builds context map for check-time
- Javadoc on each parameter constant documenting the SpiceDB type
- Javadoc on the class with the CEL expression and schema comment

**Success criteria:** Generated class compiles, constants match schema, `ref()` returns valid `CaveatRef`, `context()` returns valid `Map`.

### req-4: AuthxCodegen generates Caveats.java summary class

Analogous to `ResourceTypes.java` — one `public static final String` per caveat name.

**Success criteria:** `Caveats.IP_ALLOWLIST.equals("ip_allowlist")` for every caveat in schema.

### req-5: keyValues varargs helper

The `ref()` and `context()` methods use `Object... keyValues` (alternating key, value pairs). A private static `toMap(Object...)` helper converts them, throwing `IllegalArgumentException` on odd-length or non-String keys.

**Success criteria:** `ref(ALLOWED_CIDRS, List.of("10.0.0.0/8"))` produces `CaveatRef("ip_allowlist", Map.of("allowed_cidrs", List.of("10.0.0.0/8")))`.

### req-6: Type mapping documented in Javadoc

Parameter constants include Javadoc with the expected Java type:

| SpiceDB type | Javadoc hint |
|---|---|
| `string` | String |
| `int` / `uint` | Long |
| `double` | Double |
| `bool` | Boolean |
| `list<T>` | List\<{mapped T}\> |
| `map<K, V>` | Map\<{mapped K}, {mapped V}\> |
| `any` | Object |
| unknown | raw type string |

**Success criteria:** Generated Javadoc includes the mapped Java type for each parameter.

### req-7: Tests

- Unit test for `SchemaCache` caveat storage (add/retrieve/empty)
- Unit test for `AuthxCodegen` caveat class emission (parse output string, verify constants, methods, Javadoc)
- Unit test for `toMap()` helper (happy path, odd-length, non-String key)
- Integration: codegen round-trip with a schema containing caveats

**Success criteria:** All new tests pass, existing tests unaffected.

## Files to modify

| File | Change |
|---|---|
| `transport/SchemaLoader.java` | Add caveat extraction from reflect response |
| `cache/SchemaCache.java` | Add `CaveatDef` record, storage, accessors |
| `AuthxCodegen.java` | Add `emitCaveatClass()`, `emitCaveats()`, type mapping, `toMap` helper generation |

## Out of scope

- Runtime caveat parameter validation (type checking at SDK layer)
- Distinguishing write-time vs check-time parameters (schema doesn't differentiate)
- Generating strongly-typed method signatures per caveat (kept as `Object...` for simplicity)
