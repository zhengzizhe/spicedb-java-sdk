# SDK Review — Critical Fixes Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 10 Critical-severity findings from the 2026-04-16 SDK code review, in a single coordinated round, without breaking the public API.

**Architecture:** Test-first for every fix. Group fixes by subsystem to minimize merge conflicts: Cache → Transport → Observability → Builder. Each task is scoped to one file (or one file + its test) and ends in a commit. Parallelizable tasks are marked `[P]`.

**Tech Stack:** Java 21, Gradle, gRPC 1.80 (Netty-shaded), Resilience4j 2.4, Caffeine 3.1 (optional), HdrHistogram 2.2, JUnit 5 + AssertJ + Mockito.

---

## File Structure (what gets touched)

| Path | Role in this plan |
|---|---|
| `src/main/java/com/authx/sdk/transport/GrpcTransport.java` | SR:C1 — attach `Context.CancellableContext` |
| `src/main/java/com/authx/sdk/transport/CloseableGrpcIterator.java` | SR:C1 — preserve context across iteration |
| `src/main/java/com/authx/sdk/transport/CoalescingTransport.java` | SR:C3 — safe leader-failure eviction |
| `src/main/java/com/authx/sdk/transport/ResilientTransport.java` | SR:C4 — wire non-retryable predicate |
| `src/main/java/com/authx/sdk/transport/WatchCacheInvalidator.java` | SR:C2, SR:C5 — dispatch order, drop handler |
| `src/main/java/com/authx/sdk/transport/RealCheckChain.java` | SR:C8 — isolate read-path interceptor errors |
| `src/main/java/com/authx/sdk/transport/RealOperationChain.java` | SR:C8 — isolate read-path interceptor errors |
| `src/main/java/com/authx/sdk/transport/RealWriteChain.java` | SR:C8 — abort on write-path interceptor errors |
| `src/main/java/com/authx/sdk/policy/RetryPolicy.java` | SR:C4 — non-retryable predicate |
| `src/main/java/com/authx/sdk/metrics/SdkMetrics.java` | SR:C9 — overflow counter |
| `src/main/java/com/authx/sdk/telemetry/TelemetryReporter.java` | SR:C10 — bounded sink timeout |
| `src/main/java/com/authx/sdk/spi/SdkComponents.java` | SR:C5 — add `watchListenerDropHandler` field + builder |
| `src/main/java/com/authx/sdk/spi/DroppedListenerEvent.java` | SR:C5 — **new** record |
| `src/main/java/com/authx/sdk/cache/QueueFullPolicy.java` | SR:C5 — **new** enum |
| `src/main/java/com/authx/sdk/AuthxClientBuilder.java` | SR:C5, SR:C6, SR:C7 — build-time validations + listener options |
| `src/test/java/com/authx/sdk/transport/GrpcTransportContextTest.java` | **new** test |
| `src/test/java/com/authx/sdk/transport/CoalescingTransportFailureEvictionTest.java` | **new** test |
| `src/test/java/com/authx/sdk/transport/ResilientTransportNonRetryableTest.java` | **new** test |
| `src/test/java/com/authx/sdk/transport/WatchCacheInvalidatorOrderingTest.java` | **new** test |
| `src/test/java/com/authx/sdk/transport/WatchCacheInvalidatorBackpressureTest.java` | **new** test |
| `src/test/java/com/authx/sdk/transport/RealCheckChainIsolationTest.java` | **new** test |
| `src/test/java/com/authx/sdk/metrics/SdkMetricsOverflowTest.java` | **new** test |
| `src/test/java/com/authx/sdk/telemetry/TelemetryReporterSinkTimeoutTest.java` | **new** test |
| `src/test/java/com/authx/sdk/AuthxClientBuilderValidationTest.java` | **new** test (or extend existing) |

---

## Design Decisions

