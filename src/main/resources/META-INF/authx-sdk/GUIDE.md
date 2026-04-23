# AuthX SpiceDB SDK — Reference

> AI assistants: this file is bundled inside the SDK JAR at
> META-INF/authx-sdk/GUIDE.md as a quick reference.
>
> **Partial 2.0 update (2026-04-23)**: installation coordinates, the
> schema-validation section, and the bare-id examples have been
> corrected for 2.0. Other sections may still reflect pre-2.0 shapes —
> cross-check against `README.md`, `docs/adr/`, and `CHANGELOG.md` in
> the repository before relying on any API surface here.

---

## 1. What is this SDK?

AuthCSES SDK provides a fluent Java API for permission management backed by SpiceDB.
The SDK connects to the SpiceDB to get SpiceDB credentials, then directly
connects to SpiceDB via gRPC for low-latency permission checks.

**Architecture:**
```
Your App → SDK → SpiceDB (gRPC direct, <5ms latency)
                
```

**Key characteristics:**
- Thread-safe: one client instance shared across all threads
- High-performance: request coalescing, circuit breaker, Resilience4j
- Per-resource-type policies: consistency, retry, circuit-breaker per type
- Zero-config telemetry: async operation logging via TelemetrySink SPI

**Note (2026-04-18):** The SDK no longer caches decisions client-side.
See [ADR 2026-04-18](../../../../docs/adr/2026-04-18-remove-l1-cache.md).
Use `Consistency.minimizeLatency()` to hit SpiceDB's server-side
dispatch cache for low-latency reads.

---

## 2. Installation

```groovy
// Gradle
dependencies {
    implementation("io.github.authxkit:authx-spicedb-sdk:2.0.1")

    // Optional: Redisson-backed DistributedTokenStore for cross-JVM
    // SESSION consistency. Main SDK stays Redisson-free.
    implementation("io.github.authxkit:authx-spicedb-sdk-redisson:2.0.1")
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>io.github.authxkit</groupId>
    <artifactId>authx-spicedb-sdk</artifactId>
    <version>2.0.1</version>
</dependency>
```

**Requirements:** Java 21+

Typed constants (`Document.Rel`, `Document.Perm`, caveats) are generated
from your schema by `AuthxCodegen` in this SDK — there is no separate
`sdk-typed` module. See `docs/migration-schema-flat-descriptors.md`.

---

## 3. Initialization

### 3.1 Production Client

```java
import com.authx.sdk.AuthxClient;
import com.authx.sdk.policy.*;
import java.time.Duration;

AuthxClient client = AuthxClient.builder()
    .connection(c -> c
        .target("dns:///spicedb.prod:50051")       // SpiceDB URL
        .presharedKey("my-key")                    // SpiceDB preshared key
        .requestTimeout(Duration.ofSeconds(5))     // Per-request timeout (default: 5s)
        .tls(false))                               // TLS for SpiceDB gRPC (default: false)
    .features(f -> f
        .virtualThreads(true)                      // Java 21 virtual threads for internal threads
        .coalescing(true)                          // Deduplicate concurrent identical checks (default: true)
        .telemetry(true)                           // Async operation logging via TelemetrySink SPI
        .defaultSubjectType("user"))               // Default subject type for bare user IDs
    .extend(e -> e
        .policies(PolicyRegistry.builder()         // Per-resource-type policies (optional, see section 8)
            .defaultPolicy(ResourcePolicy.builder()
                .readConsistency(ReadConsistency.session())
                .retry(RetryPolicy.defaults())
                .circuitBreaker(CircuitBreakerPolicy.defaults())
                .timeout(Duration.ofSeconds(5))
                .build())
            .build()))
    .build();
```

`build()` performs the following steps:
1. Create gRPC channel to SpiceDB
2. Build transport chain (resilient → coalescing → instrumented → gRPC)
3. Start metrics rotation scheduler

### 3.2 Test Client (no external services)

```java
AuthxClient client = AuthxClient.inMemory();
```

InMemory behavior:
- grant/revoke → stores in a ConcurrentHashMap
- check → exact match on relation name (no recursive permission computation)
- No circuit breaker
- Thread-safe

### 3.3 Lifecycle

```java
// Create once at application startup
AuthxClient client = AuthxClient.builder()...build();

// Use throughout application lifetime
client.resource("document", "doc-1").check("view").by("alice");

// Close at application shutdown (flushes telemetry, closes gRPC channel)
client.close();

// Or use try-with-resources
try (var client = AuthxClient.builder()...build()) {
    // use client
}
```

