# Migration Notes

This file documents breaking SDK API changes from the legacy fluent/action
surface to the current public business API.

## Use `client.on(...)`

Old action classes and resource handles have been removed. Start business
operations from `AuthxClient.on(...)`.

```java
client.on(Document)
    .select("doc-1")
    .grant(Document.Rel.EDITOR)
    .to(User, "alice")
    .commit();

boolean allowed = client.on("document")
    .select("doc-1")
    .check("view")
    .by("user:alice");
```

Removed public types include `PermissionResource`, `ResourceHandle`,
`LookupQuery`, and the legacy `com.authx.sdk.action` package.

## Commit Is The Write Terminal

`commitAsync()` has been removed. `commit()` is the write terminal. If callers
need to run a write on another thread, schedule that at the application layer.

```java
WriteCompletion completion = client.on("document")
    .select("doc-1")
    .grant("viewer")
    .to("user:alice")
    .commit();
```

## Register Listeners Before Commit

`WriteCompletion` is a completed result object and no longer accepts listener
registration. Register listeners on `WriteFlow` before the terminal commit.

```java
CompletableFuture<WriteCompletion> future = client.on("document")
    .select("doc-1")
    .grant("viewer")
    .to("user:alice")
    .listener(done -> audit(done.updateCount(), done.zedToken()))
    .commit();
```

`listener(...)` returns `WriteListenerStage`, and that stage's `commit()` returns
`CompletableFuture<WriteCompletion>`.

## Use Unified Write Results

Operation-specific result records have been removed:

- `GrantResult`
- `RevokeResult`
- `BatchResult`

Use `WriteCompletion` from fluent writes and `WriteResult` at the lower
transport layer.

```java
WriteCompletion completion = flow.commit();
String zedToken = completion.zedToken();
int submittedUpdates = completion.updateCount();
```

`WriteResult.submittedUpdateCount()` is a submitted-update count, not a net
database mutation count. `deleteByFilter` cannot know a submitted update count
from SpiceDB, so it returns `WriteResult.unknownSubmittedUpdateCount(token)`.
Check `submittedUpdateCountKnown()` before treating the count as known.

## `CheckResult` No Longer Has `expiresAt`

`CheckResult.expiresAt()` was removed because the SDK did not have a real gRPC
source for that value. Use `permissionship()`, `hasPermission()`,
`isConditional()`, and `zedToken()` only.

## Bulk Check Keys Are Canonical Subject References

`BulkCheckResult` maps are keyed by canonical subject reference, not bare
subject id. Use keys like `user:alice` or `group:eng#member`.

```java
boolean allowed = bulk.get("user:alice").hasPermission();
```

This avoids collisions between different subject types that share the same id.

## New Schema And Caveat APIs

Post-3.0 source builds add live schema management through `client.schema()`:
`readRaw()`, `writeRaw(...)`, `diffRaw(...)`, and `refresh()`. These methods
require a live SpiceDB-backed client and fail fast on in-memory clients.

Generated schema code can also expose typed caveat builders. Generated setter
names come from SpiceDB caveat parameter names; the SDK does not define
business policy conditions locally. Use `ref()` for grant-time caveat binding,
`context()` / `given(...)` for check-time values, and
`completeRef()` / `completeContext()` when all schema parameters must be
present in one object. Existing `Map<String, Object>` caveat usage remains
supported.

## Watch Is Not Reintroduced

The SDK does not expose a stable Watch/change-stream API. Watch requires
persisted resume tokens, idempotent handlers, reconnect/replay handling, and
rebuild behavior when a token falls outside the SpiceDB datastore history
window. It is not used as an SDK-side L1 cache invalidation mechanism.

## Tests

The old in-memory-heavy test suite has been removed. The main end-to-end suite
runs against real SpiceDB:

```bash
./gradlew clean test spicedbE2eTest
```

Small contract tests remain appropriate for pure model/API semantics where no
SpiceDB behavior is involved.
