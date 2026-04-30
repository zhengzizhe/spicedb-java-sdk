# AuthX SpiceDB SDK Reference

This file is bundled in the SDK JAR at `META-INF/authx-sdk/GUIDE.md`.

## Installation

```groovy
dependencies {
    implementation("io.github.authxkit:authx-spicedb-sdk:3.0.1")
}
```

Java 21 or newer is required.

## Client Setup

```java
AuthxClient client = AuthxClient.builder()
    .connection(c -> c
        .target("dns:///spicedb.prod:50051")
        .presharedKey("my-key")
        .tls(true))
    .build();
```

For unit tests:

```java
AuthxClient client = AuthxClient.inMemory();
```

## Business API

Use `client.on(...).select(...)` for resource-bound operations.

```java
client.on(Document)
    .select("doc-1")
    .grant(Document.Rel.EDITOR)
    .to(User, "alice")
    .commit();

CompletableFuture<WriteCompletion> future = client.on(Document)
    .select("doc-1")
    .grant(Document.Rel.VIEWER)
    .to(User, "bob")
    .listener(done -> auditLog.write(done.count(), done.zedToken()))
    .commit();

boolean allowed = client.on(Document)
    .select("doc-1")
    .check(Document.Perm.VIEW)
    .by(User, "alice");

CheckResult detailed = client.on(Document)
    .select("doc-1")
    .check(Document.Perm.VIEW)
    .detailedBy(User, "alice");
```

Dynamic string-driven code uses the same shape:

```java
client.on("document")
    .select("doc-1")
    .grant("editor")
    .to("user:alice")
    .commit();

boolean allowed = client.on("document")
    .select("doc-1")
    .check("view")
    .by("user:alice");
```

Writes return `WriteFlow` and must end in `commit()`. `listener(...)` is the
last intermediate operation registered before `commit()`. It switches the
chain to `WriteListenerStage`, whose terminal `commit()` returns
`CompletableFuture<WriteCompletion>` and completes after the asynchronous
listener finishes. After `listener(...)`, commit through the returned stage.

`WriteCompletion.updateCount()` is the number of submitted relationship updates
in the fluent flow. Lower-level `WriteResult.submittedUpdateCount()` has the
same meaning. Operations such as transport-level `deleteByFilter` may succeed
with `submittedUpdateCountKnown() == false` because SpiceDB returns a revision
token but not a count.

`CheckResult` exposes only values the SDK can source from SpiceDB:
`permissionship` and `zedToken`.

## Batch Writes

```java
WriteCompletion completion = client.batch()
    .on(Document, "doc-1")
        .revoke(Document.Rel.OWNER).from(User, "alice")
        .grant(Document.Rel.OWNER).to(User, "bob")
    .on(Task, "task-1")
        .grant(Task.Rel.REVIEWER).to(User, "alice")
    .commit();
```

## Batch Checks

```java
CheckMatrix matrix = client.batchCheck()
    .add(Document, "doc-1", Document.Perm.VIEW, User, "alice")
    .add(Task, "task-1", Task.Perm.EDIT, User, "alice")
    .fetch();
```

## Lookup

Subject to resources:

```java
List<String> docs = client.on(Document)
    .lookupResources(User, "alice")
    .limit(100)
    .can(Document.Perm.VIEW);
```

Resource to subjects:

```java
List<String> users = client.on(Document)
    .select("doc-1")
    .lookupSubjects(User, Document.Perm.VIEW)
    .limit(100)
    .fetchIds();
```

## Relations And Expand

```java
List<Tuple> tuples = client.on(Document)
    .select("doc-1")
    .relations(Document.Rel.EDITOR)
    .fetch();

ExpandTree tree = client.on(Document)
    .select("doc-1")
    .expand(Document.Perm.VIEW);
```

## Input Validation

Fluent calls that require at least one item fail fast on empty input, including
`select(...)`, `check(...)`, `grant(...)`, `revoke(...)`, `to(...)`, `from(...)`,
batch `onAll(...)`, and batch fetch/commit terminals.

Subjects are canonical strings in dynamic code: `type:id`, `type:id#relation`,
or `type:*`. Prefer typed overloads such as `.to(User, "alice")` in generated
schema code.

## Schema Management

Use `client.schema()` for live schema operations and cached schema metadata:

```java
SchemaReadResult current = client.schema().readRaw();
SchemaDiffResult diff = client.schema().diffRaw(candidateSchema);
SchemaWriteResult written = client.schema().writeRaw(candidateSchema);

boolean refreshed = client.schema().refresh();
Set<String> resourceTypes = client.schema().resourceTypes();
```

`readRaw()`, `writeRaw(...)`, `diffRaw(...)`, and `refresh()` require a live
SpiceDB-backed client. `AuthxClient.inMemory()` fails fast for these operations.
Blank schema inputs fail locally, while SpiceDB parse/validation/transport
failures are mapped through the SDK exception hierarchy.

## Caveats

Generated caveat classes expose fluent builders:

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

Use `ref()` for grant-time caveat binding and `context()` / `given(...)` for
check-time evaluation. Generated setter names come from SpiceDB caveat
parameter names; business conditions stay in the schema's CEL expression. The
builders fail fast on unknown parameters, incompatible Java value types, and,
via `completeRef()` / `completeContext()`, missing parameters. Raw
`Map<String, Object>` caveat context remains supported.

## Watch / Change Streams

The SDK does not currently expose a stable Watch/change-stream API. SpiceDB
Watch requires persisted resume tokens, reconnect handling, idempotent event
handlers, replay tolerance, and rebuild logic when a stored token is too old.
Do not use Watch as an SDK-side permission decision cache invalidation
mechanism.

## Lifecycle

`AuthxClient` implements `AutoCloseable`.

```java
try (AuthxClient client = AuthxClient.builder()
        .connection(c -> c.target("localhost:50051").presharedKey("key"))
        .build()) {
    boolean allowed = client.on("document")
        .select("doc-1")
        .check("view")
        .by("user:alice");
}
```
