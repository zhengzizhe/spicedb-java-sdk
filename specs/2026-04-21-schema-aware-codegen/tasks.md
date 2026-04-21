# Schema-Aware Codegen Tasks

> **For agentic workers:** Use authx-executing-plans. Mark tasks `[X]` as they complete. Full step-by-step code lives in `plan.md` — this file is the execution checklist only.

Traceability tags `[SR:req-N]` reference requirement IDs in `spec.md`. Tasks marked `[P]` are parallelizable within the same phase once their dependencies are satisfied.

---

## Phase 0: Setup

- [X] T001 Create `feature/pr-a-codegen-restore` from `main@4a27a84`; record baseline `./gradlew test -x :test-app:test -x :cluster-test:test` is green — git state only

---

## Phase 1: PR-A — Restore codegen + SDK schema read path

- [X] T002 [SR:req-4] `SubjectType` record (`type`, `relation`, `wildcard`) with `of()` / `wildcard()` / `parse()` / `toRef()` factories + unit test — `src/main/java/com/authx/sdk/model/SubjectType.java`, `src/test/java/com/authx/sdk/model/SubjectTypeTest.java`
- [X] T003 [P] [SR:req-4] Add `default List<SubjectType> subjectTypes()` to `Relation.Named` + interface-default test — `src/main/java/com/authx/sdk/model/Relation.java`, `src/test/java/com/authx/sdk/model/RelationSubjectTypesDefaultTest.java`
- [X] T004 [SR:req-2] `SchemaCache` metadata-only scaffolding — `DefinitionCache(relations, permissions, relationSubjectTypes)`, `CaveatDef(name, parameters, expression, comment)`, atomic-reference swap, refresh cooldown, read accessors — `src/main/java/com/authx/sdk/cache/SchemaCache.java`, `src/test/java/com/authx/sdk/cache/SchemaCacheTest.java`
- [X] T005 [SR:req-1] `SchemaLoader` — live `ExperimentalReflectSchema` gRPC call, maps proto `ExpRelationSubjectType` → `SubjectType`, writes both definition + caveat maps; non-fatal on `UNIMPLEMENTED`; tested via gRPC in-process channel — `src/main/java/com/authx/sdk/transport/SchemaLoader.java`, `src/test/java/com/authx/sdk/transport/SchemaLoaderTest.java`
- [X] T006 [P] [SR:req-3] `SchemaClient` public wrapper + `AuthxClient.schema()` accessor — `src/main/java/com/authx/sdk/SchemaClient.java`, `src/main/java/com/authx/sdk/AuthxClient.java`, `src/test/java/com/authx/sdk/SchemaClientTest.java`
- [X] T007 [SR:req-5] Builder wiring — `AuthxClientBuilder.loadSchemaOnStart(boolean)` + calls `SchemaLoader.load()` in `build()` then hands `SchemaCache` into `SchemaClient`; non-fatal on load failure — `src/main/java/com/authx/sdk/AuthxClientBuilder.java`, `src/test/java/com/authx/sdk/AuthxClientBuilderSchemaTest.java`
- [X] T008 [SR:req-6] `AuthxCodegen` — restore historical codegen + upgrade `emitTypeClass` to emit schema-aware `Rel` enum with `subjectTypes()` override; emits `XxxType.java`, `XxxCaveat.java`, `ResourceTypes.java`, `Caveats.java`; javadoc scrubbed of deleted `.toUser()` API references — `src/main/java/com/authx/sdk/AuthxCodegen.java`, `src/test/java/com/authx/sdk/AuthxCodegenTest.java`
- [X] T009 [SR:req-7] Regenerate `test-app/schema/*.java` via `RegenerateTestAppSchemaTool` (checked-in `@Disabled` JUnit tool) — produces new `Department.java`, `Document.java`, `Folder.java`, `Group.java`, `Organization.java`, `Space.java`, `User.java` (new), `ResourceTypes.java`; test-app compiles + tests unchanged — `test-app/src/main/java/com/authx/testapp/schema/*.java`, `src/test/java/com/authx/sdk/codegen/RegenerateTestAppSchemaTool.java`

**Phase 1 gate:** `./gradlew compileJava && ./gradlew test -x :test-app:test -x :cluster-test:test && ./gradlew :test-app:compileJava && ./gradlew :test-app:test` all green. PR-A opens against `main`.

---

## Phase 2: PR-B — Subject validation fail-fast