---

## 4. Core API — ResourceHandle

All operations start with `client.resource(type, id)`:

```java
var doc = client.resource("document", "doc-123");
```

### 4.1 grant — Write relationships

Subjects must be canonical `type:id` / `type:id#relation` / `type:*`
(no default subject type). Bare ids like `"alice"` only work for single
arguments when the schema is loaded and the relation declares exactly
one non-wildcard subject type; prefer canonical form in documentation.

```java
// Single subject (canonical)
GrantResult r = doc.grant("editor").to("user:alice");

// Multiple subjects — atomic single RPC
GrantResult r = doc.grant("editor").to("user:alice", "user:bob", "user:carol");

// Collection of canonical refs
GrantResult r = doc.grant("editor").to(List.of("user:alice", "user:bob"));

// Multiple relations in one call
GrantResult r = doc.grant("editor", "can_download").to("user:alice");

// Non-user subject types
GrantResult r = doc.grant("viewer").to("group:engineering#member");
GrantResult r = doc.grant("viewer").to("user:*");   // wildcard

// Typed subject form (no string-assembly)
GrantResult r = doc.grant("viewer").to(User, "alice");
GrantResult r = doc.grant("viewer").to(Group, "eng", "member");
GrantResult r = doc.grant("viewer").toWildcard(User);
```

**GrantResult fields:**
- `zedToken()` — SpiceDB consistency token (use for write-after-read)
- `count()` — number of relationship updates sent (TOUCH is idempotent)
- `asConsistency()` — shortcut for `Consistency.atLeast(zedToken)`

For the **typed write path** (`client.on(Document).select(id).grant(...)...`),
writes are batched into a `WriteFlow` and require an explicit `.commit()`
— see the WriteFlow section in `README.md`.

### 4.2 revoke — Delete relationships

Mirrors the grant API. All overloads require canonical subject strings
(bare-id inference applies only to the single-string overload with
schema loaded).

```java
doc.revoke("editor").from("user:alice");
doc.revoke("editor").from("user:alice", "user:bob");
doc.revoke("editor").from(List.of("user:alice", "user:bob"));
doc.revoke("editor").from("group:engineering#member");

// Typed subject form
doc.revoke("editor").from(User, "alice");

// Filter-based: remove ALL relations of this subject on this resource
doc.revokeAll().from("user:alice");

// Filter-based: remove only the listed relations
doc.revokeAll("editor", "viewer").from("user:alice");
```

**Returns:** `RevokeResult` (same shape as GrantResult)

### 4.3 check — Permission check

```java
// Single check → CheckResult
CheckResult r = doc.check("view").by("user:alice");
r.hasPermission();     // boolean
r.isConditional();     // boolean (caveat-based conditional)
r.permissionship();    // Permissionship enum: HAS_PERMISSION / NO_PERMISSION / CONDITIONAL_PERMISSION
r.zedToken();          // consistency token

// With explicit consistency
doc.check("view").withConsistency(Consistency.full()).by("user:alice");

// Typed subject form
doc.check("view").by(User, "alice");

// Write-after-read pattern
GrantResult gr = doc.grant("editor").to("user:bob");
doc.check("edit").withConsistency(gr.asConsistency()).by("user:bob"); // guaranteed to see the grant

// Bulk check — one permission, multiple subjects → BulkCheckResult
BulkCheckResult bulk = doc.check("view").byAll("user:alice", "user:bob", "user:carol");
bulk.get("user:alice");   // CheckResult
bulk.asMap();             // Map<String, CheckResult>
bulk.allowed();           // List<String> — subjects WITH permission
bulk.denied();            // List<String> — subjects WITHOUT permission
bulk.allowedSet();        // Set<String>
bulk.allAllowed();        // boolean — ALL subjects have permission?
bulk.anyAllowed();        // boolean — ANY subject has permission?
bulk.allowedCount();      // int
```

### 4.4 checkAll — Multiple permissions

```java
// One subject, multiple permissions → PermissionSet
PermissionSet perms = doc.checkAll("view", "edit", "delete", "share").by("user:alice");
perms.can("edit");        // boolean
perms.toMap();            // Map<String, Boolean>
perms.allowed();          // Set<String> of granted permissions
perms.denied();           // Set<String> of denied permissions

// Multiple subjects × multiple permissions → PermissionMatrix
PermissionMatrix matrix = doc.checkAll("view", "edit").byAll("user:alice", "user:bob");
matrix.get("user:alice");              // PermissionSet
matrix.get("user:alice").can("edit");  // boolean
matrix.whoCanAll("view", "edit");      // List<String> — subjects with ALL
matrix.whoCanAny("view", "edit");      // List<String> — subjects with ANY
```

