# Schema Flat Descriptors — Tasks

> **For agentic workers:** This checklist is executed by the `authx-executing-plans` skill. Mark each task `[X]` on completion. Detailed per-task steps (code, commands, expected output, commit message) live in `plan.md` next to the matching Task ID. Read `spec.md` for *what* / `plan.md` for *how*.

**Branch:** `feature/schema-flat-descriptors`
**Segments:** 3 atomic commits on this branch (Segment 1 = T001–T020, Segment 2 = T021–T030, Segment 3 = T031–T035).
**Per-task gate:** `./gradlew compileJava` passes.
**Per-phase gate:** `./gradlew test -x :test-app:test` passes (phase 1) or `./gradlew test` passes (phase 2+).

---

## Phase 1 — Segment 1: SDK foundations + codegen engine (req-1..8)

This phase produces one commit (T020). Test-app schema files are **not** touched yet — they remain backward-compatible against the new SDK until T021.

- [X] T001 [SR:req-8] Create `PermissionProxy<P>` public interface with `Class<P> enumClass()` — `src/main/java/com/authx/sdk/PermissionProxy.java`
- [X] T002 [SR:req-4] Un-`final` `ResourceType`, change constructor to `protected`, keep `of(...)` factory + fields — `src/main/java/com/authx/sdk/ResourceType.java`
- [X] T003 [SR:req-4] Add `ResourceTypeSubclassTest` — user-defined subclass preserves `name()` / `relClass()` / `permClass()` semantics — `src/test/java/com/authx/sdk/ResourceTypeSubclassTest.java`
- [X] T004 [P] [SR:req-6] Add enum-typed `to(ResourceType, id, R2 subRelation)` + `to(ResourceType, id, P2 subPermission)` overloads on `TypedGrantAction`, delegate to existing string-sub-rel path — `src/main/java/com/authx/sdk/TypedGrantAction.java`
- [X] T005 [P] [SR:req-6] Symmetric `from(ResourceType, id, R2)` + `from(ResourceType, id, P2)` overloads on `TypedRevokeAction` — `src/main/java/com/authx/sdk/TypedRevokeAction.java`
- [X] T006 [P] [SR:req-5] [SR:req-6] Add enum-typed sub-relation overloads on all four `CrossResourceBatchBuilder` nested scopes (`GrantScope`, `RevokeScope`, `MultiGrantScope`, `MultiRevokeScope`) — `src/main/java/com/authx/sdk/CrossResourceBatchBuilder.java`
- [X] T007 [P] [SR:req-7] [SR:req-8] Add `checkAll(PermissionProxy<P2>)` overload on `TypedHandle`, delegate to `checkAll(proxy.enumClass())` — `src/main/java/com/authx/sdk/TypedHandle.java`
- [X] T008 [SR:req-7] Add `who(ResourceType<R2,P2>, P permission)` overload on `TypedHandle`, delegate to existing `who(String, P)` — `src/main/java/com/authx/sdk/TypedHandle.java`
- [X] T009 [P] [SR:req-6] Wire-format identity test for enum-typed grant sub-relation — `src/test/java/com/authx/sdk/TypedGrantActionSubRelationTest.java`
- [X] T010 [P] [SR:req-6] Wire-format identity test for enum-typed revoke sub-relation — `src/test/java/com/authx/sdk/TypedRevokeActionSubRelationTest.java`
- [X] T011 [P] [SR:req-5] [SR:req-6] Wire-format identity test for enum-typed sub-relation on all four batch scopes — `src/test/java/com/authx/sdk/CrossResourceBatchTypedSubRelationTest.java`
- [X] T012 [P] [SR:req-8] `TypedCheckAllProxyTest` — verify `checkAll(proxy)` and `checkAll(proxy.enumClass())` produce identical `CheckBulkPermissions` RPCs — `src/test/java/com/authx/sdk/TypedCheckAllProxyTest.java`
- [X] T013 [SR:req-1] Implement `AuthxCodegen.emitSchema(String packageName, List<ResourceTypeDef>, Path out)` — emits `Schema.java` with one Descriptor + RelProxy + PermProxy per type, FQN enum refs in Proxy fields — `src/main/java/com/authx/sdk/AuthxCodegen.java`
- [X] T014 [SR:req-2] Modify `AuthxCodegen.emitTypeClass` — drop `public static final ResourceType<Rel,Perm> TYPE = ...` emission, drop ResourceType import from header — `src/main/java/com/authx/sdk/AuthxCodegen.java`
- [X] T015 [SR:req-3] Modify `AuthxCodegen.generate` — no longer writes `ResourceTypes.java`, deletes a pre-existing `ResourceTypes.java` on the output path if found, calls `emitSchema(...)` — `src/main/java/com/authx/sdk/AuthxCodegen.java`
- [X] T016 [SR:req-1] Extend `AuthxCodegenTest` — `emitsSchemaAggregatorWithDescriptorAndProxies` + FQN assertion — `src/test/java/com/authx/sdk/AuthxCodegenTest.java`
- [X] T017 [SR:req-2] Extend `AuthxCodegenTest` — `emitTypeClassDoesNotEmitTypeField` + `doesNotImportResourceType` — `src/test/java/com/authx/sdk/AuthxCodegenTest.java` (same file as T016 → sequential)
- [X] T018 [SR:req-3] Extend `AuthxCodegenTest` — `generateRemovesObsoleteResourceTypesFileIfPresent` + `endToEndEmitsSchemaFile` — `src/test/java/com/authx/sdk/AuthxCodegenTest.java` (same file as T016/T017 → sequential)
- [X] T019 Phase-1 gate — run `./gradlew compileJava` and `./gradlew test -x :test-app:test`, must be green
- [X] T020 Segment-1 commit — stage all files from T001–T018, commit with message "feat(sdk): flat descriptors — PermissionProxy, typed sub-rel overloads, Schema.java codegen"

