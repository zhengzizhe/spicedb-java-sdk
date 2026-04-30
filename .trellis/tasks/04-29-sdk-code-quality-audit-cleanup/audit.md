# SDK code quality audit inventory

## Fixed

* Root fluent API helpers:
  * `ResourceLookupSupport.canAny/canAll` now applies `limit` after
    union/intersection instead of limiting each permission lookup first.
  * `SdkRefs.requireNotEmpty` now rejects null elements, not only empty
    containers.
  * `SdkRefs.checkedProduct` centralizes matrix/cell overflow checks.
* Lookup and relation queries:
  * `TypedWhoQuery.exists()` no longer mutates query state to force
    `limit=1`.
  * `TypedWhoQuery.count()` documentation no longer claims a non-existent
    count-only RPC.
  * `RelationQuery` copies relation arrays, rejects null consistency, and
    avoids fetching every relation when `fetchExists()` / `fetchFirst()` can
    stop early.
* Batch and matrix checks:
  * `BatchCheckBuilder.fetch()` preserves request shape when a transport
    returns fewer results and ignores extra results instead of risking
    index errors.
  * `TypedCheckAction.byAll(...)` and `TypedCheckAllAction.byAll(...)` guard
    cartesian-product capacity before allocating arrays/lists.
  * `PolicyAwareConsistencyTransport.checkBulkMulti(...)` fills missing or
    null delegate results as denied instead of leaking nulls.
  * `GrpcTransport` bulk result mapping now preserves request shape for both
    subject-bulk and arbitrary-cell bulk checks.
* Token tracking:
  * Removed the no-op `recordRead(...)` path and the misleading test that only
    asserted it did nothing.
  * Removed unused global/back-compat token overloads from `TokenTracker`.
  * Empty write batches no longer call `recordWrite(null, token)`.
* Builder and cleanup:
  * `AuthxClientBuilder.ConnectionConfig.targets(...)` now fails fast on empty
    input.
  * Builder config setters now reject null target, key, and duration values at
    the configuration edge.
  * Build-error cleanup now logs telemetry reporter close failures instead of
    silently swallowing them.
* Legacy cleanup:
  * Removed empty `action` directories left after deleting the old action
    builders.
  * Removed stale “legacy handle” wording from `CrossResourceBatchBuilder`.

## Intentionally Left Alone

* `HealthResult` and `HealthProbe.ProbeResult.latencyMs()` still mention
  compatibility because they are current public health API compatibility
  shims, not dead business API.
* `GrpcTransport.lookupSubjects(...)` keeps the compatibility comment around
  not setting `optionalConcreteLimit`; that is an active server-version
  compatibility decision.
* `System.out` remains in e2e/demo tests and log-format demo tests only, which
  the backend quality spec permits.
* `TraceContext.traceId()` / `traceparent()` return null for absent active
  spans by design; callers already treat this as optional trace context.

## Deferred Candidates

* `AuthxClientBuilder` is still large and could be split into a dedicated
  channel factory, transport-stack factory, and scheduler/telemetry factory.
  This is architectural cleanup rather than a safe local patch.
* `CrossResourceBatchBuilder` still has a broad nested fluent-scope surface.
  The current helper extraction removed the worst fan-out duplication, but a
  deeper stage-model redesign should be a separate API-design task.
* `StaticNameResolver` still assumes simple `host:port` endpoint strings for
  multi-target mode. Supporting URI targets and IPv6 cleanly should be handled
  with explicit connection-string tests.