### SR:C1 — Context vs CallOptions.Deadline
Use `Context.CancellableContext` because it propagates to **all** nested gRPC calls (including Watch reconnects), not just the outer call. The `CallOptions.withDeadline` is redundant but keep it as belt-and-suspenders. The `CloseableGrpcIterator` must store the context and reattach on each `hasNext()`/`next()` call; use `try-with-resources` over the `Context.attach()/detach()` pair inside each iteration step.

### SR:C2 — Ordering guarantee
Cache is invalidated synchronously in the gRPC callback thread **before** `listenerExecutor.execute(...)` is invoked. Because the executor is an async boundary, any `check()` starting on any other thread after the dispatch must have already passed the cache-invalidation memory barrier. Document this explicitly with a `// Ordering: invalidate → dispatch` comment block.

### SR:C3 — Failure eviction pattern
Change the pattern from:
```
try { result = delegate.call(); future.complete(result); }
catch (e) { future.completeExceptionally(e); throw e; }
finally { inflight.remove(key, future); }
```
To:
```
try { result = delegate.call(); future.complete(result); return result; }
catch (e) {
    inflight.remove(key, future);    // remove BEFORE publishing failure
    future.completeExceptionally(e);
    throw e;
}
```
Success path keeps the `finally` semantics (value is cacheable for the tiny success window); failure path removes before publishing so any new joiner gets `null` from `putIfAbsent` and starts its own call.

### SR:C4 — RetryPolicy.shouldRetry contract
`RetryPolicy` already has a list of retryable gRPC status codes. Add a companion list of permanent exception classes. `ResilientTransport` uses `Retry.Builder.retryOnException(predicate)` where `predicate = ex -> !policy.isPermanent(ex)`. The CB's `ignoreExceptions` remains unchanged for CB-failure-rate computation, but retries no longer escalate on permanent errors.

### SR:C5 — Two-lever design
- **Drop handler** (`SdkComponents.watchListenerDropHandler`): observability only. Always fired, regardless of policy.
- **Queue full policy** (`CacheConfig.listenerQueueOnFull`): behavior. `DROP` (default, backwards-compatible) vs `BLOCK_WITH_BACKPRESSURE` (calls `put()` instead of `offer()`; the gRPC callback thread blocks, which pauses `ClientCall.request(1)` and thus slows the stream).

Document the trade-off: `BLOCK_WITH_BACKPRESSURE` avoids data loss but can starve the gRPC executor pool; users running listeners with external I/O should either (a) use their own fast fan-out executor, or (b) accept drops.

### SR:C8 — Read vs write asymmetry
Read paths (check/lookup/expand/read) can tolerate skipping a broken interceptor — worst case is lost telemetry. Write paths cannot — an interceptor that aborts is likely doing policy enforcement, so the write must fail closed. The asymmetry is deliberate and must be documented.

### SR:C9 — Overflow without breaking percentile semantics
HdrHistogram's `recordValue` only clamps the recorded value, not the count. The bigger issue is that clamping distorts the high tail. Raising `MAX_TRACKABLE_MICROS` to 600 s keeps HdrHistogram's memory footprint modest (~30 KB, from ~20 KB at 60 s) and covers essentially all realistic request timeouts. Overflow beyond 10 minutes is logged + counted; such requests indicate an SDK bug or stuck thread, not a latency concern.

### SR:C10 — Async timeout, not thread interrupt
`CompletableFuture.supplyAsync(...).orTimeout(...)` does not interrupt the sink's blocking call — that would risk corrupting user state. Instead, the scheduler moves on; the orphaned sink call may eventually complete (and its result is discarded). Users who need hard cancellation must implement their own interruptible sink. Document this.

---

## Per-Task Details

> Each task lists: files, code-level approach (not full code), and test strategy. Actual implementation is the executing-plans session's job.

### T001 — Branch + scaffolding

**Files:** none (git-only).

**Steps:**
1. Verify we're on `claude/interesting-cohen` (worktree branch).
2. `./gradlew compileJava test -x :test-app:test -x :cluster-test:test --offline` — baseline must be green.
3. Commit: `chore: begin critical fixes (baseline green)`.