### 4.5 who — Reverse lookup (who has access?)

`who()` requires the subject type to look up — SpiceDB's LookupSubjects
RPC always filters by subject type.

```java
// By permission (recursive — computes through permission tree)
List<String> viewerIds = doc.who("user").withPermission("view").fetch();

// By relation (exact match — only direct relationships)
Set<String> editorIds = doc.who("user").withRelation("editor").fetchSet();

// Typed subject-type form
List<String> viewerIds = doc.who(User).withPermission("view").fetch();

// Terminal methods
doc.who("user").withPermission("view").fetch();          // List<String>
doc.who("user").withPermission("view").fetchSet();        // Set<String>
doc.who("user").withPermission("view").fetchFirst();      // Optional<String>
doc.who("user").withPermission("view").fetchCount();      // int
doc.who("user").withPermission("view").fetchExists();     // boolean

// With consistency
doc.who("user").withPermission("view")
    .withConsistency(Consistency.full())
    .fetch();
```

**IMPORTANT:** `withPermission()` uses LookupSubjects (recursive). `withRelation()` uses ReadRelationships (exact).
Using a relation name with `withPermission()` or vice versa will be rejected by SpiceDB's server-side validation.

### 4.6 relations — Read relationships

```java
// All relations on this resource
List<Tuple> all = doc.relations().fetch();

// Filtered by relation name
List<Tuple> editors = doc.relations("editor").fetch();
List<Tuple> editorsAndViewers = doc.relations("editor", "viewer").fetch();

// Terminal methods
doc.relations("editor").fetch();                    // List<Tuple>
doc.relations("editor").fetchSet();                 // Set<Tuple>
doc.relations("editor").fetchFirst();               // Optional<Tuple>
doc.relations("editor").fetchCount();               // int
doc.relations("editor").fetchExists();              // boolean
doc.relations("editor").fetchSubjectIds();          // List<String> (just IDs)
doc.relations("editor").fetchSubjectIdSet();        // Set<String>

// Grouping
doc.relations().groupByRelationTuples();                   // Map<String, List<Tuple>>
doc.relations().groupByRelation();                         // Map<String, List<String>>
```

**Tuple fields:** `resourceType()`, `resourceId()`, `relation()`, `subjectType()`, `subjectId()`, `subjectRelation()`, `subject()` (formatted string), `resource()` (formatted string)

### 4.7 batch — Atomic multi-operation

```java
// Mixed grant + revoke in a single atomic gRPC call
BatchResult r = doc.batch()
    .grant("owner").to("user:carol")
    .grant("editor").to("user:dave")
    .revoke("owner").from("user:alice")
    .execute();

r.zedToken();
r.asConsistency();
```

For cross-resource atomic writes (grant on doc1 AND revoke on doc2 in
one RPC), use `client.batch()` → `CrossResourceBatchBuilder`.

### 4.8 lookup — Cross-resource query

```java
// Which documents can alice view?
List<String> docIds = client.lookup("document")
    .withPermission("view")
    .by("user:alice")
    .fetch();

// Terminal methods (same as who())
client.lookup("document").withPermission("view").by("user:alice").fetch();
client.lookup("document").withPermission("view").by("user:alice").fetchSet();
client.lookup("document").withPermission("view").by("user:alice").fetchFirst();
client.lookup("document").withPermission("view").by("user:alice").fetchCount();
client.lookup("document").withPermission("view").by("user:alice").fetchExists();

// With consistency
client.lookup("document").withPermission("view").by("user:alice")
    .withConsistency(Consistency.full())
    .fetch();
```

**IMPORTANT:** `withPermission()` and `by()` must be called before any terminal method, or `IllegalStateException` is thrown.

---

## 5. Consistency Model

### 5.1 Explicit Consistency (per-operation)

```java
import com.authx.sdk.model.Consistency;

doc.check("view").withConsistency(Consistency.full()).by("alice");              // always latest
doc.check("view").withConsistency(Consistency.minimizeLatency()).by("alice");   // fastest, may be stale
doc.check("view").withConsistency(Consistency.atLeast(zedToken)).by("alice");   // at least this fresh
doc.check("view").withConsistency(Consistency.atExactSnapshot(token)).by("alice"); // exact snapshot (pagination)
```

