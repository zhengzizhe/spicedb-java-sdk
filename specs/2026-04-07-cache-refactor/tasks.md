# Cache Layer Refactor — Task Checklist

> Track progress by marking items `[X]` as completed.

## Phase 0: Setup
- [X] T001 [SR:req-2] Add Lettuce compileOnly dependency — `build.gradle`

## Phase 1: Core Cache Components
- [X] T002 [P] [SR:req-5] TieredCache implements IndexedCache — `src/.../cache/TieredCache.java`
- [X] T003 [P] [SR:req-3,req-4] Create RedisCacheAdapter — `src/.../cache/RedisCacheAdapter.java`

## Phase 2: Integration
- [X] T004 [SR:req-1,req-10] Remove SdkComponents.l2Cache — `src/.../spi/SdkComponents.java`
- [X] T005 [SR:req-7,req-8] Update AuthxClient Builder — Redis config + wiring — `src/.../AuthxClient.java`

## Phase 3: Verification
- [X] T006 [SR:req-6,req-9] Watch invalidation path + multi-instance tests — `src/test/.../transport/WatchInvalidationPathTest.java`
- [X] T007 [SR:req-10] Update cache-consistency-guide.md — `docs/cache-consistency-guide.md`
- [X] T008 Final verification — run full test suite, grep for dead references

## Dependencies

```
T002, T003 are parallelizable [P] (independent cache components)
T004 depends on T003 (RedisCacheAdapter must exist before SdkComponents.l2Cache removal)
T005 depends on T002, T003, T004 (needs TieredCache IndexedCache + RedisCacheAdapter + cleaned SdkComponents)
T006 depends on T002, T003, T005 (needs full wiring to test end-to-end path)
T007 depends on T005 (docs reference the new API)
T008 depends on all
```

## Coverage Matrix

| Spec Requirement | Task(s) | Status |
|---|---|---|
| req-1: Remove L2 cache SPI | T004 | Covered |
| req-2: Lettuce dependency | T001 | Covered |
| req-3: Redis Hash structure | T003 | Covered |
| req-4: RedisCacheAdapter | T003 | Covered |
| req-5: TieredCache IndexedCache | T002 | Covered |
| req-6: Watch invalidation path fix | T006 | Covered |
| req-7: Config API simplify | T005 | Covered |
| req-8: Runtime detection/degradation | T005 | Covered |
| req-9: Multi-instance correctness | T006 | Covered |
| req-10: Clean dead code + docs | T004, T007 | Covered |
