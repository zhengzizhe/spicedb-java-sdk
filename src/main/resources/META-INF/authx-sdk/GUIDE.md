# AuthCSES SDK — Complete Reference

> AI assistants: this is the authoritative reference for the AuthCSES Java SDK.
> Read this file to understand ALL available APIs, configuration options, and best practices.
> This file is bundled inside the SDK JAR at META-INF/authx-sdk/GUIDE.md.

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
    implementation("com.authx:authx-sdk:1.0.0")

    // Optional: cross-JVM SESSION consistency via Redis
    implementation("com.authx:authx-sdk-redisson:1.0.0")

    // Optional: generated type-safe constants for your schema
    implementation("com.authx:authx-sdk-typed:1.0.0")
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>com.authx</groupId>
    <artifactId>authx-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Requirements:** Java 21+

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

```java
// Single user
GrantResult r = doc.grant("editor").to("alice");

// Multiple users (atomic — one gRPC call)
GrantResult r = doc.grant("editor").to("alice", "bob", "carol");

// Collection
GrantResult r = doc.grant("editor").to(userIdList);

// Multiple relations at once
GrantResult r = doc.grant("editor", "can_download").to("alice");

// Non-user subject types
GrantResult r = doc.grant("viewer").toSubjects("group:engineering#member");
GrantResult r = doc.grant("viewer").toSubjects(subjectRefList);
```

**GrantResult fields:**
- `zedToken()` — SpiceDB consistency token (use for write-after-read)
- `count()` — number of relationship updates sent (TOUCH is idempotent)
- `asConsistency()` — shortcut for `Consistency.atLeast(zedToken)`

### 4.2 revoke — Delete relationships

```java
// Mirror of grant API
doc.revoke("editor").from("alice");
doc.revoke("editor").from("alice", "bob");
doc.revoke("editor").from(userIdList);
doc.revoke("editor").fromSubjects("group:engineering#member");

// Remove ALL relations for a user (reads then deletes)
doc.revokeAll().from("alice");

// Remove all of specific relations for a user
doc.revokeAll("editor", "viewer").from("alice");
```

**Returns:** `RevokeResult` (same shape as GrantResult)

### 4.3 check — Permission check

```java
// Single check → CheckResult
CheckResult r = doc.check("view").by("alice");
r.hasPermission();     // boolean
r.isConditional();     // boolean (caveat-based conditional)
r.permissionship();    // Permissionship enum: HAS_PERMISSION / NO_PERMISSION / CONDITIONAL_PERMISSION
r.zedToken();          // consistency token

// With explicit consistency
doc.check("view").withConsistency(Consistency.full()).by("alice");

// Write-after-read pattern
GrantResult gr = doc.grant("editor").to("bob");
doc.check("edit").withConsistency(gr.asConsistency()).by("bob"); // guaranteed to see the grant

// Bulk check — one permission, multiple users → BulkCheckResult
BulkCheckResult bulk = doc.check("view").byAll("alice", "bob", "carol");
bulk.get("alice");        // CheckResult
bulk.asMap();             // Map<String, CheckResult>
bulk.allowed();           // List<String> — users WITH permission
bulk.denied();            // List<String> — users WITHOUT permission
bulk.allowedSet();        // Set<String>
bulk.allAllowed();        // boolean — ALL users have permission?
bulk.anyAllowed();        // boolean — ANY user has permission?
bulk.allowedCount();      // int
```

### 4.4 checkAll — Multiple permissions

```java
// One user, multiple permissions → PermissionSet
PermissionSet perms = doc.checkAll("view", "edit", "delete", "share").by("alice");
perms.can("edit");        // boolean
perms.toMap();            // Map<String, Boolean>
perms.allowed();          // Set<String> of granted permissions
perms.denied();           // Set<String> of denied permissions

// Multiple users × multiple permissions → PermissionMatrix
PermissionMatrix matrix = doc.checkAll("view", "edit").byAll("alice", "bob");
matrix.get("alice");               // PermissionSet
matrix.get("alice").can("edit");   // boolean
matrix.whoCanAll("view", "edit");  // List<String> — users with ALL permissions
matrix.whoCanAny("view", "edit");  // List<String> — users with ANY permission
```

### 4.5 who — Reverse lookup (who has access?)

```java
// By permission (recursive — computes through permission tree)
List<String> viewers = doc.who().withPermission("view").fetch();

// By relation (exact match — only direct relationships)
Set<String> editors = doc.who().withRelation("editor").fetchSet();

// Terminal methods
doc.who().withPermission("view").fetch();          // List<String>
doc.who().withPermission("view").fetchSet();        // Set<String>
doc.who().withPermission("view").fetchFirst();      // Optional<String>
doc.who().withPermission("view").fetchCount();      // int
doc.who().withPermission("view").fetchExists();     // boolean

// With consistency
doc.who().withPermission("view")
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
    .grant("owner").to("carol")
    .grant("editor").to("dave")
    .revoke("owner").from("alice")
    .execute();

r.zedToken();
r.asConsistency();
```

