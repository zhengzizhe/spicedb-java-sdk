# AuthX SpiceDB SDK

Java 21 SDK for SpiceDB. The SDK connects directly to SpiceDB over gRPC and
provides fluent business APIs for permission checks, relationship writes,
lookups, batch checks, resilience, and observability.

## Installation

```groovy
dependencies {
    implementation("io.github.authxkit:authx-spicedb-sdk:3.0.2")
}
```

## Client

```java
AuthxClient client = AuthxClient.builder()
    .connection(c -> c
        .target("dns:///spicedb.prod:50051")
        .presharedKey("my-key")
        .tls(true))
    .build();
```

`AuthxClient` implements `AutoCloseable`; use try-with-resources or close it
during application shutdown.

## Fluent API

Typed generated schema descriptors are preferred when available:

```java
WriteCompletion completion = client.on(Document)
    .select("doc-1")
    .grant(Document.Rel.EDITOR)
    .to(User, "alice")
    .commit();

boolean allowed = client.on(Document)
    .select("doc-1")
    .check(Document.Perm.VIEW)
    .by(User, "alice");
```

Dynamic string-based code has the same shape:

```java
client.on("document")
    .select("doc-1")
    .grant("editor")
    .to("user:alice")
    .commit();

CheckResult result = client.on("document")
    .select("doc-1")
    .check("view")
    .detailedBy("user:alice");
```

`CheckResult` exposes only values the SDK can source from SpiceDB:
`permissionship` and `zedToken`.

## Writes And Listeners

Writes return `WriteFlow` and must end in `commit()`.

```java
WriteCompletion completion = client.on("document")
    .select("doc-1")
    .grant("viewer")
    .to("user:bob")
    .commit();
```

Listeners are registered before commit. `listener(...)` returns a
`WriteListenerStage`; that stage's `commit()` returns
`CompletableFuture<WriteCompletion>` and completes after the asynchronous
listener finishes.

```java
CompletableFuture<WriteCompletion> future = client.on("document")
    .select("doc-1")
    .grant("viewer")
    .to("user:bob")
    .listener(done -> audit(done.updateCount(), done.zedToken()))
    .commit();
```

`WriteCompletion.updateCount()` is the number of submitted relationship updates
in the fluent flow. Lower-level `WriteResult.submittedUpdateCount()` has the
same meaning. For gRPC operations such as `deleteByFilter`, SpiceDB returns a
revision token but not a count; the SDK uses
`WriteResult.UNKNOWN_SUBMITTED_UPDATE_COUNT` and
`submittedUpdateCountKnown() == false`.

## Batch And Lookup

```java
WriteCompletion batch = client.batch()
    .on("document", "doc-1")
        .grant("owner").to("user:alice")
    .on("folder", "folder-1")
        .grant("viewer").to("user:alice")
    .commit();

CheckMatrix matrix = client.batchCheck()
    .add("document", "doc-1", "view", SubjectRef.parse("user:alice"))
    .add("folder", "folder-1", "view", SubjectRef.parse("user:alice"))
    .fetch();

List<String> docs = client.on("document")
    .lookupResources("user:alice")
    .limit(100)
    .can("view");
```

## Validation

Empty or malformed required inputs fail fast at the SDK boundary. Dynamic
subjects must use canonical references such as `user:alice`,
`group:eng#member`, or `user:*`.

## Schema Management

`client.schema()` exposes both cached schema metadata and live SpiceDB schema
management APIs:

```java
SchemaReadResult current = client.schema().readRaw();

SchemaDiffResult diff = client.schema().diffRaw(candidateSchema);
if (diff.hasDiffs()) {
    diff.diffs().forEach(d -> log.info("{} {}", d.kind(), d.target()));
}

SchemaWriteResult written = client.schema().writeRaw(candidateSchema);
client.schema().refresh();
```

`readRaw()`, `writeRaw(...)`, `diffRaw(...)`, and `refresh()` require a live
SpiceDB-backed client. In-memory clients fail fast with
`UnsupportedOperationException`. Blank schema inputs fail locally before a gRPC
request is made, and SpiceDB gRPC failures are mapped into the SDK exception
hierarchy.

## Caveats

Generated schema code emits typed caveat builders when SpiceDB schema
reflection includes caveat definitions:

```java
Instant expiresAt = subscription.expiresAt();
Instant now = clock.instant();

CaveatRef grantCaveat = NotExpired.builder()
    .expiresAt(expiresAt)
    .ref();

CaveatContext checkContext = NotExpired.builder()
    .now(now)
    .context();
```

