# Remove L1 Cache + Watch Infrastructure — Task Checklist

Artifact chain: `spec.md` (what/why) → `plan.md` (how, detailed) → `tasks.md` (this file, execution).

Legend: `[P]` parallelizable within phase — `[SR:req-N]` spec requirement trace.

---

## Phase 0: Setup

- [ ] T001 Baseline green on `remove-l1-cache` — `(no files)`

## Phase 1: Clean in-repo downstream consumers

- [ ] T002 [P] [SR:req-22] Clean `test-app/SpiceDbConfig.java` — remove `.cache(...)` + `watchInvalidation` + `CachePolicy` refs
- [ ] T003 [P] [SR:req-22] Clean `test-app/PermissionController.java` — remove `client.cache()` + cache endpoint
- [ ] T004 [P] [SR:req-23] Clean `cluster-test/config/SdkConfig.java` — remove `.cache(...)` + `CachePolicy`
- [ ] T005 [P] [SR:req-23] Clean `cluster-test/matrix/RealMatrixClient.java` — remove `.cache(...)` + `client.cache()` + `CachePolicy`
- [ ] T006 [P] [SR:req-23] Clean `cluster-test/soak/ResourceSampler.java` — remove `client.cache()` + `CacheStats`
- [ ] T007 [P] [SR:req-23] Clean `cluster-test/resilience/R7CloseRobustnessTest.java` — remove `.cache(...)`
- [ ] T008 [P] [SR:req-23] Clean `cluster-test/resilience/R4TokenStoreTest.java` — remove `.cache(...)` + `CachePolicy`
- [ ] T009 [P] [SR:req-23] Clean `cluster-test/test/caveat/CaveatIT.java` — remove `.cache(...)`
- [ ] T010 [SR:req-23] Delete `cluster-test/test/watchstorm/WatchStormIT.java` (exists only to stress Watch)

## Phase 2: Gut SDK internal cache/Watch references (coordinated, compile may break mid-phase)

- [ ] T011 [P] [SR:req-18] Drop schema validation from `TypedGrantAction#write` + `TypedRevokeAction#write`
- [ ] T012 [SR:req-19, req-20, req-21] Remove `CacheConfig` + `cache()` method + cache fields + validation rules from `AuthxClientBuilder`
- [ ] T013 [SR:req-4, req-5, req-12] Remove `caching` field + `cache()` + `onRelationshipChange/offRelationshipChange` from `AuthxClient`
- [ ] T014 [SR:req-4] Drop `schemaCache` param from `ResourceFactory` constructor + accessor + all call sites
- [ ] T015 [P] [SR:req-14] Remove watch fields from `SdkComponents` record + builder
- [ ] T016 [P] [SR:req-17] Remove `CachePolicy` field from `ResourcePolicy` + `PolicyRegistry`
- [ ] T017 [P] [SR:req-15] Remove cache/watch event types from `SdkTypedEvent` sealed interface
- [ ] T018 [P] [SR:req-16] Remove cache/watch counters from `SdkMetrics` + `Snapshot`

## Phase 3: Delete cache/Watch transport files

- [ ] T019 [SR:req-10] Delete `transport/CachedTransport.java`
- [ ] T020 [P] [SR:req-8] Delete `transport/WatchCacheInvalidator.java`
- [ ] T021 [P] [SR:req-9] Delete `transport/WatchConnectionState.java`
- [ ] T022 [P] [SR:req-11] Delete `transport/SchemaLoader.java`

## Phase 4: Delete packages + misc files

- [ ] T023 [P] [SR:req-6] Delete `com.authx.sdk.watch/` package
- [ ] T024 [P] [SR:req-7] Delete `com.authx.sdk.dedup/` package
- [ ] T025 [P] [SR:req-1] Delete `com.authx.sdk.cache/` package (incl. `SchemaCache`)
- [ ] T026 [P] [SR:req-2, req-3, req-17] Delete `CacheHandle.java` + `internal/SdkCaching.java` + `policy/CachePolicy.java`
- [ ] T027 [P] [SR:req-13] Delete Watch-specific SPIs: `DuplicateDetector.java` + `DroppedListenerEvent.java` + `QueueFullPolicy.java`

## Phase 5: Delete cache/Watch tests

- [ ] T028 [SR:req-1] Delete `src/test/java/com/authx/sdk/cache/` directory
- [ ] T029 [P] [SR:req-1, req-6, req-10] Delete cache/Watch test files in `transport/` test package

## Phase 6: Add regression guards

- [ ] T030 [P] [SR:req-19] Create `BuilderCacheMethodRemovedTest.java` — reflection-based check that `.cache()` + watch fields/methods are gone
- [ ] T031 [P] [SR:req-6, req-8] Create `NoWatchStreamStartsTest.java` — thread enumeration asserts no `authx-sdk-watch` thread starts
- [ ] T032 [P] [SR:req-10, req-25] Create `TransportChainTest.java` — verifies chain has no `CachedTransport` but still has `CoalescingTransport`