---

## Phase 2 — Segment 2: regenerate schema + migrate services (req-9, partial req-1/2/3 materialization)

Schema regeneration (T021) and service migrations (T023–T028) must land in the same commit (T030) because test-app compilation requires both to be consistent.

- [X] T021 [SR:req-1] [SR:req-2] [SR:req-3] Run `AuthxCodegen` against live `schema.zed`; regenerates slim `Organization.java` / `Department.java` / `Group.java` / `Space.java` / `Folder.java` / `Document.java` / `User.java` (no TYPE), generates `Schema.java`, deletes `ResourceTypes.java` — `test-app/src/main/java/com/authx/testapp/schema/*`
- [X] T022 [P] [SR:req-1] `SchemaInitOrderTest` — reflectively assert every `Schema.Xxx` descriptor + every `Schema.Xxx.Rel.YYY` / `Schema.Xxx.Perm.YYY` field is non-null; identity-compare against `pkg.Xxx.Rel.YYY` — `test-app/src/test/java/com/authx/testapp/schema/SchemaInitOrderTest.java`
- [X] T023 [P] [SR:req-9] Migrate `CompanyWorkspaceService` — add `import static com.authx.testapp.schema.Schema.*;`, rewrite every `.on(Xxx.TYPE)` → `.on(Xxx)`, every `.to(User.TYPE, id)` → `.to(User, id)`, every `.to(Group, id, "member")` → `.to(Group, id, Group.Rel.MEMBER)` (keep 1 string-form call for polymorphism coverage) — `test-app/src/main/java/com/authx/testapp/service/CompanyWorkspaceService.java`
- [X] T024 [P] [SR:req-9] Migrate `DocumentSharingService` — same rewrites; `findBy(User.TYPE, id)` → `findBy(User, id)`, `who(...)` typed — `test-app/src/main/java/com/authx/testapp/service/DocumentSharingService.java`
- [X] T025 [P] [SR:req-9] Migrate `WorkspaceAccessService` — `batchCheck().add(Xxx.TYPE, ...)` → `.add(Xxx, ...)`, `m.allowed(Xxx.TYPE + ":" + id, ...)` → `m.allowed(Xxx + ":" + id, ...)` (relies on `ResourceType.toString()` returning `name()`) — `test-app/src/main/java/com/authx/testapp/service/WorkspaceAccessService.java`
- [X] T026 [P] [SR:req-9] Migrate `ConditionalShareService` — 4 call sites, straight rewrite — `test-app/src/main/java/com/authx/testapp/service/ConditionalShareService.java`
- [X] T027 [P] [SR:req-9] Migrate `CompanyWorkspaceServiceTest` — assertions unchanged, only call sites — `test-app/src/test/java/com/authx/testapp/service/CompanyWorkspaceServiceTest.java`
- [X] T028 [P] [SR:req-9] Migrate `ConditionalShareServiceTest` — no `.TYPE` references found in the current codebase; no-op (already flat-descriptor-compatible) — `test-app/src/test/java/com/authx/testapp/service/ConditionalShareServiceTest.java`
- [X] T029 Phase-2 gate — `./gradlew test` (all modules) must be green; verify `grep -r "\.TYPE" test-app/src/main/java/com/authx/testapp/service/` returns zero hits
- [X] T030 Segment-2 commits — per-task commits already landed (T021, T023..T028, + iceberg controllers). Phase-2 logical aggregate marker.

---

## Phase 3 — Segment 3: docs (req-10)

- [X] T031 [SR:req-10] Update `README.md` API example block to new flat-descriptor form — `README.md`
- [X] T032 [SR:req-10] Create migration guide with before/after table for external users — `docs/migration-schema-flat-descriptors.md`
- [X] T033 [P] [SR:req-10] Grep docs for lingering `Xxx.TYPE` / `.on("<type>")` examples and update; grep skills in `.claude/skills/` too — `docs/**/*.md`, `README.md`, `.claude/**/*.md`
- [X] T034 [SR:req-10] Phase-3 gate + segment-3 commit — `./gradlew test` final green run, commit with message "docs: migration guide + README examples for flat descriptors"
- [X] T035 Final verification — run success-criteria checklist from spec §Success Criteria:
  1. `./gradlew test` green
  2. `CompanyWorkspaceService.java` char-count ≥ 25% smaller than pre-migration
  3. `grep -r "\.TYPE" test-app/src/main/java/com/authx/testapp/service/` returns 0
  4. `grep -nE "^\s*public final [A-Za-z_.]+ [A-Z_]+ = [A-Z]" test-app/src/main/java/com/authx/testapp/schema/Schema.java` returns 0 (every Proxy field uses FQN, no short-form lines)
  5. Wire-format identity tests (T009, T010, T011) green
  6. `docs/migration-schema-flat-descriptors.md` exists with both before+after examples

