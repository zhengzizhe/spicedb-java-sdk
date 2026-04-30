# AuthX SpiceDB SDK

Java 21 SDK for SpiceDB. The SDK connects directly to SpiceDB over gRPC and
provides fluent business APIs for permission checks, relationship writes,
lookups, batch checks, resilience, and observability.

## Installation

```groovy
dependencies {
    implementation("io.github.authxkit:authx-spicedb-sdk:3.0.1")
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
