# SDK Review — Critical Fixes (2026-04-16)

## Background

A four-agent parallel code review of the AuthX SDK identified 10 Critical-severity findings spanning the transport chain, cache/Watch subsystem, public API builders, and observability stack. This spec formalizes those findings as testable requirements for a single coordinated fix round.

Follow-up rounds will address the High (≈8) and Medium (≈10) findings.

## Non-goals

- Performance refactoring beyond what's needed to fix correctness.
- API breaking changes to the public surface (new methods and new builder options are fine; existing signatures must remain compatible).
- OTel SDK / Micrometer deeper integration (documentation only, no new runtime dependency).

## Requirements

Each requirement has a unique ID (`SR:C#`), a one-line acceptance criterion, and a link to the source finding.

---

### SR:C1 — gRPC deadline propagates via Context

**Problem.** `GrpcTransport.stub().withDeadlineAfter(deadlineMs, ...)` only sets the per-call gRPC deadline. It does not attach to `io.grpc.Context.current()`. When an upstream HTTP handler thread is cancelled, the gRPC call keeps running to its own deadline — leaking worker threads and violating SLA budgets.

**Acceptance.**
- `GrpcTransport` computes `effectiveDeadline = min(Context.current().getDeadline(), now + policyTimeout)` and attaches via a `CancellableContext` that wraps each call.
- `CloseableGrpcIterator.from(...)` preserves the same context across lazy iteration (not detached before first `hasNext()`).
- New unit test: upstream `Context` cancellation causes in-flight gRPC call to observe `CANCELLED` within 50 ms.
- New unit test: when upstream deadline is tighter than policy timeout, gRPC call fails with `DEADLINE_EXCEEDED` at upstream deadline, not policy timeout.

---

### SR:C2 — Cache invalidation precedes listener dispatch

**Problem.** `WatchCacheInvalidator.processResponse` today dispatches listeners before invalidating the cache. A concurrent `check()` call can observe the new world via a listener while the cache still returns the old decision.

**Acceptance.**
- `processResponse` invalidates the cache entry/entries first, then enqueues listener dispatch.
- A `happens-before` edge from "cache entry removed" to "listener invoked" is documented in Javadoc.
- New concurrency test: 1000 concurrent `check()` + `grant()` pairs with Watch enabled never observes a post-grant listener event while the cache still returns the pre-grant decision.

---

### SR:C3 — Coalescing never reuses a failed future

**Problem.** In `CoalescingTransport`, when the leader fails, `myFuture.completeExceptionally(e)` publishes the failure to followers, but `inflight.remove(key, myFuture)` can race with a new request arriving in the same microsecond — the newcomer finds the failed future via `putIfAbsent` and is served a stale, unrelated exception.

**Acceptance.**
- Failed futures are removed from `inflight` **before** `completeExceptionally` is called, or wrapped so followers arriving after the failure get `null` from `putIfAbsent` and start their own call.
- New unit test: leader fails with exception X; a follower arriving in the narrow window between failure and removal either (a) sees X (if it joined before), or (b) starts a fresh call (if it joined after). Never sees X for a call it did not actually join.

---

### SR:C4 — Retry respects non-retryable exception classes

**Problem.** `ResilientTransport` composes `Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(cb, ...))`. The CB's `ignoreExceptions` list (`AuthxAuthException`, `AuthxSchemaException`) is consulted per inner call, so retries can still consume budget against permanent errors. Users with short retry budgets observe surprising latency spikes when misconfigured.

**Acceptance.**
- `RetryPolicy.shouldRetry(Throwable)` explicitly returns `false` for `AuthxAuthException`, `AuthxSchemaException`, `AuthxConstraintViolationException`, and any other permanent `AuthxException` subclass.
- `ResilientTransport` wires this predicate into the Resilience4j `RetryConfig.retryOnException(...)`.
- New unit test: `UNAUTHENTICATED` is thrown exactly once (no retries) regardless of retry budget.
- New unit test: `UNAVAILABLE` is retried up to max attempts.

---

### SR:C5 — Watch listener drops are observable and (optionally) apply backpressure

**Problem.** When the bounded listener queue fills, `WatchCacheInvalidator` silently increments `droppedListenerEvents`. Downstream audit sinks lose events with no in-process signal. There is no mechanism to slow the upstream Watch stream.

**Acceptance.**
- New SPI: `SdkComponents.Builder#watchListenerDropHandler(Consumer<DroppedListenerEvent>)`. Default is noop + counter.
- `DroppedListenerEvent` record carries `{zedToken, resourceType, resourceId, queueDepth, timestamp}`.
- New configuration option: `CacheConfig#listenerQueueOnFull(QueueFullPolicy)` with values `DROP` (default, matches today), `BLOCK_WITH_BACKPRESSURE` (blocks the gRPC callback thread, applying implicit gRPC flow-control).
- New unit test: with `DROP` policy + a drop handler, a saturation burst invokes the handler for every dropped event.
- New unit test: with `BLOCK_WITH_BACKPRESSURE`, consumer pressure stalls `ClientCall.request(1)` and no event is dropped.