### 4.8 lookup — Cross-resource query

```java
// Which documents can alice view?
List<String> docIds = client.lookup("document")
    .withPermission("view")
    .by("alice")
    .fetch();

// Terminal methods (same as who())
client.lookup("document").withPermission("view").by("alice").fetch();
client.lookup("document").withPermission("view").by("alice").fetchSet();
client.lookup("document").withPermission("view").by("alice").fetchFirst();
client.lookup("document").withPermission("view").by("alice").fetchCount();
client.lookup("document").withPermission("view").by("alice").fetchExists();

// With consistency
client.lookup("document").withPermission("view").by("alice")
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
doc.grant("editor").to("bob");
// SDK internally tracks the write token
doc.check("edit").by("bob").hasPermission();  // guaranteed true (session consistency)
```

If you pass an explicit consistency, it takes precedence over the policy.

---

## 6. Subject References

The SDK defaults bare string IDs to `"user"` type:

```java
doc.grant("editor").to("alice");
// equivalent to: subject = user:alice
```

For non-user subjects, use `toSubjects()` / `fromSubjects()`:

```java
doc.grant("viewer").toSubjects("group:engineering#member");
doc.grant("viewer").toSubjects("department:sales#member", "group:admins#member");
```

Format: `"type:id"` or `"type:id#relation"`

Helper class (from sdk-typed, if available):
```java
import com.authx.sdk.typed.Subjects;

doc.grant("viewer").toSubjects(Subjects.groupMember("engineering"));
// → "group:engineering#member"
```

---

## 7. Schema Validation

Client-side schema validation was removed on 2026-04-18 alongside the
SchemaCache subsystem (see ADR). Invalid resource types, relations, or
subject types now fail at the SpiceDB boundary with
`AuthxInvalidArgumentException`. The SpiceDB error message carries the
same diagnostic detail.

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

## 11. Typed Constants (sdk-typed)

If you add `authx-sdk-typed`, you get compile-time-safe constants:

```java
import com.authx.sdk.typed.constants.Document;
import com.authx.sdk.typed.Subjects;

doc.grant(Document.EDITOR).to("alice");
doc.check(Document.VIEW).by("alice");
doc.grant(Document.VIEWER).toSubjects(Subjects.groupMember("engineering"));

// Available arrays
Document.ALL_RELATIONS;    // String[]
Document.ALL_PERMISSIONS;  // String[]
```

Generate with: `./gradlew :sdk-codegen:run --args="--namespace dev"`

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
    doc.grant("editor").to("alice");

    // InMemory: check matches on relation name (no recursive permission computation)
    assertTrue(doc.check("editor").by("alice").hasPermission());
    assertFalse(doc.check("editor").by("bob").hasPermission());
}

@Test
void revokeRemovesAccess() {
    var doc = client.resource("document", "doc-1");
    doc.grant("editor").to("alice");
    doc.revoke("editor").from("alice");

    assertFalse(doc.check("editor").by("alice").hasPermission());
}

@Test
void batchIsAtomic() {
    var doc = client.resource("document", "doc-1");
    doc.grant("owner").to("alice");

    doc.batch()
        .revoke("owner").from("alice")
        .grant("owner").to("bob")
        .grant("editor").to("alice")
        .execute();

    assertFalse(doc.check("owner").by("alice").hasPermission());
    assertTrue(doc.check("owner").by("bob").hasPermission());
    assertTrue(doc.check("editor").by("alice").hasPermission());
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
    if (!client.resource("document", id).check("view").by(userId).hasPermission()) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return documentService.findById(id);
}
```

### Render UI buttons based on permissions

```java
PermissionSet perms = client.resource("document", docId)
    .checkAll("view", "edit", "delete", "share")
    .by(currentUserId);

model.addAttribute("canEdit", perms.can("edit"));
model.addAttribute("canDelete", perms.can("delete"));
model.addAttribute("canShare", perms.can("share"));
```

### List all documents a user can access

```java
List<String> docIds = client.lookup("document")
    .withPermission("view")
    .by(userId)
    .fetch();
```

### Transfer ownership

```java
client.resource("document", docId).batch()
    .revoke("owner").from(oldOwner)
    .grant("owner").to(newOwner)
    .grant("editor").to(oldOwner)  // demote to editor
    .execute();
```

### Grant department-wide access

```java
client.resource("document", docId)
    .grant("viewer")
    .toSubjects("group:engineering#member");
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