### T002 [SR:C2] — Test: ordering invariant (cache-before-dispatch)

**Files:** new `src/test/java/com/authx/sdk/transport/WatchCacheInvalidatorOrderingTest.java`.

**Steps:**
1. Write a test harness with: a Caffeine-backed cache, a mock listener that captures `(timestamp, key)` for each event, and a mock that snapshots the cache state at listener entry.
2. Drive `WatchCacheInvalidator.processResponse(...)` with a crafted Watch response mutating key `K`.
3. Assert: at the moment the listener is invoked, `cache.get(K)` returns empty (already invalidated).
4. Run — expect FAIL under current code.
5. Commit test only: `test(watch): invariant — cache invalidated before listener dispatch`.

### T003 [SR:C2] — Impl: swap invalidate/dispatch order

**Files:** `WatchCacheInvalidator.java` (the `processResponse` / `onMessage` path near lines 610-624 in current code).

**Steps:**
1. Move the `invalidateByIndex(...)` / `invalidate(key)` call to **before** `listenerExecutor.execute(...)`.
2. Add a `// Ordering: cache invalidation happens-before listener dispatch` comment block.
3. Run T002 — expect PASS.
4. Run full watch test suite — expect no regressions.
5. Commit: `fix(watch): invalidate cache before dispatching listener (SR:C2)`.

### T004 [SR:C5] [P] — New types: `DroppedListenerEvent`, `QueueFullPolicy`

**Files:**
- New `src/main/java/com/authx/sdk/spi/DroppedListenerEvent.java` — record with fields `{String zedToken, String resourceType, String resourceId, int queueDepth, Instant timestamp}`.
- New `src/main/java/com/authx/sdk/cache/QueueFullPolicy.java` — enum `{DROP, BLOCK_WITH_BACKPRESSURE}`.

**Steps:**
1. Define both types with Javadoc per spec SR:C5 acceptance.
2. `./gradlew compileJava` — must pass.
3. Commit: `feat(spi): add DroppedListenerEvent + QueueFullPolicy types (SR:C5)`.

### T005 [SR:C5] — Extend `SdkComponents` with drop handler

**Files:** `SdkComponents.java`.

**Steps:**
1. Add `@Nullable Consumer<DroppedListenerEvent> watchListenerDropHandler` field to the record.
2. Add builder method `watchListenerDropHandler(Consumer<DroppedListenerEvent>)`.
3. Update `defaults()` to pass `null` for the new field.
4. `./gradlew compileJava` — must pass.
5. Commit: `feat(spi): add watchListenerDropHandler hook (SR:C5)`.

### T006 [SR:C5] — Extend `CacheConfig` with queue-full policy

**Files:** `AuthxClientBuilder.java` (the `CacheConfig` inner class around line 170-190).

**Steps:**
1. Add private field `QueueFullPolicy listenerQueueOnFull = QueueFullPolicy.DROP;` on the outer builder.
2. Add `CacheConfig#listenerQueueOnFull(QueueFullPolicy)` method.
3. `./gradlew compileJava` — must pass.
4. Commit: `feat(builder): expose listenerQueueOnFull (SR:C5)`.

### T007 [SR:C5] — Test: drop handler fires on saturation

**Files:** new `src/test/java/com/authx/sdk/transport/WatchCacheInvalidatorBackpressureTest.java`.