The generated setter names come from the SpiceDB caveat parameter names. The
SDK does not define business conditions; the CEL expression remains in the
SpiceDB schema and applications provide runtime values. Builders validate
parameter names and Java value types locally. Use `completeRef()` /
`completeContext()` when you want all known parameters to be required in one
object. Existing `Map<String, Object>` caveat APIs still work for dynamic code
and future SpiceDB caveat types.

## Watch / Change Streams

The SDK intentionally does not expose a stable `client.watch(...)` API in this
release. SpiceDB Watch is a server-streaming API that requires persisted resume
tokens, reconnect handling, idempotent consumers, replay tolerance, and rebuild
logic when a token falls outside the datastore history window.

For most applications, record audit/search-index changes at the business write
path. If a future Watch API is added, it will be documented as an advanced
at-least-once event stream rather than a cache invalidation mechanism.

## Testing

The primary verification suite uses a real SpiceDB container:

```bash
./gradlew clean test spicedbE2eTest
```

`spicedbE2eTest` is tagged `spicedb-e2e` and fails if Docker/Testcontainers is
unavailable.


## Unreleased: 3.0.3-SNAPSHOT authorization hardening

The published installation above remains 3.0.2. The local snapshot adds:

- Effective consistency is resolved before coalescing. Context maps (including nested
  values) participate in request identity. Fully consistent head reads are not shared
  with earlier in-flight reads. Waiters respect their own deadline/cancellation.
- `client.batchCheck().add(type, id, permission, subject, context).fetchDetailed()`
  preserves each requested cell, condition state and revision token. Duplicate cells
  with different contexts remain separate. `fetchInSingleSnapshot()` accepts at most
  500 cells and verifies that all actual outcomes carry the same nonblank token;
  policy grouping may otherwise produce multiple revisions. Existing boolean matrices
  remain available. Bulk context now survives fluent and raw transport paths.
- Real gRPC bulk replies are bound to submitted request items; missing or mismatched items are protocol errors; reordered items are rebound by full request identity rather than permission decisions.
- The client timeout is an operation budget across token resolution, coalescing waits,
  retries and RPCs. Per-type timeouts may tighten it; mixed operations use the tightest
  participating timeout. Upstream gRPC deadlines/cancellation still apply. Arbitrary
  user SPI code must honor this context; a deadline cannot forcibly stop a blocking
  third-party implementation that ignores cancellation.
- Resilience state is bounded without a mandatory Caffeine runtime dependency. Active
  or open breakers are not evicted to create new type capacity. Retry backoff honors
  its configured maximum. Local validation/protocol failures are not retried.

### Best-effort SESSION versus coordinated SESSION

`ReadConsistency.session()` remains compatible best-effort, per-resource-type
last-observed token tracking. Its `DistributedTokenStore` is a cache SPI: miss or
failure may fall back to local state or minimize-latency reads. The suggested 60-second
TTL belongs to that cache, not to the ZedToken itself. SESSION does not infer graph
dependencies or guarantee monotonic concurrent token publication.

For required coordination, opt into `ReadConsistency.coordinatedSession()` and
provide a **CoordinatedTokenStore**, not just the old best-effort store:

```java
AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target(endpoint).presharedKey(key).tls(true))
    .extend(e -> e
        .policies(PolicyRegistry.builder()
            .defaultPolicy(ResourcePolicy.builder()
                .readConsistency(ReadConsistency.coordinatedSession())
                .build())
            .build())
        .components(SdkComponents.builder()
            .coordinatedTokenStore(coordinator)
            .build()))
    .build();
```

The coordinator's namespace must include every resource type affecting the protected
checks. It must serialize the **actual graph mutations** across clients/processes,
mark writes pending before dispatch, publish the real returned token before success,
and make required reads unavailable after uncertain writes, lease loss, missing state,
or storage failure. It must not return an older token after cache expiry. This design
never compares opaque token strings. All SDK relationship write/delete entry points pass through
the coordinator, even when reads use other configured resource policies. A coordinated read may not
override its required bound with `full()` or an unrelated token; use a separate
non-coordinated policy for intentional historical/head reads. Outcomes without an
actual revision token cannot be accepted as coordinated decisions.

The SDK does not ship a Redis implementation. A Redis get/set adapter does not meet
this contract. Implementations must supply linearizable coordination, fencing and
recovery; the backing SpiceDB datastore must preserve the required causal write order.
Bootstrap/reconciliation of historical or externally written relationships is an
application responsibility. Required reads fail closed until a safe token is published.
Coordinating a broad namespace can serialize writes, so keep bulk writes and measure
write throughput before production adoption. This API alone is not proof that global
revocation works in a deployed multi-instance service.

Time-based `boundedStaleness` has no implemented SpiceDB mapping and now fails
explicitly rather than silently returning an unbounded cache snapshot.
