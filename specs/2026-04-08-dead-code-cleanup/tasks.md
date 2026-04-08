# Dead Code & Stale Content Cleanup — Task Checklist

> Track progress by marking items `[X]` as completed.

## Phase 1: Dead Code & Dependency Removal

- [X] T001 [P] [SR:req-1] Delete LogRedactionInterceptor — `src/.../builtin/LogRedactionInterceptor.java`
- [X] T002 [P] [SR:req-2] Remove jackson-databind dependency — `build.gradle`

## Phase 2: Documentation Fixes

- [X] T003 [P] [SR:req-3] Update README stale L2 cache references — `README.md`, `README_en.md`
- [X] T004 [P] [SR:req-4] Mark cache-refactor spec as superseded — `specs/2026-04-07-cache-refactor/SUPERSEDED.md`

## Phase 3: Verification

- [X] T005 Final verification — compile + test + grep checks

## Dependencies

```
T001, T002 are parallelizable [P] (independent files)
T003, T004 are parallelizable [P] (independent files)
T005 depends on T001, T002, T003, T004
Phase 1 and Phase 2 are independent — all of T001-T004 can run in parallel
```

## Coverage Matrix

| Spec Requirement | Task(s) | Status |
|---|---|---|
| req-1: Remove dead LogRedactionInterceptor | T001 | Covered |
| req-2: Remove unused jackson-databind | T002 | Covered |
| req-3: Update README stale L2 cache references | T003 | Covered |
| req-4: Mark superseded cache-refactor spec | T004 | Covered |
