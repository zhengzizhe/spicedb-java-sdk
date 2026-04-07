# SDK Quality Improvements — Task Checklist

> Track progress by marking items `[X]` as completed.

## Phase 0: P0 — Release Blockers

- [X] T001 [P] [SR:ops-1] Create CI pipeline — `.github/workflows/ci.yml`
- [X] T002 [P] [SR:ops-2] Create release workflow — `.github/workflows/release.yml`
- [X] T003 [SR:test-1] Comprehensive WatchCacheInvalidator tests — `src/test/.../WatchCacheInvalidatorTest.java`
- [X] T004 [SR:ops-3] Caffeine runtime detection with warning — `src/.../AuthxClient.java`
- [X] T005 [SR:ops-4] Unify artifact ID and group — `build.gradle`

## Phase 1: P1 — Post-Release Urgent

- [X] T006 [SR:arch-1] Split Builder.build() into sub-methods — `src/.../AuthxClient.java`
- [X] T007 [SR:cache-2] Clarify TieredCache.stats() (correct but confusing — add comments) — `src/.../cache/TieredCache.java`
- [X] T008 [SR:cache-1] Remove post-invalidation in CachedTransport — `src/.../transport/CachedTransport.java`
- [X] T009 [SR:api-1] ResourceFactory grant/revoke return result objects — `src/.../ResourceFactory.java`
- [X] T010 [SR:cache-4] WatchCacheInvalidator listener queue drop metric — `src/.../transport/WatchCacheInvalidator.java`
- [X] T011 [SR:obs-2] TelemetryReporter buffer-full drop counter — `src/.../telemetry/TelemetryReporter.java`
- [X] T012 [P] [SR:doc-1] Add English README — `README_en.md`

## Phase 2: P2 — Post-Release Optimization

- [ ] T013 [SR:arch-2] Document transport decorator ordering — `src/.../AuthxClient.java`
- [ ] T014 [SR:arch-3] ResilientTransport breaker LRU eviction — `src/.../transport/ResilientTransport.java`
- [ ] T015 [P] [SR:err-4] AuthxException.isRetryable() — `src/.../exception/AuthxException.java`
- [ ] T016 [P] [SR:err-2] Schema validation exception constructors — `src/.../exception/Invalid*.java`
- [ ] T017 [SR:err-5,test-4] Extract GrpcExceptionMapper + tests — `src/.../transport/GrpcExceptionMapper.java`
- [ ] T018 [SR:test-5,test-2] TelemetryReporter + SdkMetrics concurrent tests — `src/test/...`
- [ ] T019 [P] [SR:doc-4] Resilience configuration guide — `docs/resilience-guide.md`
- [ ] T020 [P] [SR:doc-3] Cache and consistency guide — `docs/cache-consistency-guide.md`
- [ ] T021 [SR:test-3] E2E Testcontainers integration — `build.gradle`, `SdkEndToEndTest.java`

## Phase 3: P3 — Long-Term Evolution

- [ ] T022 [SR:api-2] Document async API pattern — `src/.../ResourceHandle.java`
- [ ] T023 [SR:api-4] LookupQuery SubjectRef overload — `src/.../LookupQuery.java`
- [ ] T024 [SR:cache-5] Streaming backpressure safety valve — `src/.../transport/GrpcTransport.java`
- [ ] T025 [SR:obs-4] Event bus async publish option — `src/.../event/DefaultTypedEventBus.java`
- [ ] T026 [SR:arch-4] CoalescingTransport join timeout — `src/.../transport/CoalescingTransport.java`
- [ ] T027 [SR:arch-5] InterceptorTransport unified chain model — `src/.../transport/InterceptorTransport.java`

## Dependencies

```
Phase 0: T001, T002 are parallelizable [P]. T003, T004, T005 are independent.
Phase 1: T006 should be done first (other P1 tasks may touch build()). T007-T012 are independent.
          T012 is parallelizable with code tasks.
Phase 2: T013 depends on T006. T017 is independent. T015, T016 are parallelizable [P].
          T019, T020 are parallelizable [P] (docs only).
          T018 depends on T011 (tests the buffer-full counter).
          T021 is independent.
Phase 3: All tasks are independent of each other. Each depends on Phase 2 completion.
```

## Coverage Matrix

| Spec Requirement | Task(s) | Status |
|---|---|---|
| ops-1: CI pipeline | T001 | Covered |
| ops-2: Release automation | T002 | Covered |
| test-1: Watch tests | T003 | Covered |
| ops-3: Caffeine detection | T004 | Covered |
| ops-4: Artifact ID | T005 | Covered |
| arch-1: Builder.build() split | T006 | Covered |
| cache-2: TieredCache stats | T007 | Covered |
| cache-1: CachedTransport invalidation | T008 | Covered |
| api-1: ResourceFactory return types | T009 | Covered |
| cache-4: Watch listener drop metric | T010 | Covered |
| obs-2: Telemetry buffer drop | T011 | Covered |
| doc-1: English README | T012 | Covered |
| arch-2: Decorator ordering | T013 | Covered |
| arch-3: Breaker LRU | T014 | Covered |
| err-4: isRetryable() | T015 | Covered |
| err-2: Exception constructors | T016 | Covered |
| err-5: GrpcExceptionMapper | T017 | Covered |
| test-5: TelemetryReporter tests | T018 | Covered |
| test-2: SdkMetrics tests | T018 | Covered |
| doc-4: Resilience guide | T019 | Covered |
| doc-3: Cache guide | T020 | Covered |
| test-3: Testcontainers | T021 | Covered |
| api-2: Async pattern | T022 | Covered |
| api-4: LookupQuery SubjectRef | T023 | Covered |
| cache-5: Streaming backpressure | T024 | Covered |
| obs-4: Async events | T025 | Covered |
| arch-4: Coalescing timeout | T026 | Covered |
| arch-5: Interceptor unification | T027 | Covered |
