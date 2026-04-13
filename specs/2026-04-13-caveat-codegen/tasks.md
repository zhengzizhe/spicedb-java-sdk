# Caveat Code Generation — Tasks

## Phase 1: Foundation
- [ ] T001 [SR:req-2,req-7] Add CaveatDef record and accessors to SchemaCache + tests — `cache/SchemaCache.java`
- [ ] T002 [SR:req-1] Extract caveats in SchemaLoader — `transport/SchemaLoader.java`

## Phase 2: Code Generation
- [ ] T003 [SR:req-3,req-4,req-5,req-6,req-7] Add caveat class emission to AuthxCodegen + tests — `AuthxCodegen.java`
- [ ] T004 [SR:req-3,req-4] Wire caveat generation into generate() entry point — `AuthxCodegen.java`

## Phase 3: Verification
- [ ] T005 [SR:req-7] Final verification — full test suite

## Dependencies
T002 depends on T001 (SchemaCache must have CaveatDef before SchemaLoader can populate it)
T003 depends on T001 (tests reference CaveatDef)
T004 depends on T002, T003
T005 depends on T004

## Coverage

| Spec Requirement | Task(s) | Status |
|---|---|---|
| req-1: SchemaLoader extracts caveats | T002 | Covered |
| req-2: SchemaCache stores caveat metadata | T001 | Covered |
| req-3: AuthxCodegen generates per-caveat classes | T003, T004 | Covered |
| req-4: AuthxCodegen generates Caveats.java | T003, T004 | Covered |
| req-5: keyValues varargs helper | T003 | Covered |
| req-6: Type mapping in Javadoc | T003 | Covered |
| req-7: Tests | T001, T003, T005 | Covered |
