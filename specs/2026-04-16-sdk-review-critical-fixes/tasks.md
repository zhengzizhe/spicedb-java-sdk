# SDK Critical Fixes — Task Checklist

Artifact chain: `spec.md` (what/why) → `plan.md` (how, detailed) → `tasks.md` (this file, execution).

Legend: `[P]` parallelizable within phase — `[SR:C#]` spec requirement.

---

## Phase 0: Baseline

- [X] T001 Verify branch and green baseline

## Phase 1: Cache & Watch (SR:C2, SR:C5)

Landed in PR#1 (merge commit `b7c1a03`, 2026-04-18):

- **SR:C2** — Javadoc + inline comments now spell out the happens-before
  invariant at `WatchCacheInvalidator#processResponse`; regression test in
  `WatchCacheInvalidatorOrderingTest` exercises 1000 single-update responses
  and asserts zero ordering violations.
- **SR:C5** — `DroppedListenerEvent` + `QueueFullPolicy` SPIs added;
  `SdkComponents.Builder#watchListenerDropHandler(...)` surfaces drops,
  `CacheConfig#listenerQueueOnFull(...)` opts into
  `BLOCK_WITH_BACKPRESSURE`. Two tests in `WatchListenerQueuePolicyTest`
  cover the DROP handler payload and the no-drop invariant under
  backpressure.

- [X] T002 [SR:C2] Ordering Javadoc + inline documentation
- [X] T003 [SR:C2] Concurrency regression test
- [X] T004–T009 [SR:C5] DroppedListenerEvent SPI + QueueFullPolicy + handler + backpressure

## Phase 2: Transport chain (SR:C3, SR:C4, SR:C8)

- [X] T010 [SR:C8] Check-chain isolation test written
- [X] T011 [SR:C8] Read-path interceptor isolation implemented
- [X] T012 [SR:C8] Write-chain abort test written
- [X] T013 [SR:C8] Write-chain fails closed implemented
- [X] T020 [SR:C4] Non-retryable test written
- [X] T021 [SR:C4] Added `isPermanent(Throwable)` and extended `defaults()` non-retryable set with the three schema-validation exceptions. `ResilientTransport` already routes through `shouldRetry` — no transport change required.
- [X] T022 [SR:C3] Coalescing failure-eviction test written
- [X] T023 [SR:C3] Evict-before-publish on failure path implemented

## Phase 3: Builder validations (SR:C6, SR:C7)

- [X] T014 [SR:C6] Target/targets mutex test written
- [X] T015 [SR:C6] Builder rejects target + targets
- [X] T016 [SR:C7] watchInvalidation-requires-cache test written
- [X] T017 [SR:C7] Builder rejects watchInvalidation without cache

## Phase 4: Observability (SR:C9, SR:C10)

- [X] T018 [SR:C9] Latency-overflow test written
- [X] T019 [SR:C9] Added overflow counter, raised ceiling to 600 s
- [X] T024 [SR:C10] Sink-timeout test written
- [X] T025 [SR:C10] Bounded flush + close by sink timeout

## Phase 5: Context propagation (SR:C1)

Landed in PR#1 (merge commit `b7c1a03`, 2026-04-18):

- `GrpcTransport#newCallContext()` builds a `CancellableContext` whose
  deadline is `min(Context.current().getDeadline(), now + policyTimeout)`;
  every unary call now runs under this context and every iterator call is
  handed one via the new `CloseableGrpcIterator.from(supplier, ctx)`
  overload. The iterator re-attaches `ctx` inside `hasNext` / `next` so
  Context-sensitive operations during lazy iteration see the same context.
  Two tests in `GrpcTransportContextTest` cover upstream cancellation
  propagation (≤250 ms) and tighter-upstream-deadline wins.

- [X] T026–T028 [SR:C1] CancellableContext wrapping + iterator preservation + tests

## Phase 6: Verification

- [X] T029 Full SDK unit suite green; `:test-app:compileJava` passes (public API stable)
- [X] T030 README changelog entry added

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