- [ ] T010 [SR:req-8] Write failing test for `SchemaCache.validateSubject(resourceType, relation, subjectRef)` covering typed match / sub-relation match / wildcard / rejects unknown type / rejects wildcard against non-wildcard declaration / fail-open on empty cache — `src/test/java/com/authx/sdk/cache/SchemaCacheValidateSubjectTest.java`
- [ ] T011 [SR:req-8] Implement `validateSubject` — throws `InvalidRelationException` with the full list of allowed subject shapes (formatted via `SubjectType.toRef()`) — `src/main/java/com/authx/sdk/cache/SchemaCache.java`
- [ ] T012 [SR:req-8] Wire nullable `SchemaCache` through `ResourceFactory` → `ResourceHandle` → `GrantAction`; call `validateSubject` per `(relation, subjectRef)` pair before `writeRelationships`; keep legacy ctor delegating with `null` — `src/main/java/com/authx/sdk/ResourceFactory.java`, `src/main/java/com/authx/sdk/ResourceHandle.java`, `src/main/java/com/authx/sdk/action/GrantAction.java`, `src/test/java/com/authx/sdk/action/GrantActionValidateSubjectTest.java`
- [ ] T013 [P] [SR:req-8] Same validation plumbing on `RevokeAction` — `src/main/java/com/authx/sdk/action/RevokeAction.java`, `src/test/java/com/authx/sdk/action/RevokeActionValidateSubjectTest.java`
- [ ] T014 [P] [SR:req-8] Same validation plumbing on `BatchGrantAction` + `BatchRevokeAction` — `src/main/java/com/authx/sdk/action/BatchGrantAction.java`, `src/main/java/com/authx/sdk/action/BatchRevokeAction.java`, `src/test/java/com/authx/sdk/action/BatchActionsValidateSubjectTest.java`
- [ ] T015 [P] [SR:req-9] Typed-chain inheritance smoke test — `TypedGrantAction` / `TypedRevokeAction` call sites still get validated through underlying `GrantAction` / `RevokeAction` — `src/test/java/com/authx/sdk/TypedChainValidationSmokeTest.java`

**Phase 2 gate:** `./gradlew test -x :test-app:test -x :cluster-test:test` green. PR-B opens against merged PR-A.

---

## Phase 3: PR-C — Single-type inference + typed overloads + test-app migration

- [ ] T016 [SR:req-10] `SubjectType.inferSingleType(List<SubjectType>)` static helper — returns `Optional<SubjectType>` containing the lone non-wildcard element, or empty when zero, multiple, or wildcard-only — `src/main/java/com/authx/sdk/model/SubjectType.java`, `src/test/java/com/authx/sdk/model/SubjectTypeTest.java` (extend existing)
- [ ] T017 [SR:req-10] `GrantAction.to(String id)` — if `id` contains `:` treat as canonical; else inference via schema; throw `IllegalArgumentException` with a clear message naming the relation + allowed types when inference ambiguous — `src/main/java/com/authx/sdk/action/GrantAction.java`, `src/test/java/com/authx/sdk/action/GrantActionInferenceTest.java`
- [ ] T018 [SR:req-11] `GrantAction.to(ResourceType, String id)` typed overload — `src/main/java/com/authx/sdk/action/GrantAction.java`, `src/test/java/com/authx/sdk/action/GrantActionTypedToTest.java`
- [ ] T019 [SR:req-11] `GrantAction.to(ResourceType, String id, String relation)` subject-relation overload — `src/main/java/com/authx/sdk/action/GrantAction.java`, `src/test/java/com/authx/sdk/action/GrantActionTypedToSubRelationTest.java`
- [ ] T020 [SR:req-11] [SR:req-13] `GrantAction.toWildcard(ResourceType)` + `to(ResourceType, Iterable<String>)` — `src/main/java/com/authx/sdk/action/GrantAction.java`, `src/test/java/com/authx/sdk/action/GrantActionToWildcardTest.java`, `src/test/java/com/authx/sdk/action/GrantActionToIterableTest.java`
- [ ] T021 [P] [SR:req-12] [SR:req-13] `RevokeAction.from` mirror overloads — `from(String)`, `from(ResourceType, String)`, `from(ResourceType, String, String)`, `fromWildcard(ResourceType)`, `from(ResourceType, Iterable<String>)` — `src/main/java/com/authx/sdk/action/RevokeAction.java`, `src/test/java/com/authx/sdk/action/RevokeActionTypedFromTest.java`
- [ ] T022 [P] [SR:req-12] [SR:req-13] `CheckAction.by(ResourceType, String)` + `byAll(ResourceType, Iterable<String>)` + matching `TypedCheckAction` overloads — `src/main/java/com/authx/sdk/action/CheckAction.java`, `src/main/java/com/authx/sdk/TypedCheckAction.java`, `src/test/java/com/authx/sdk/action/CheckActionTypedByTest.java`
- [ ] T023 [P] [SR:req-12] [SR:req-13] `WhoBuilder` + `LookupQuery.findBy(ResourceType, id)` + `TypedFinder` mirror typed overloads — `src/main/java/com/authx/sdk/action/WhoBuilder.java`, `src/main/java/com/authx/sdk/LookupQuery.java`, `src/main/java/com/authx/sdk/TypedFinder.java`, `src/test/java/com/authx/sdk/LookupTypedTest.java`
- [ ] T024 [P] [SR:req-13] `Iterable<String>` overload matrix across Grant/Revoke/Check/Lookup — sweep for missing variants, cover with parameterised tests — `src/test/java/com/authx/sdk/action/IterableOverloadMatrixTest.java`
- [ ] T025 [SR:req-14] Migrate `DocumentSharingService` per spec appendix B — replace hand-built `SubjectRef.of(...)` / `"user:" + id` / `"group:" + id` call sites with `.to(id)` inference + `.to(Group.TYPE, id)` typed forms — `test-app/src/main/java/com/authx/testapp/service/DocumentSharingService.java`, `test-app/src/test/java/com/authx/testapp/service/DocumentSharingServiceTest.java`