### 5.2 Policy-Based Consistency (per-resource-type)

```java
import com.authx.sdk.policy.ReadConsistency;

// Configured via PolicyRegistry — applied automatically when no explicit consistency is set
ReadConsistency.strong()                           // fully_consistent=true
ReadConsistency.session()                          // atLeast(lastWriteToken) — DEFAULT
ReadConsistency.minimizeLatency()                  // fastest, may be stale
ReadConsistency.boundedStaleness(Duration.ofSeconds(5))  // accept data ≤5s old
ReadConsistency.monotonicRead()                    // never read older than last read
ReadConsistency.snapshot()                         // exact snapshot for pagination
```

### 5.3 Automatic Write-After-Read

The SDK automatically tracks ZedTokens. After any write (grant/revoke/batch),
subsequent reads using the default consistency (session) will automatically use
`atLeast(lastWriteToken)` — preventing stale reads.

```java
doc.grant("editor").to("user:bob");
// SDK internally tracks the write token
doc.check("edit").by("user:bob").hasPermission();  // guaranteed true (session consistency)
```

If you pass an explicit consistency, it takes precedence over the policy.

---

## 6. Subject References

Subjects are canonical `type:id` / `type:id#relation` / `type:*`. The
SDK **does not** assume a default subject type — `"alice"` is rejected;
write `"user:alice"`.

```java
doc.grant("editor").to("user:alice");
doc.grant("viewer").to("group:engineering#member");
doc.grant("viewer").to("department:sales#member", "group:admins#member");
doc.grant("viewer").to("user:*");  // wildcard
```

Bare-id sugar: when a `SchemaCache` is loaded and a relation declares
exactly one non-wildcard subject type, the single-string overloads of
`.to(id)` / `.from(id)` infer the type. The varargs / iterable / byAll
overloads never infer and always require canonical strings.

Typed ergonomics: prefer the generated typed entry points for
compile-time safety. After running `AuthxCodegen` against your schema:

```java
import static com.your.app.schema.Schema.*;

doc.grant("viewer").to(Group, "engineering", "member");   // → group:engineering#member
doc.grant("viewer").toWildcard(User);                     // → user:*

// Or go fully typed from the top:
client.on(Document).select("doc-1")
    .grant(Document.Rel.VIEWER).to(User, "alice")
    .commit();
```

---

## 7. Schema Validation

Schema metadata (resource types, relations, permissions, per-relation
allowed subject types, caveat definitions) is loaded once at
`AuthxClient.build()` via SpiceDB's `ExperimentalReflectSchema` RPC
and stored in a metadata-only `SchemaCache`. This powers:

- **Fail-fast subject-type validation** on every grant / revoke /
  WriteFlow commit — invalid subject types raise
  `InvalidRelationException` locally before any RPC.
- **Typed-overload inference** — `grant(...).to(User, "alice")` and the
  bare-id single-string path use the cached schema to construct the
  canonical ref.
- **`AuthxCodegen`** — emits typed constants (`Document.Rel.VIEWER`,
  `Document.Perm.VIEW`, `Caveats`) from the same metadata.

The `SchemaCache` holds **no decision cache** — decisions always go to
SpiceDB (see ADR 2026-04-18). Schema load is non-fatal: if SpiceDB is
older and returns `UNIMPLEMENTED`, the cache stays empty, fail-fast
validation becomes a no-op, and the SDK still works (invalid subjects
will surface as `AuthxInvalidArgumentException` from SpiceDB instead).

Disable schema load for offline / unit-test setups:
```java
AuthxClient.builder()
    .connection(...)
    .loadSchemaOnStart(false)
    .build();
```

---

## 8. Per-Resource-Type Policies

Different resource types often need different strategies:

```java
.extend(e -> e.policies(PolicyRegistry.builder()
    // Global defaults (applied to any type without specific config)
    .defaultPolicy(ResourcePolicy.builder()
        .readConsistency(ReadConsistency.session())
        .retry(RetryPolicy.builder()
            .maxAttempts(3)
            .baseDelay(Duration.ofMillis(50))
            .maxDelay(Duration.ofSeconds(5))
            .multiplier(2.0)
            .jitterFactor(0.2)
            .build())
        .circuitBreaker(CircuitBreakerPolicy.builder()
            .failureRateThreshold(50.0)
            .slidingWindowSize(100)
            .waitInOpenState(Duration.ofSeconds(30))
            .build())
        .timeout(Duration.ofSeconds(5))
        .build())

    // Document: shorter timeout
    .forResourceType("document", ResourcePolicy.builder()
        .timeout(Duration.ofSeconds(3))
        .build())

    // Folder: bounded staleness reads OK
    .forResourceType("folder", ResourcePolicy.builder()
        .readConsistency(ReadConsistency.boundedStaleness(Duration.ofSeconds(10)))
        .build())

    // Group: membership must be strongly consistent, no retry
    .forResourceType("group", ResourcePolicy.builder()
        .readConsistency(ReadConsistency.strong())
        .retry(RetryPolicy.disabled())
        .build())

    .build()))
```