---

## Dependencies

```
T001 ─┬──────────────────────────┐
      │                          ├──> T007  (needs PermissionProxy)
      │                          ├──> T012  (test for PermissionProxy)
T002 ─┼──> T003                  │
      │                          │
      ├──> T004 [P] ──> T009 [P] │
      ├──> T005 [P] ──> T010 [P] │
      ├──> T006 [P] ──> T011 [P] │
      │                          │
      ├──> T008                  │
      │                          │
T013 ──> T014 ──> T015 (all edit AuthxCodegen — sequential) ──> T016 ──> T017 ──> T018 (all edit AuthxCodegenTest — sequential)
      │
      └──> T019 (phase gate) ──> T020 (commit segment 1)
                                    │
                                    ▼
                                 T021 ──┬──> T022 [P]
                                        ├──> T023 [P]
                                        ├──> T024 [P]
                                        ├──> T025 [P]
                                        ├──> T026 [P]
                                        ├──> T027 [P]
                                        └──> T028 [P]
                                               │
                                               ▼
                                         T029 (phase gate) ──> T030 (commit segment 2)
                                                                  │
                                                                  ▼
                                                              T031, T032 (sequential, different files)
                                                                  │
                                                                  ▼
                                                              T033 [P]
                                                                  │
                                                                  ▼
                                                              T034 (phase gate + commit segment 3)
                                                                  │
                                                                  ▼
                                                              T035 (final verification)
```

### Parallel task groups `[P]`

| Group | Tasks | Why parallel |
|---|---|---|
| G1 (after T002) | T004, T005, T006 | Three distinct SDK files, no shared state |
| G2 (after T001, T002) | T007 + T008 work in same file (`TypedHandle.java`) → **sequential** — T008 runs immediately after T007 |
| G3 (after T004/T005/T006) | T009, T010, T011 | Three distinct test files |
| G4 (after T001+T007) | T012 | Independent |
| G5 (after T021) | T022, T023, T024, T025, T026, T027, T028 | Seven different files, no shared state; T022 tests schema package, T023–T028 touch service package |

(T016, T017, T018 intentionally NOT marked `[P]` — all three edit `AuthxCodegenTest.java`. Sequential execution avoids merge conflicts.)
(T007, T008 intentionally NOT both marked `[P]` — both edit `TypedHandle.java`. T007 `[P]` can run concurrently with T004/T005/T006/T012 (different files); T008 runs sequentially after T007.)

**[P] execution note for parallel agents (per `.claude/known-mistakes.md` §"Parallel agents create nested package directories"):** when dispatching agents for G3 or G5, supply **absolute file paths** in the prompt and instruct the agent to verify directory layout with `find` before committing. Do not rely on relative paths.

---

## Spec Traceability

| Requirement | Tasks |
|---|---|
| req-1 (Schema.java codegen) | T013, T016, T021, T022 |
| req-2 (Xxx.java slim — no TYPE) | T014, T017, T021 |
| req-3 (ResourceTypes.java deletion) | T015, T018, T021 |
| req-4 (ResourceType un-final) | T002, T003 |
| req-5 (on(ResourceType) entry) | T006, T011 (verification; base path pre-existing per plan §Already implemented) |
| req-6 (typed sub-rel grant/revoke) | T004, T005, T006, T009, T010, T011 |
| req-7 (typed check/lookup) | T007, T008 (verification; most pre-existing per plan §Already implemented) |
| req-8 (checkAll(PermissionProxy)) | T001, T007, T012 |
| req-9 (service migration) | T023, T024, T025, T026, T027, T028 |
| req-10 (docs) | T031, T032, T033, T034 |

Every spec requirement is covered by at least one task.

---

## Notes for executing agent

1. **Follow `plan.md` verbatim** — each task there has exact code, commands, and expected output. Do not improvise.
2. **Commit after every task** that produces tracked changes (except T019, T029 which are gate-only). Segment commits (T020, T030, T034) aggregate with descriptive messages.
3. **No decision-cache changes.** Per CLAUDE.md + ADR 2026-04-18.
4. **Logging uses `java.lang.System.Logger`.** Any new log statement follows existing pattern; do not pull in SLF4J directly.
5. **No hard-coded business type names** (`"organization"`, `"document"`, etc.) inside SDK core (`src/main/java/com/authx/sdk/`). All resource-type knowledge enters via `ResourceType<R,P>` parameter.
6. **Fail-fast on TYPE field references outside test-app schema package** — after T021, the only places `Xxx.TYPE` may appear are zero (all services migrated + schema files regenerated without the field). T029 grep check enforces this.
