# Write Listener API — Task Checklist

Artifact chain: `spec.md` (what/why) → `plan.md` (how, detailed) → `tasks.md` (this file, execution).

Legend: `[P]` parallelizable within phase — `[SR:req-N]` spec requirement trace.

---

## Phase 0: Setup

- [ ] T001 Verify green baseline on `feature/write-listener-api` — `(no files)`

## Phase 1: Public interfaces (blocks all)

- [ ] T002 [SR:req-1, req-2] Create sealed interfaces `GrantCompletion` + `RevokeCompletion` with `of(...)` static factory — `src/main/java/com/authx/sdk/action/GrantCompletion.java`, `src/main/java/com/authx/sdk/action/RevokeCompletion.java`

## Phase 2: Sync listener (Grant ‖ Revoke)

- [ ] T003 [P] [SR:req-7, req-10] Grant sync listener tests + impl — `src/main/java/com/authx/sdk/action/GrantCompletionImpl.java`, `src/test/java/com/authx/sdk/action/GrantCompletionTest.java`
- [ ] T004 [P] [SR:req-9, req-10] Revoke sync listener tests + impl — `src/main/java/com/authx/sdk/action/RevokeCompletionImpl.java`, `src/test/java/com/authx/sdk/action/RevokeCompletionTest.java`

## Phase 3: Async listener + exception handling (Grant ‖ Revoke)

- [ ] T005 [P] [SR:req-8, req-11] Grant async listener + swallow/log — `src/main/java/com/authx/sdk/action/GrantCompletionImpl.java`, `src/test/java/com/authx/sdk/action/GrantCompletionTest.java`
- [ ] T006 [P] [SR:req-9, req-11] Revoke async listener + swallow/log — `src/main/java/com/authx/sdk/action/RevokeCompletionImpl.java`, `src/test/java/com/authx/sdk/action/RevokeCompletionTest.java`

## Phase 4: Typed action rewire + aggregation (Grant ‖ Revoke)

- [ ] T007 [P] [SR:req-3, req-5] `TypedGrantAction` terminals return `GrantCompletion`; aggregate result; +aggregation test — `src/main/java/com/authx/sdk/TypedGrantAction.java`, `src/main/java/com/authx/sdk/action/GrantCompletion.java`, `src/test/java/com/authx/sdk/action/GrantCompletionTest.java`
- [ ] T008 [P] [SR:req-4, req-6] `TypedRevokeAction` terminals return `RevokeCompletion`; aggregate result; +aggregation test — `src/main/java/com/authx/sdk/TypedRevokeAction.java`, `src/main/java/com/authx/sdk/action/RevokeCompletion.java`, `src/test/java/com/authx/sdk/action/RevokeCompletionTest.java`

## Phase 5: Failure & back-compat tests

- [ ] T009 [SR:req-12, req-13] Write-failure short-circuit test — `src/test/java/com/authx/sdk/action/GrantCompletionTest.java`
- [ ] T010 [SR:req-14] Statement-form back-compat test — `src/test/java/com/authx/sdk/action/GrantCompletionTest.java`

## Phase 6: Verification

- [ ] T011 [SR:req-15] Verify no out-of-scope files changed — `(no files)`
- [ ] T012 Full test suite + downstream `compileJava` green — `(no files)`
- [ ] T013 README `Listeners` subsection (CN + EN) — `README.md`, `README_en.md`

---

## Dependencies

```
T001 (baseline)
  └─ T002 (interfaces)
       ├─ T003 (grant sync) ────────────┐
       │    └─ T005 (grant async)       │
       │         └─ T007 (typed grant)  │
       │              ├─ T009 (failure test)
       │              └─ T010 (statement form test)
       └─ T004 (revoke sync) ───────────┤
            └─ T006 (revoke async)      │
                 └─ T008 (typed revoke) │
                                        │
                                        ▼
                                  T011 (scope check)
                                  T012 (full suite)
                                  T013 (README)
```

**Explicit:**
- T003 depends on T002
- T004 depends on T002
- T005 depends on T003 (modifies same impl file + test file)
- T006 depends on T004
- T007 depends on T002, T005 (needs interface factory + async impl for full spec)
- T008 depends on T002, T006
- T009 depends on T007 (needs typed-chain wiring to actually throw)
- T010 depends on T007
- T011 depends on T007, T008
- T012 depends on T007, T008, T009, T010
- T013 depends on T012 (docs after code is green)

## Parallelizable batches

- **Batch A (after T002):** T003 ‖ T004 — touch disjoint files
- **Batch B (after Batch A):** T005 ‖ T006 — touch disjoint files
- **Batch C (after Batch B):** T007 ‖ T008 — touch disjoint files
- **Batch D (after Batch C):** T009, T010 — same file, run serially; T011 independent

## Sequential-only (shared-file) tasks

- T003 → T005 → T007 → T009 → T010: all touch `GrantCompletionTest.java`
- T004 → T006 → T008: all touch `RevokeCompletionTest.java`
- T005 → T007: both touch `GrantCompletionImpl.java` (T005 fills async, T007 is no-op on impl — but sequence still required for clean diff)
- T006 → T008: same story for revoke

## Notes for the executing-plans session

- **TDD is mandatory.** For every implementation task, write the failing test first, confirm the failure, then implement to pass.
- **Commit per task.** Each task ends with a commit whose subject references the `[SR:req-N]` IDs.
- **Do NOT skip the `UnsupportedOperationException` stub in T003/T004.** It's what makes T005/T006 a real TDD cycle (red → green) instead of a trivial pass-through.
- **Public API gate:** after T007 and T008, run `./gradlew :test-app:compileJava :cluster-test:compileJava` to catch any accidental downstream break.
- **Full suite before T013:** `./gradlew :test -x :test-app:test -x :cluster-test:test` must be green with zero failures before writing docs.
- **Scope discipline (T011):** if `git diff --name-only main...HEAD` shows any file outside the whitelist in the plan's T011 step, stop and reconcile — something drifted.