**Resolution order:** per-type policy → global default.
Fields not set in a more specific policy inherit from the parent.

---

## 9. Exception Hierarchy

```
AuthxException (RuntimeException)
├── AuthxAuthException          — invalid API Key, forbidden
├── AuthxConnectionException    — SpiceDB/platform unreachable
├── AuthxTimeoutException       — request timeout
├── CircuitBreakerOpenException    — circuit breaker is open
├── InvalidResourceException       — resource type not in schema
├── InvalidPermissionException     — permission not in schema (or relation/permission confusion)
└── InvalidRelationException       — relation not in schema (or relation/permission confusion)
```

**Retry behavior:**
- `AuthxConnectionException`, `AuthxTimeoutException` → retried (transient)
- `AuthxAuthException`, `InvalidResourceException`, etc. → NOT retried (permanent)
- `CircuitBreakerOpenException` → NOT retried (circuit is open)

---

## 10. Transport Architecture

SDK uses a decorator chain (outermost → innermost). As of ADR 2026-04-18
the CachedTransport and Watch infrastructure have been removed:

```
InterceptorTransport        — user-supplied SdkInterceptor hooks
  → InstrumentedTransport   — telemetry + metrics recording
    → CoalescingTransport   — deduplicate concurrent identical check() calls
      → PolicyAwareConsistency  — apply per-type ReadConsistency + track ZedTokens
        → ResilientTransport    — Resilience4j CB + retry + bulkhead + rate limit (per-type)
          → GrpcTransport       — SpiceDB gRPC (preshared key bearer token)
```

**Decision caching:**
- The SDK does NOT cache decisions client-side (removed 2026-04-18).
- SpiceDB's server-side dispatch cache handles decision caching
  (schema-aware, correct across inheritance).
- Use `Consistency.minimizeLatency()` for reads that should hit the
  server-side cache.

---

## 11. Typed Constants (AuthxCodegen)

Typed constants are generated by `AuthxCodegen` in the main SDK —
there is no separate `sdk-typed` module. After running codegen against
your schema, use:

```java
import static com.your.app.schema.Schema.*;   // flat descriptors

// Typed check
client.on(Document).select(id)
    .check(Document.Perm.VIEW).by(User, "alice");

// Typed grant (WriteFlow — must commit)
client.on(Document).select(id)
    .grant(Document.Rel.EDITOR).to(User, "alice")
    .commit();

// Typed reverse lookup
client.on(Document).select(id)
    .who(User, Document.Perm.VIEW).fetchIds();
```

Generated enums implement `Relation.Named` / `Permission.Named` and
carry the schema-declared subject-type metadata used by runtime
fail-fast validation. See `docs/migration-schema-flat-descriptors.md`
for the 2026-04-22 import-static-based ergonomics.

---

## 12. Testing

```java
// Unit test — no external services
@BeforeEach
void setup() {
    client = AuthxClient.inMemory();
}

@Test
void editorCanEdit() {
    var doc = client.resource("document", "doc-1");
    doc.grant("editor").to("user:alice");

    // InMemory: check matches on relation name (no recursive permission computation)
    assertTrue(doc.check("editor").by("user:alice").hasPermission());
    assertFalse(doc.check("editor").by("user:bob").hasPermission());
}

@Test
void revokeRemovesAccess() {
    var doc = client.resource("document", "doc-1");
    doc.grant("editor").to("user:alice");
    doc.revoke("editor").from("user:alice");

    assertFalse(doc.check("editor").by("user:alice").hasPermission());
}

@Test
void batchIsAtomic() {
    var doc = client.resource("document", "doc-1");
    doc.grant("owner").to("user:alice");

    doc.batch()
        .revoke("owner").from("user:alice")
        .grant("owner").to("user:bob")
        .grant("editor").to("user:alice")
        .execute();

    assertFalse(doc.check("owner").by("user:alice").hasPermission());
    assertTrue(doc.check("owner").by("user:bob").hasPermission());
    assertTrue(doc.check("editor").by("user:alice").hasPermission());
}
```