**Steps:**
1. Build an invalidator with: drop handler (capturing list), listener executor with capacity 2 and a latch-blocked listener.
2. Inject 10 synthetic Watch responses.
3. Assert: drop handler received 8 events, each with `queueDepth` ≥ 2.
4. Run — expect FAIL (handler doesn't exist yet in impl).
5. Commit test only: `test(watch): drop handler fires on queue saturation (SR:C5)`.

### T008 [SR:C5] — Test: BLOCK_WITH_BACKPRESSURE stalls producer

**Files:** same test file as T007.

**Steps:**
1. Variant with policy `BLOCK_WITH_BACKPRESSURE` and an unblocked listener after 100 ms.
2. Assert: no drops, and total dispatch time ≥ 100 ms (proves producer blocked).
3. Commit test: `test(watch): BLOCK_WITH_BACKPRESSURE applies backpressure (SR:C5)`.

### T009 [SR:C5] — Impl: wire drop handler + policy

**Files:** `WatchCacheInvalidator.java`.

**Steps:**
1. Accept `@Nullable Consumer<DroppedListenerEvent> dropHandler` and `QueueFullPolicy policy` in constructor (thread through `AuthxClientBuilder` wiring).
2. On queue full under `DROP`: build `DroppedListenerEvent`, increment `droppedListenerEvents`, invoke `dropHandler` (guard with try/catch so handler exception doesn't kill Watch thread).
3. Under `BLOCK_WITH_BACKPRESSURE`: replace `executor.execute(r)` with a bounded-queue `put(r)` equivalent; if the executor is user-supplied, document that this policy requires a bounded `BlockingQueue`.
4. Run T007, T008 — expect PASS.
5. Commit: `fix(watch): observable drops + optional backpressure (SR:C5)`.

### T010 [SR:C8] — Test: interceptor exception isolation (check chain)

**Files:** new `src/test/java/com/authx/sdk/transport/RealCheckChainIsolationTest.java`.

**Steps:**
1. Build a chain with three interceptors: A (records call), B (throws `RuntimeException`), C (records call).
2. Execute `interceptCheck(terminal)` with a mock terminal returning a fixed result.
3. Assert: A and C both ran; terminal ran; result is returned; B's exception is logged but not thrown.
4. Run — expect FAIL.
5. Commit test: `test(transport): interceptor exceptions isolated on check chain (SR:C8)`.

### T011 [SR:C8] — Impl: try/catch per interceptor on read chains

**Files:** `RealCheckChain.java`, `RealOperationChain.java` (read ops only).

**Steps:**
1. Wrap `interceptors.get(index).interceptXxx(this)` in try/catch; on non-`AuthxException`, log at WARN including interceptor class name, and continue with `proceed()` passing the prior result.
2. Let `AuthxException` subclasses propagate unchanged.
3. Run T010 — expect PASS.
4. Commit: `fix(transport): isolate interceptor failures on read chains (SR:C8)`.

### T012 [SR:C8] — Test: write chain aborts on interceptor exception

**Files:** same as T010 (new test method).

**Steps:**
1. Build write chain with interceptors A, B(throws), C.
2. Execute `interceptWrite(...)` with a mock terminal.
3. Assert: B's exception propagates as `RuntimeException` (or wrapped `AuthxWriteException`); C does NOT run; terminal does NOT run.
4. Commit test: `test(transport): write chain aborts on interceptor exception (SR:C8)`.

### T013 [SR:C8] — Impl: write chain aborts with wrapping

**Files:** `RealWriteChain.java`.

**Steps:**
1. Wrap each interceptor in try/catch; on non-`AuthxException`, wrap in `AuthxWriteException` with cause + interceptor class name and throw.
2. Run T012 — expect PASS.
3. Commit: `fix(transport): write chain fails closed on interceptor exception (SR:C8)`.

### T014 [SR:C6] [P] — Test: target + targets mutex

**Files:** `src/test/java/com/authx/sdk/AuthxClientBuilderValidationTest.java` (new or extend existing).

**Steps:**
1. Test: builder with both `target("a")` and `targets("b")` → `build()` throws `IllegalArgumentException` containing "mutually exclusive".
2. Test: `target` only → builds.
3. Test: `targets` only → builds.
4. Commit test: `test(builder): target/targets mutex (SR:C6)`.

### T015 [SR:C6] — Impl: mutex check

**Files:** `AuthxClientBuilder.java` line 217 area.

**Steps:**
1. Before `if (target == null && targets == null)`, add `if (target != null && targets != null) throw new IllegalArgumentException("target and targets are mutually exclusive — pick one");`.
2. Run T014 — expect PASS.
3. Commit: `fix(builder): reject mutually exclusive target + targets (SR:C6)`.

### T016 [SR:C7] [P] — Test: watchInvalidation requires cache enabled

**Files:** same as T014.

**Steps:**
1. Test: `cache(c -> c.watchInvalidation(true))` without `.enabled(true)` → `build()` throws.
2. Test: `extend(e -> e.watchStrategy(...))` without cache+watchInvalidation → throws.
3. Test: both enabled → builds.
4. Commit test: `test(builder): watchInvalidation requires cache (SR:C7)`.

### T017 [SR:C7] — Impl: validate watch-requires-cache

**Files:** `AuthxClientBuilder.java` `build()` method, before line 462.

**Steps:**
1. Add:
   ```
   if (watchInvalidation && !cacheEnabled) throw new IllegalArgumentException(...);
   if (!watchStrategies.isEmpty() && (!cacheEnabled || !watchInvalidation)) throw new IllegalArgumentException(...);
   ```
2. Run T016 — expect PASS.
3. Commit: `fix(builder): watchInvalidation requires cache enabled (SR:C7)`.

### T018 [SR:C9] [P] — Test: overflow counter

**Files:** new `src/test/java/com/authx/sdk/metrics/SdkMetricsOverflowTest.java`.

**Steps:**
1. Record three latencies at 1 ms and one at `MAX_TRACKABLE_MICROS + 1`.
2. Assert: `snapshot().latencyOverflowCount() == 1`.
3. Assert: percentile p99 is ~1 ms (not 60 s), proving the clamp sample contributed but didn't dominate.
4. Assert: `snapshot.toString()` contains `"overflow=1"`.
5. Run — expect FAIL.
6. Commit test: `test(metrics): latency overflow counter (SR:C9)`.

### T019 [SR:C9] — Impl: overflow counter + raise cap

**Files:** `SdkMetrics.java`.

**Steps:**
1. Add `private final LongAdder latencyOverflow = new LongAdder();`.
2. In `recordRequest`: if `latencyMicros > MAX_TRACKABLE_MICROS`, `latencyOverflow.increment()`.
3. Raise `MAX_TRACKABLE_MICROS = 600_000_000L` (10 minutes).
4. Extend `Snapshot` record / builder to expose `latencyOverflowCount()`.
5. Update `toString()` to print `overflow=N` when `> 0`.
6. Run T018 — expect PASS.
7. Commit: `fix(metrics): count + expose latency overflow (SR:C9)`.

### T020 [SR:C4] [P] — Test: non-retryable auth exception

**Files:** new `src/test/java/com/authx/sdk/transport/ResilientTransportNonRetryableTest.java`.

**Steps:**
1. Wrap an in-memory transport that throws `AuthxAuthException` on check.
2. Resilient config: retry max = 5, backoff minimal.
3. Call check, assert: exception thrown after exactly 1 attempt (verify via call counter on the transport).
4. Second test: transport throws `AuthxConnectionException` (UNAVAILABLE) → called 5 times.
5. Commit test: `test(resilient): non-retryable exceptions bypass retry (SR:C4)`.

### T021 [SR:C4] — Impl: RetryPolicy.isPermanent + wire into Resilience4j

**Files:** `RetryPolicy.java`, `ResilientTransport.java`.

**Steps:**
1. Add `public boolean isPermanent(Throwable t)` returning `true` for `AuthxAuthException`, `AuthxSchemaException`, `AuthxConstraintViolationException`, `AuthxArgumentException`.
2. In `ResilientTransport.createRetry(...)` Resilience4j config, set `.retryOnException(ex -> !policy.isPermanent(ex))`.
3. Run T020 — expect PASS.
4. Run the existing resilience tests — expect no regression.
5. Commit: `fix(resilient): skip retry for permanent exceptions (SR:C4)`.

### T022 [SR:C3] — Test: coalescing leader failure does not poison newcomers

**Files:** new `src/test/java/com/authx/sdk/transport/CoalescingTransportFailureEvictionTest.java`.

**Steps:**
1. Wrap an in-memory transport whose first call blocks on latch 1 and throws; subsequent calls succeed.
2. Launch thread A on the failing path.
3. Wait until A's future is marked `completedExceptionally` but before the `finally` would run (use a debug hook or `CompletableFuture.whenComplete`).
4. Launch thread B with the same key.
5. Assert: B either (a) sees the same exception (joined before eviction) or (b) gets the success result (joined after eviction). Never sees the exception for a call it didn't participate in.
6. Commit test: `test(coalesce): leader failure does not poison newcomers (SR:C3)`.

### T023 [SR:C3] — Impl: evict before publishing failure

**Files:** `CoalescingTransport.java` lines 76-86.

**Steps:**
1. Restructure exception path: `catch (Exception e) { inflight.remove(key, myFuture); myFuture.completeExceptionally(e); throw e; }`.
2. Keep `finally { inflight.remove(key, myFuture); }` for success path cleanup (no-op if already removed).
3. Run T022 — expect PASS.
4. Commit: `fix(coalesce): evict failed future before publishing exception (SR:C3)`.

### T024 [SR:C10] [P] — Test: sink timeout bounds flush

**Files:** new `src/test/java/com/authx/sdk/telemetry/TelemetryReporterSinkTimeoutTest.java`.

**Steps:**
1. Build a reporter with a sink that blocks on an un-released latch.
2. Call `record(...)` then `flush()`.
3. Assert: `flush()` returns within `sinkTimeout + 1 s`.
4. Assert: one warning log containing "sink timeout" was emitted.
5. Call `close()`; assert it returns within `sinkTimeout + 1 s` too.
6. Commit test: `test(telemetry): hung sink does not block reporter (SR:C10)`.

### T025 [SR:C10] — Impl: bounded sink delivery

**Files:** `TelemetryReporter.java`.

**Steps:**
1. Add `sinkTimeout` field (default `Duration.ofSeconds(5)`); expose via constructor / setter.
2. Create a single-thread `ScheduledExecutorService` (`sinkExecutor`) — shutdown in `close()`.
3. Replace `sink.send(batch)` with `CompletableFuture.supplyAsync(() -> { sink.send(batch); return null; }, sinkExecutor).orTimeout(sinkTimeout.toMillis(), MILLISECONDS).exceptionally(this::onSinkFailure)`.
4. `onSinkFailure` logs WARN at most once per minute (use `AtomicLong lastWarnNanos`), increments failure counter.
5. `close()` shuts down `sinkExecutor` with `sinkTimeout + 1 s` timeout using `awaitTermination`, then `shutdownNow()` if still pending.
6. Run T024 — expect PASS.
7. Commit: `fix(telemetry): bound flush + close by sink timeout (SR:C10)`.

### T026 [SR:C1] — Test: Context cancellation observed

**Files:** new `src/test/java/com/authx/sdk/transport/GrpcTransportContextTest.java`.

**Steps:**
1. Stand up an in-process gRPC server that blocks for 5 s on `CheckPermission`.
2. Build a `GrpcTransport` against it with `deadlineMs = 10_000`.
3. Execute `check(...)` inside `Context.current().withCancellation()`; cancel the context after 100 ms on another thread.
4. Assert: the `check()` call fails with a `CANCELLED`-mapped exception within 200 ms (well before the 10 s deadline).
5. Commit test: `test(grpc): context cancellation propagates (SR:C1)`.

### T027 [SR:C1] — Test: tighter deadline wins

**Files:** same as T026.

**Steps:**
1. Same server blocking for 5 s.
2. `GrpcTransport` with `deadlineMs = 10_000`.
3. Execute `check(...)` inside `Context.current().withDeadlineAfter(500 ms)`.
4. Assert: fails with `DEADLINE_EXCEEDED` within ~600 ms.
5. Commit test: `test(grpc): upstream deadline wins over policy timeout (SR:C1)`.

### T028 [SR:C1] — Impl: attach CancellableContext per call

**Files:** `GrpcTransport.java`, `CloseableGrpcIterator.java`.

**Steps:**
1. In `GrpcTransport.stub()` or equivalent per-call site: compute `effectiveDeadline = min(Context.current().getDeadline(), Deadline.after(deadlineMs))`. Build a `CancellableContext` from `Context.current().withDeadline(effectiveDeadline, schedulerExecutor)`.
2. Wrap the actual stub call in `ctx.call(() -> stub.withDeadline(effectiveDeadline).xxx(req))` so cancellation propagates.
3. In `CloseableGrpcIterator.from(...)`: store the `CancellableContext` on the iterator; in `hasNext()`/`next()`, attach before the underlying `Iterator.hasNext()/next()` and detach in a `finally`.
4. Ensure `close()` calls `ctx.cancel(null)` to release resources on early iterator close.
5. Run T026, T027 — expect PASS.
6. Run full transport test suite — expect no regression.
7. Commit: `fix(grpc): propagate cancellable context across calls and iterators (SR:C1)`.

### T029 — Final verification

**Files:** none (verification only).

**Steps:**
1. `./gradlew compileJava` — must pass.
2. `./gradlew test -x :test-app:test -x :cluster-test:test` — must pass.
3. `./gradlew :test-app:compileJava` — verify no public API breaks.
4. `./gradlew :cluster-test:test --tests 'WatchStormIT' --tests 'BreakerColdStartIT'` — runtime integration check (takes ~10 min).
5. Update top-level `README.md` with a brief note in `## Changelog` section (or create one) listing the fixes by SR number.
6. Commit: `docs: note critical fixes batch (SR:C1–C10)`.

---

## Self-review Results

### Pass 1 — Coverage

| SR | Tasks | Status |
|---|---|---|
| SR:C1 | T026, T027, T028 | Covered |
| SR:C2 | T002, T003 | Covered |
| SR:C3 | T022, T023 | Covered |
| SR:C4 | T020, T021 | Covered |
| SR:C5 | T004, T005, T006, T007, T008, T009 | Covered |
| SR:C6 | T014, T015 | Covered |
| SR:C7 | T016, T017 | Covered |
| SR:C8 | T010, T011, T012, T013 | Covered |
| SR:C9 | T018, T019 | Covered |
| SR:C10 | T024, T025 | Covered |

### Pass 2 — Placeholder scan

None. Every task names the file, the approach, and the test assertion.

### Pass 3 — Type consistency

- `DroppedListenerEvent` referenced identically in T004, T005, T009.
- `QueueFullPolicy` enum values `DROP`, `BLOCK_WITH_BACKPRESSURE` referenced identically in T004, T006, T009.
- `RetryPolicy.isPermanent` method name consistent across T020, T021.
- `latencyOverflowCount()` accessor name consistent across T018, T019.

### Pass 4 — Dependency integrity

- T004 must complete before T005, T006, T009 (types must exist first).
- T005, T006 can run in parallel (different files).
- T007, T008 depend on T004, T005, T006 (tests use the types).
- T009 depends on T004, T005, T006 (impl uses the types) and completes T007, T008.
- T011 depends on T010; T013 depends on T012; no shared files.
- Phase-3 observability tasks (T018–T025) touch separate files from Phase-2 and can fully parallel-dispatch.
- T028 is the final transport-layer change; run after all other transport changes (T003, T009, T011, T013, T021, T023) to avoid merge friction in `GrpcTransport.java` and `CloseableGrpcIterator.java`.

### Pass 5 — Contradiction scan

No contradictions between spec.md SR definitions and plan.md task approaches. Acceptance criteria in spec match test assertions in plan.
