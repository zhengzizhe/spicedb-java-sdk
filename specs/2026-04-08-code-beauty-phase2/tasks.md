# Code Beauty Phase 2 — Task Checklist

> Track progress by marking items `[X]` as completed.

## Phase 1: Extract ResourceHandle inner classes
- [X] T001 [SR:req-1,req-5] Extract 11 action classes to action/ package — `src/.../action/*.java`
- [X] T002 [SR:req-1] Slim down ResourceHandle — `src/.../ResourceHandle.java`

## Phase 2: Extract AuthxClient Builder
- [X] T003 [SR:req-2] Extract Builder to AuthxClientBuilder.java — `src/.../AuthxClientBuilder.java`
- [X] T004 [SR:req-2] Slim down AuthxClient — `src/.../AuthxClient.java`

## Phase 3: Javadoc + imports
- [X] T005 [P] [SR:req-3] Javadoc — ResourceHandle + action classes — `src/.../ResourceHandle.java`, `action/*.java`
- [X] T006 [P] [SR:req-3] Javadoc — AuthxClient + Builder + ResourceFactory + LookupQuery + CrossResourceBatchBuilder
- [X] T007 [SR:req-4] Import cleanup — no wildcards in API layer

## Phase 4: Verification
- [X] T008 Final verification — compile + test + line count checks

## Dependencies

```
T001 must be first (creates action/ package)
T002 depends on T001 (ResourceHandle references extracted classes)
T003 is independent of T001/T002 (different file)
T004 depends on T003
T005, T006 are parallelizable [P]
T007 depends on T001-T004 (imports change after extraction)
T008 depends on all
```

## Coverage Matrix

| Spec Requirement | Task(s) | Status |
|---|---|---|
| req-1: ResourceHandle inner class extraction | T001, T002 | Covered |
| req-2: AuthxClient Builder extraction | T003, T004 | Covered |
| req-3: API layer Javadoc | T005, T006 | Covered |
| req-4: Import cleanup | T007 | Covered |
| req-5: action package @NullMarked | T001 | Covered |