---

## 13. Enums

All constants use enums (no magic strings):

| Enum | Values | Usage |
|------|--------|-------|
| `Permissionship` | HAS_PERMISSION, NO_PERMISSION, CONDITIONAL_PERMISSION | `checkResult.permissionship()` |
| `SdkAction` | CHECK, CHECK_BULK, WRITE, DELETE, READ, LOOKUP_SUBJECTS, LOOKUP_RESOURCES | Telemetry |
| `OperationResult` | SUCCESS, ERROR | Telemetry |
| `CircuitBreaker.State` | CLOSED, OPEN, HALF_OPEN | Circuit breaker state |

---

## 14. Common Patterns

### Permission gate in a REST controller

```java
@GetMapping("/documents/{id}")
public Document getDocument(@PathVariable String id, @RequestHeader("X-User-Id") String userId) {
    if (!client.resource("document", id).check("view").by("user:" + userId).hasPermission()) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return documentService.findById(id);
}
```

### Render UI buttons based on permissions

```java
PermissionSet perms = client.resource("document", docId)
    .checkAll("view", "edit", "delete", "share")
    .by("user:" + currentUserId);

model.addAttribute("canEdit", perms.can("edit"));
model.addAttribute("canDelete", perms.can("delete"));
model.addAttribute("canShare", perms.can("share"));
```

### List all documents a user can access

```java
List<String> docIds = client.lookup("document")
    .withPermission("view")
    .by("user:" + userId)
    .fetch();
```

### Transfer ownership

```java
client.resource("document", docId).batch()
    .revoke("owner").from("user:" + oldOwner)
    .grant("owner").to("user:" + newOwner)
    .grant("editor").to("user:" + oldOwner)  // demote to editor
    .execute();
```

### Grant department-wide access

```java
client.resource("document", docId)
    .grant("viewer")
    .to("group:engineering#member");
```

---

## 15. Logging & Tracing

### Overview

SDK logs go through `java.lang.System.Logger` — the JDK's zero-dependency
logging facade. The backend is host-chosen: `java.util.logging` by default,
or route to SLF4J / Log4j 2 / Logback via standard bridges. On top of
`System.Logger`, the SDK adds three non-invasive enrichment layers:

- **Trace-id prefix** — every message is prepended with `[trace=<16hex>] `
  when an OpenTelemetry span is active. Requires only the OTel API
  (already a direct dependency); no SDK configuration needed.
- **SLF4J MDC bridge** — when `org.slf4j:slf4j-api` (2.0.13) is on the
  classpath, the SDK pushes up to 15 `authx.*` keys onto the per-thread
  MDC at each RPC entry and clears them on return. Absent SLF4J, the
  bridge is a silent no-op; no classes are loaded.
- **WARN+ suffix** — messages at `WARN` or higher with resource context
  in scope get a trailing ` [type=... res=... perm|rel=... subj=...]` so
  readers without structured MDC still see the affected entity.

### MDC fields

Up to 15 keys are pushed, omitted when blank:

`authx.traceId`, `authx.spanId`, `authx.action`, `authx.resourceType`,
`authx.resourceId`, `authx.permission`, `authx.relation`, `authx.subject`,
`authx.consistency`, `authx.retry.attempt`, `authx.retry.max`,
`authx.cb.state`, `authx.caveat`, `authx.expiresAt`, `authx.zedToken`.

### OTel integration

The SDK uses `GlobalOpenTelemetry` via `Span.current()`. Any standard OTel
SDK install registered globally at host startup is picked up automatically;
no SDK-side wiring is required.

Key span attributes set by the SDK:
`authx.action`, `authx.resource.type`, `authx.resource.id`,
`authx.permission`, `authx.subject`, `authx.consistency`, `authx.result`,
`authx.errorType`, `authx.retry.attempt`, `authx.retry.max`.

### SLF4J wiring (recommended)

```gradle
dependencies {
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.slf4j:jul-to-slf4j:2.0.13")  // System.Logger → SLF4J
}
```

Install the JUL bridge once at startup:

```java
org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
org.slf4j.bridge.SLF4JBridgeHandler.install();
```

See `docs/logging-guide.md` for complete pattern examples (minimal / middle
/ full JSON), the level-semantics table, and the seven stability guarantees
(SG-1..SG-7) that govern the logging layer.