---

### SR:C6 — Builder rejects mutually exclusive target / targets

**Problem.** `ConnectionConfig` exposes both `target(String)` and `targets(String...)`. The builder validates "at least one is set" but allows both; `buildChannel()` silently picks one.

**Acceptance.**
- `AuthxClientBuilder#build()` throws `IllegalArgumentException` with message `"target and targets are mutually exclusive — pick one"` when both are set.
- New unit test.

---

### SR:C7 — watchInvalidation(true) requires cache enabled

**Problem.** `CacheConfig#watchInvalidation(true)` without `cache.enabled(true)` silently builds a client where Watch is a no-op.

**Acceptance.**
- `AuthxClientBuilder#build()` throws `IllegalArgumentException` with message `"cache.watchInvalidation(true) requires cache.enabled(true)"`.
- Registering any Watch strategy (`extend.watchStrategy(...)`) without cache+watchInvalidation throws the same way.
- New unit test per case.

---

### SR:C8 — Interceptor chain isolates listener exceptions

**Problem.** `RealCheckChain`, `RealOperationChain`, `RealWriteChain` propagate interceptor exceptions immediately, aborting the chain. This skips `InstrumentedTransport`'s `finally`-block telemetry emission and can mask the original failure.

**Acceptance.**
- Each chain class wraps `interceptors.get(index).interceptXxx(next)` in try-catch; user-thrown interceptor exceptions are logged at WARN with interceptor class name and:
  - For **read** paths (check/lookup/expand): the chain continues to the next interceptor with the prior result.
  - For **write** paths: the chain aborts (writes must not silently proceed if an interceptor fails).
- gRPC-originated exceptions (identified by subclass of `AuthxException`) always propagate unchanged.
- New unit test per chain class.

---

### SR:C9 — HdrHistogram overflow is counted, not hidden

**Problem.** `SdkMetrics.recordRequest` clamps values to `MAX_TRACKABLE_MICROS` (60 s). Requests longer than that are indistinguishable from 60-second requests in the percentile output, and there is no overflow counter.

**Acceptance.**
- `SdkMetrics` exposes `latencyOverflowCount()` (a `LongAdder`) incremented on clamp.
- `MAX_TRACKABLE_MICROS` is raised to **600 seconds** (10 minutes); values above still clamp and count.
- `SdkMetrics.Snapshot` exposes `latencyOverflowCount()`.
- `Snapshot.toString()` prints `overflow=N` when non-zero.
- New unit test: 3 records at 1 ms, 1 record at 10 minutes + 1 µs → overflow counter = 1, percentiles unaffected by the overflow sample.

---

### SR:C10 — TelemetryReporter.flush() is bounded by sink timeout

**Problem.** `TelemetryReporter.flush()` calls `sink.send(batch)` synchronously. A hung sink blocks the scheduler thread indefinitely, also blocking `close()`.

**Acceptance.**
- `TelemetryReporter` wraps `sink.send(batch)` in `CompletableFuture.supplyAsync(...).orTimeout(sinkTimeout, TimeUnit.MILLISECONDS)` on a dedicated sink-executor.
- Default `sinkTimeout = 5 s`; configurable via `TelemetryReporter.Config` or a new `features.telemetrySinkTimeout(Duration)` builder option.
- On timeout, a warning is logged at most once per minute; batch is retained for retry up to `maxRetries` (default 2).
- `close()` returns within `sinkTimeout + 1 s` even with a hung sink.
- New unit test: a sink that blocks on a latch never held; close returns within 6 s; timeout logged.

## Out of Scope (follow-up plans)

- SR:H* findings (High severity): `HealthProbe` clock injection, Composite probe parallelism, EventBus error surface, Lifecycle transition validation, TokenTracker multi-type correctness, RetryBudget atomicity, gRPC channel ownership, Cursor backoff config, OTel no-op startup log.
- SR:M* findings: `Duration` adoption across transport, `IndexedCache` race tightening, `NoopCache` implementing `IndexedCache`, `SdkClock.Fixed` atomicity, `CheckMatrix` typed key, etc.
- Java 21 idiom refactors (sealed `CheckResult`, Result/Either types, virtual threads as default listener executor).

## Verification Strategy

- All SR:C# requirements gated by new unit tests under `src/test/java/com/authx/sdk/...`.
- After all SRs green, run `./gradlew test` (full unit suite must pass).
- Run `./gradlew :cluster-test:test` (integration suite) for SR:C1, C2, C5 to exercise real gRPC + Watch flow.
- No public API break: `./gradlew compileJava` with downstream `test-app/` unchanged must succeed.
