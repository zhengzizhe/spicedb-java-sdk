# SDK Critical Fixes — Task Checklist

Artifact chain: `spec.md` (what/why) → `plan.md` (how, detailed) → `tasks.md` (this file, execution).

Legend: `[P]` parallelizable within phase — `[SR:C#]` spec requirement.

---

## Phase 0: Baseline

- [X] T001 Verify branch and green baseline

## Phase 1: Cache & Watch (SR:C2, SR:C5) — DEFERRED

Discovery during execution:

- **SR:C2** — On inspection the ordering is already correct in the current
  code. `processResponse` invalidates the cache on the gRPC callback thread
  BEFORE enqueuing listener dispatch
  (`src/main/java/com/authx/sdk/transport/WatchCacheInvalidator.java:617-624`).
  The review agent that flagged C2 was reading lines 610-624 out of order.
  Deferring the regression test (writing a reliable 1000-concurrent test is
  its own design exercise); real invariant holds today.
- **SR:C5** — drop handler SPI + backpressure policy is a larger wiring
  change crossing `SdkComponents`, `AuthxClientBuilder`, `WatchCacheInvalidator`
  and requires new public types. Defers to a dedicated follow-up plan so it
  can be reviewed independently of the critical-correctness batch.

- [ ] T002 [SR:C2] DEFERRED — current code already satisfies the invariant
- [ ] T003 [SR:C2] DEFERRED — see above
- [ ] T004–T009 [SR:C5] DEFERRED — split into a follow-up plan

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

## Phase 5: Context propagation (SR:C1) — DEFERRED

Writing a deterministic test requires standing up an in-process gRPC server
that blocks predictably; the implementation itself is also subtle (must
preserve `CancellableContext` across `CloseableGrpcIterator` lazy iteration
without breaking existing iterator semantics). Scoped to a dedicated
follow-up plan.

- [ ] T026–T028 [SR:C1] DEFERRED — follow-up plan

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
