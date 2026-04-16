# SDK Critical Fixes — Task Checklist

Artifact chain: `spec.md` (what/why) → `plan.md` (how, detailed) → `tasks.md` (this file, execution).

Legend: `[P]` parallelizable within phase — `[SR:C#]` spec requirement.

---

## Phase 0: Baseline

- [ ] T001 Verify branch and green baseline — `./gradlew compileJava test -x :test-app:test -x :cluster-test:test`

## Phase 1: Cache & Watch (SR:C2, SR:C5)

- [ ] T002 [SR:C2] Write ordering-invariant test — `src/test/java/com/authx/sdk/transport/WatchCacheInvalidatorOrderingTest.java`
- [ ] T003 [SR:C2] Swap invalidate-before-dispatch in `processResponse` — `src/main/java/com/authx/sdk/transport/WatchCacheInvalidator.java`
- [ ] T004 [SR:C5] Create new record + enum — `src/main/java/com/authx/sdk/spi/DroppedListenerEvent.java`, `src/main/java/com/authx/sdk/cache/QueueFullPolicy.java`
- [ ] T005 [P] [SR:C5] Add `watchListenerDropHandler` field + builder — `src/main/java/com/authx/sdk/spi/SdkComponents.java`
- [ ] T006 [P] [SR:C5] Add `listenerQueueOnFull` to `CacheConfig` — `src/main/java/com/authx/sdk/AuthxClientBuilder.java`
- [ ] T007 [SR:C5] Write drop-handler saturation test — `src/test/java/com/authx/sdk/transport/WatchCacheInvalidatorBackpressureTest.java`
- [ ] T008 [SR:C5] Write BLOCK_WITH_BACKPRESSURE test — same file as T007
- [ ] T009 [SR:C5] Wire drop handler + policy into invalidator — `src/main/java/com/authx/sdk/transport/WatchCacheInvalidator.java`

## Phase 2: Transport chain (SR:C3, SR:C4, SR:C8)

- [ ] T010 [SR:C8] Write check-chain interceptor isolation test — `src/test/java/com/authx/sdk/transport/RealCheckChainIsolationTest.java`
- [ ] T011 [SR:C8] Isolate read-path interceptors — `src/main/java/com/authx/sdk/transport/RealCheckChain.java`, `src/main/java/com/authx/sdk/transport/RealOperationChain.java`
- [ ] T012 [SR:C8] Write write-chain abort test — same file as T010
- [ ] T013 [SR:C8] Write chain fails closed on interceptor exception — `src/main/java/com/authx/sdk/transport/RealWriteChain.java`
- [ ] T020 [P] [SR:C4] Write non-retryable test — `src/test/java/com/authx/sdk/transport/ResilientTransportNonRetryableTest.java`
- [ ] T021 [SR:C4] Add `isPermanent(Throwable)` + wire — `src/main/java/com/authx/sdk/policy/RetryPolicy.java`, `src/main/java/com/authx/sdk/transport/ResilientTransport.java`
- [ ] T022 [SR:C3] Write coalescing failure-eviction test — `src/test/java/com/authx/sdk/transport/CoalescingTransportFailureEvictionTest.java`
- [ ] T023 [SR:C3] Evict failed future before publishing — `src/main/java/com/authx/sdk/transport/CoalescingTransport.java`

## Phase 3: Builder validations (SR:C6, SR:C7)

- [ ] T014 [P] [SR:C6] Write target/targets mutex test — `src/test/java/com/authx/sdk/AuthxClientBuilderValidationTest.java`
- [ ] T015 [SR:C6] Reject target + targets simultaneously — `src/main/java/com/authx/sdk/AuthxClientBuilder.java`
- [ ] T016 [P] [SR:C7] Write watchInvalidation-requires-cache test — same file as T014
- [ ] T017 [SR:C7] Validate watchInvalidation requires cache — `src/main/java/com/authx/sdk/AuthxClientBuilder.java`

## Phase 4: Observability (SR:C9, SR:C10)

- [ ] T018 [P] [SR:C9] Write latency-overflow test — `src/test/java/com/authx/sdk/metrics/SdkMetricsOverflowTest.java`
- [ ] T019 [SR:C9] Add overflow counter + raise cap to 600 s — `src/main/java/com/authx/sdk/metrics/SdkMetrics.java`
- [ ] T024 [P] [SR:C10] Write sink-timeout test — `src/test/java/com/authx/sdk/telemetry/TelemetryReporterSinkTimeoutTest.java`
- [ ] T025 [SR:C10] Bound flush + close by sink timeout — `src/main/java/com/authx/sdk/telemetry/TelemetryReporter.java`

## Phase 5: Context propagation (SR:C1)

- [ ] T026 [SR:C1] Write context-cancellation test — `src/test/java/com/authx/sdk/transport/GrpcTransportContextTest.java`
- [ ] T027 [SR:C1] Write tighter-deadline-wins test — same file as T026
- [ ] T028 [SR:C1] Attach `CancellableContext` in `GrpcTransport` + `CloseableGrpcIterator` — `src/main/java/com/authx/sdk/transport/GrpcTransport.java`, `src/main/java/com/authx/sdk/transport/CloseableGrpcIterator.java`

## Phase 6: Verification

- [ ] T029 Full unit suite + `test-app` compile + `cluster-test` WatchStormIT & BreakerColdStartIT — no-code task
- [ ] T030 Update `README.md` changelog entry — `README.md`

---

## Dependencies

- **Within Phase 1**: T004 → T005, T006 (types needed); T004, T005, T006 → T007, T008, T009.
- T002 → T003 (TDD).
- **Within Phase 2**: T010 → T011; T012 → T013; T020 → T021; T022 → T023.
- **Within Phase 3**: T014 → T015; T016 → T017.
- **Within Phase 4**: T018 → T019; T024 → T025.
- **Phase 5** (T026, T027, T028) should run **last** in the implementation phases because `GrpcTransport` is also touched indirectly by T009 wiring (`SdkComponents` threading). T026, T027 → T028.
- **T029 and T030** block on all prior tasks.

## Parallelizable batches

Safe to dispatch in parallel (different files):

- Batch A: {T005, T006} after T004.
- Batch B: {T014, T016, T018, T020, T024} (independent test files across subsystems).
- Batch C: Phase-4 and Phase-3 implementations (T015, T017, T019, T025) after their tests.

Sequential only (shared files):

- Anything touching `WatchCacheInvalidator.java` (T003, T009) — serialize.
- Anything touching `GrpcTransport.java` or `CloseableGrpcIterator.java` (T028) — runs last.
- Builder validations (T015, T017) — serialize because both edit `AuthxClientBuilder#build()`.

## Notes for the executing-plans session

- **TDD is mandatory.** For every `[SR:*]` task, write the test first, run to confirm it fails, then implement.
- **Commit per task.** Each task ends in a commit with a conventional-commit subject referencing the SR.
- **No batched commits** across tasks — small commits make the fix round bisectable.
- **Public API gate:** after every phase, run `./gradlew :test-app:compileJava` to confirm no breakage.
- **Run full suite before Phase 6:** `./gradlew test -x :cluster-test:test` should be green before the integration smoke-test task.
