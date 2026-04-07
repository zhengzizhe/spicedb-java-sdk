# Code Beauty Phase 1 — Task Checklist

> Track progress by marking items `[X]` as completed.

## Phase 0: Setup
- [ ] T001 [SR:req-1] Add JSpecify dependency — `build.gradle`

## Phase 1: Policy Getter Renames (breaks callers, must be atomic)
- [ ] T002 [P] [SR:req-2] Rename ResourcePolicy getters — `policy/ResourcePolicy.java`
- [ ] T003 [P] [SR:req-2,req-3] Rename CachePolicy getters + ofTtl→of — `policy/CachePolicy.java`
- [ ] T004 [P] [SR:req-2] Rename RetryPolicy getters — `policy/RetryPolicy.java`
- [ ] T005 [P] [SR:req-2] Rename CircuitBreakerPolicy getters — `policy/CircuitBreakerPolicy.java`
- [ ] T006 [SR:req-2] Rename PolicyRegistry getter + update internal calls — `policy/PolicyRegistry.java`
- [ ] T007 [SR:req-2] Fix all callers (ResilientTransport, AuthxClient) — `transport/ResilientTransport.java`

## Phase 2: Factory Method + @Nullable (independent of Phase 1)
- [ ] T008 [SR:req-3] CheckRequest.from→of + update callers — `model/CheckRequest.java`
- [ ] T009 [SR:req-1] Create @NullMarked package-info files — 6 new files
- [ ] T010 [P] [SR:req-1] @Nullable annotations — model layer — `model/*.java`
- [ ] T011 [P] [SR:req-1,req-8] @Nullable annotations — policy + spi layer — `policy/*.java`, `spi/*.java`

## Phase 3: Javadoc + Imports (purely additive)
- [ ] T012 [P] [SR:req-5] Javadoc — model records — `model/*.java`
- [ ] T013 [P] [SR:req-6] Javadoc — exception layer — `exception/*.java`
- [ ] T014 [P] [SR:req-7] Javadoc — policy layer — `policy/*.java`
- [ ] T015 [SR:req-4] Import cleanup — no wildcards — scope files

## Phase 4: Verification
- [ ] T016 Final verification — compile + test + grep checks

## Dependencies

```
T001 blocks all (JSpecify needed for @Nullable)
T002-T005 are parallelizable [P] (each changes one policy file independently)
T006 depends on T002-T005 (PolicyRegistry calls renamed methods)
T007 depends on T006 (fixes callers after all renames done)
T008 is independent (different package)
T009 depends on T001 (needs JSpecify)
T010, T011 depend on T009 (need package-info files)
T010, T011 are parallelizable [P]
T012, T013, T014 are parallelizable [P] (independent Javadoc additions)
T015 is independent (import cleanup)
T016 depends on all
```

## Coverage Matrix

| Spec Requirement | Task(s) | Status |
|---|---|---|
| req-1: @Nullable annotations | T001, T009, T010, T011 | Covered |
| req-2: Getter property-style naming | T002, T003, T004, T005, T006, T007 | Covered |
| req-3: Factory method naming (of) | T003, T008 | Covered |
| req-4: Import cleanup | T015 | Covered |
| req-5: Model record Javadoc | T012 | Covered |
| req-6: Exception Javadoc | T013 | Covered |
| req-7: Policy Javadoc | T014 | Covered |
| req-8: SPI @Nullable | T011 | Covered |
