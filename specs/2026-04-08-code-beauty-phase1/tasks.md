# Code Beauty Phase 1 — Task Checklist

> Track progress by marking items `[X]` as completed.

## Phase 0: Setup
- [X] T001 [SR:req-1] Add JSpecify dependency — `build.gradle`

## Phase 1: Policy Getter Renames (breaks callers, must be atomic)
- [X] T002 [P] [SR:req-2] Rename ResourcePolicy getters — `policy/ResourcePolicy.java`
- [X] T003 [P] [SR:req-2,req-3] Rename CachePolicy getters + ofTtl→of — `policy/CachePolicy.java`
- [X] T004 [P] [SR:req-2] Rename RetryPolicy getters — `policy/RetryPolicy.java`
- [X] T005 [P] [SR:req-2] Rename CircuitBreakerPolicy getters — `policy/CircuitBreakerPolicy.java`
- [X] T006 [SR:req-2] Rename PolicyRegistry getter + update internal calls — `policy/PolicyRegistry.java`
- [X] T007 [SR:req-2] Fix all callers (ResilientTransport, AuthxClient) — `transport/ResilientTransport.java`

## Phase 2: Factory Method + @Nullable (independent of Phase 1)
- [X] T008 [SR:req-3] CheckRequest.from→of + update callers — `model/CheckRequest.java`
- [X] T009 [SR:req-1] Create @NullMarked package-info files — 6 new files
- [X] T010 [P] [SR:req-1] @Nullable annotations — model layer — `model/*.java`
- [X] T011 [P] [SR:req-1,req-8] @Nullable annotations — policy + spi layer — `policy/*.java`, `spi/*.java`

## Phase 3: Javadoc + Imports (purely additive)
- [X] T012 [P] [SR:req-5] Javadoc — model records — `model/*.java`
- [X] T013 [P] [SR:req-6] Javadoc — exception layer — `exception/*.java`
- [X] T014 [P] [SR:req-7] Javadoc — policy layer — `policy/*.java`
- [X] T015 [SR:req-4] Import cleanup — no wildcards — scope files

## Phase 4: Verification
- [X] T016 Final verification — compile + test + grep checks

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