## Phase 7: Documentation

- [ ] T033 [P] [SR:req-30] Update `CLAUDE.md` — remove `cache/`, `watch/`, `dedup/` from package structure; update tech stack line
- [ ] T034 [P] [SR:req-31, req-33] Update `README.md` — delete cache + Watch sections; add breaking-change changelog entry
- [ ] T035 [P] [SR:req-31] Update `README_en.md` — mirror CN changes + breaking-change note
- [ ] T036 [P] [SR:req-32] Update `llms.txt` — remove any cache/Watch references

## Phase 8: Verification

- [ ] T037 [SR:req-24, req-26, req-27, req-28] Full SDK test suite + downstream (`test-app`, `cluster-test`, `sdk-redisson`) compile green + javadoc clean
- [ ] T038 Scope check — `git diff --name-status` lists only expected changes

---

## Dependencies

```
T001 (baseline)
  └─ Phase 1 (T002-T010)                    [all independent or [P]]
       └─ Phase 2 (T011-T018)               [T011 [P]; T012→T013→T014 serial; T015-T018 [P]]
            └─ Phase 3 (T019-T022)          [T019 then T020/21/22 [P]]
                 └─ Phase 4 (T023-T027)     [all [P]]
                      └─ Phase 5 (T028-T029)[[P]]
                           └─ Phase 6 (T030-T032) [all [P]]
                                └─ Phase 7 (T033-T036) [all [P]]
                                     └─ Phase 8 (T037-T038)
```

**Critical serial chain inside Phase 2:** T012 → T013 → T014.
- T012 breaks compile (removes fields that AuthxClient still reads).
- T013 restores compile for AuthxClient (removes readers of those fields).
- T014 restores compile for ResourceFactory (removes the schemaCache param that AuthxClient/Builder no longer pass).

The other Phase 2 tasks (T011, T015, T016, T017, T018) touch disjoint files and can run in parallel to the T012→T013→T014 sequence.

## Parallelizable batches

- **Batch A (after T001):** T002 ‖ T003 ‖ T004 ‖ T005 ‖ T006 ‖ T007 ‖ T008 ‖ T009 — 8 disjoint consumer files
- **Batch B (after Batch A):** T010 — single deletion, trivially independent
- **Batch C (after T010):** T011 ‖ {T012→T013→T014 serial} ‖ T015 ‖ T016 ‖ T017 ‖ T018
- **Batch D (after Batch C):** T019, then T020 ‖ T021 ‖ T022
- **Batch E (after Batch D):** T023 ‖ T024 ‖ T025 ‖ T026 ‖ T027
- **Batch F (after Batch E):** T028, T029 [P]
- **Batch G (after Batch F):** T030 ‖ T031 ‖ T032
- **Batch H (docs, can overlap with G):** T033 ‖ T034 ‖ T035 ‖ T036
- **Final:** T037 → T038

## Sequential-only (shared-file) tasks

- T012, T013 both edit `AuthxClientBuilder.java` + `AuthxClient.java` respectively — different files, but T013 needs T012's field removals to happen first logically (otherwise AuthxClient still tries to pass the builder's gone fields).
- T014 (ResourceFactory) needs T013 (AuthxClient.on() / .resource() call sites) done first — otherwise ResourceFactory's constructor change would break AuthxClient's current call.
- T019 deletes `CachedTransport.java` which is referenced by `AuthxClientBuilder.buildCache()` (already removed in T012). So T019 only runs after T012.
- T025 deletes `cache/` which contains `SchemaCache`. T025 depends on T011 (TypedGrantAction/TypedRevokeAction no longer import SchemaCache) AND T014 (ResourceFactory no longer imports SchemaCache) AND T012 (Builder no longer imports SchemaCache).

## Notes for the executing-plans session

- **Commit per task or per small batch** — each task ends in a commit with a conventional-commit subject referencing the `[SR:req-N]` IDs. Batches of parallelizable [P] tasks can commit together (e.g., T002+T003, T004–T009, T015–T018).
- **Mid-phase compile failures are expected** — Phase 2's T012 intentionally breaks compile until T013+T014 complete. Do not treat that as a stop condition.
- **Public API gate:** after Phase 2 compiles green, run `./gradlew :test-app:compileJava :cluster-test:compileJava` to confirm downstream still compiles.
- **Full suite before Phase 7:** `./gradlew :test -x :test-app:test -x :cluster-test:test` should be green before writing docs.
- **Scope discipline (T038):** the diff should show zero changes outside: `src/main/java/com/authx/sdk/**`, `src/test/java/com/authx/sdk/**`, `test-app/**`, `cluster-test/**`, `README*.md`, `CLAUDE.md`, `llms.txt`, `specs/2026-04-18-remove-l1-cache/**`, `docs/adr/2026-04-18-remove-l1-cache.md`.
- **The "Performance Baseline to Record" section in spec.md** requires post-merge benchmark numbers — the ADR gets an addendum later; not part of this plan's tasks.