**Phase 3 gate:** `./gradlew test` (including test-app) green. PR-C opens against merged PR-B.

---

## Phase 4: PR-D — Typed caveat

- [ ] T026 [SR:req-15] Add `caveat ip_allowlist(cidrs list<string>, client_ip string) { ... }` block + one relation referencing it — `deploy/schema.zed`
- [ ] T027 [SR:req-15] Regenerate test-app schema — new `IpAllowlist.java` (with `ref()` factory + `context(...)` builder) + `Caveats.java` constants — `test-app/src/main/java/com/authx/testapp/schema/IpAllowlist.java`, `test-app/src/main/java/com/authx/testapp/schema/Caveats.java`
- [ ] T028 [SR:req-15] `ConditionalShareService` demo + test using `IpAllowlist.ref(...)` + `IpAllowlist.context(...)` — `test-app/src/main/java/com/authx/testapp/service/ConditionalShareService.java`, `test-app/src/test/java/com/authx/testapp/service/ConditionalShareServiceTest.java`

**Phase 4 gate:** `./gradlew test` green. PR-D opens against merged PR-C.

---

## Dependencies

- T002 → T003 (Relation.Named default uses `SubjectType`).
- T002 → T004 (`DefinitionCache` uses `SubjectType`).
- T004 → T005 (loader writes into cache).
- T004 → T006 (public wrapper reads cache).
- T005, T006 → T007 (builder glues both).
- T002, T006 → T008 (codegen emits `SubjectType` literals).
- T008 → T009 (regenerate uses codegen).
- T010 → T011 (TDD: failing test before impl).
- T011 → T012 (action validation calls `validateSubject`).
- T012 → T013, T014, T015 (share the SchemaCache plumbing pattern; parallelizable after T012).
- T016 → T017 (`to(String id)` uses `inferSingleType`).
- T017 → T018, T019, T020 (share the `GrantAction` constructor/context shape — sequential in same file to avoid merge churn).
- T020 → T021, T022, T023 (mirror the pattern already locked in on Grant).
- T021, T022, T023 → T024 (matrix sweep runs after primaries exist).
- T017–T024 → T025 (migration consumes the new APIs).
- T026 → T027 → T028 (schema → regen → service).

## Parallelizable tasks [P]

| Phase | Parallelizable batch | After |
|---|---|---|
| 1 | T003 with T004 | T002 |
| 1 | T005 with T006 | T004 |
| 2 | T013, T014, T015 | T012 |
| 3 | T021, T022, T023 | T020 |
| 3 | T024 | T021 + T022 + T023 |

---

## Spec coverage

| Requirement | Task(s) | Status |
|---|---|---|
| req-1: SchemaLoader live gRPC | T005 | Covered |
| req-2: SchemaCache metadata-only | T004 | Covered |
| req-3: SchemaClient public wrapper | T006 | Covered |
| req-4: SubjectType record + Relation.Named.subjectTypes() | T002, T003 | Covered |
| req-5: Builder wiring + non-fatal load | T007 | Covered |
| req-6: AuthxCodegen restored + schema-aware enums | T008 | Covered |
| req-7: Regenerated test-app schema | T009 | Covered |
| req-8: Runtime subject validation | T010, T011, T012, T013, T014 | Covered |
| req-9: Typed chain inherits validation | T015 | Covered |
| req-10: `.to(id)` single-type inference | T016, T017 | Covered |
| req-11: Typed `to(ResourceType, id)` overloads | T018, T019, T020 | Covered |
| req-12: check/revoke/lookup symmetry | T021, T022, T023 | Covered |
| req-13: `Iterable<String>` overloads | T020, T021, T022, T023, T024 | Covered |
| req-14: DocumentSharingService migration | T025 | Covered |
| req-15: Typed caveat | T026, T027, T028 | Covered |
