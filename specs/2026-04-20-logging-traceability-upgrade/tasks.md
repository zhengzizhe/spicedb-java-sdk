# Logging & Traceability Upgrade — Task Checklist

Artifact chain: `spec.md` (what/why) → `plan.md` (how, detailed) → `tasks.md` (this file, execution).

Legend: `[P]` parallelizable within phase — `[SR:req-N]` spec requirement trace.

---

## Phase 0: Setup

- [X] T001 Baseline green on `feature/logging-traceability` — `(no files)`

## Phase 1: Build config

- [X] T002 [SR:req-3] Add SLF4J 2.0.13 compileOnly + testImplementation — `build.gradle`

## Phase 2: Foundation helpers (TDD, parallelizable)

- [X] T003 [P] [SR:req-1] Create `LogCtx` class + tests — `src/main/java/com/authx/sdk/trace/LogCtx.java`, `src/test/java/com/authx/sdk/trace/LogCtxTest.java`
- [X] T004 [P] [SR:req-5] Create `LogFields` class + tests — `src/main/java/com/authx/sdk/trace/LogFields.java`, `src/test/java/com/authx/sdk/trace/LogFieldsTest.java`
- [X] T005 [P] [SR:req-4, req-20, req-21, req-23] Create `Slf4jMdcBridge` class + tests — `src/main/java/com/authx/sdk/trace/Slf4jMdcBridge.java`, `src/test/java/com/authx/sdk/trace/Slf4jMdcBridgeTest.java`

## Phase 3: Transport entry MDC push + OTel span enrichment

- [X] T006 [SR:req-6] Wire `Slf4jMdcBridge.push/pop` into `InterceptorTransport` — `src/main/java/com/authx/sdk/transport/InterceptorTransport.java`
- [X] T007 [P] [SR:req-7] Additional span attributes in `InstrumentedTransport` — `src/main/java/com/authx/sdk/transport/InstrumentedTransport.java`
- [X] T008 [P] [SR:req-8, req-10] Retry attribute + event in `ResilientTransport`; retry log WARN→DEBUG — `src/main/java/com/authx/sdk/transport/ResilientTransport.java`
- [X] T009 [P] [SR:req-9] Consistency attribute in `PolicyAwareConsistencyTransport` — `src/main/java/com/authx/sdk/transport/PolicyAwareConsistencyTransport.java`

## Phase 4: Log-site mechanical wrap (LogCtx.fmt around every LOG.log)

- [X] T010 [P] [SR:req-2] action/ package log-site wrap — `src/main/java/com/authx/sdk/action/GrantCompletionImpl.java`, `RevokeCompletionImpl.java`
- [X] T011 [SR:req-2, req-10] transport/ package log-site wrap + 2 WARN→DEBUG in GrpcTransport — `src/main/java/com/authx/sdk/transport/{RealOperationChain,RealWriteChain,RealCheckChain,GrpcTransport,TokenTracker,ResilientTransport}.java`
- [X] T012 [P] [SR:req-2] lifecycle/ + telemetry/ + event/ + builtin/ log-site wrap — 4 files
- [X] T013 [SR:req-2] root + internal inline log-site wrap — `AuthxClient.java`, `AuthxClientBuilder.java`, `internal/SdkInfrastructure.java`

## Phase 5: WARN+ suffix enrichment

- [X] T014 [SR:req-13, req-14] Append `LogFields.suffix*(...)` to WARN+ sites with resource context — ~6 files

## Phase 6: Integration test

- [X] T015 [SR:req-1, req-2, req-6, req-13] End-to-end log+MDC+span assertion — `src/test/java/com/authx/sdk/trace/LogEnrichmentIntegrationTest.java`

## Phase 7: Documentation

- [X] T016 [P] [SR:req-15] New guide — `docs/logging-guide.md`
- [X] T017 [P] [SR:req-16, req-17, req-18] README.md + README_en.md + CLAUDE.md + META-INF/GUIDE.md updates

## Phase 8: Verification

- [X] T018 [SR:req-19..25] Full suite + downstream compile + javadoc + scope check — `(no files)`

---

## Dependencies

```
T001 (baseline)
  └─ T002 (build config, adds SLF4J)
       └─ Phase 2 (helpers, parallel)
            └─ Phase 3 (wire + span attrs)
                 └─ Phase 4 (mechanical wrap across 15 files)
                      └─ Phase 5 (suffix enrichment)
                           └─ Phase 6 (integration test)
                                └─ Phase 7 (docs, parallel)
                                     └─ Phase 8 (verify)
```

**Explicit dependencies:**
- T003 ‖ T004 ‖ T005 (helpers — disjoint files)
- T006 depends on T004+T005 (uses LogFields + Slf4jMdcBridge)
- T007 ‖ T008 ‖ T009 (span attrs — disjoint transport files)
- T010 ‖ T012 ‖ T013 (log wraps — disjoint files)
- T011 serial-after T008 (both edit ResilientTransport)
- T014 depends on T010..T013 (adds suffix to already-wrapped sites)
- T015 depends on all wraps + suffix
- T016 ‖ T017 (docs — disjoint files)
- T018 depends on everything

## Parallelizable batches

- **Batch A (Phase 2):** T003 ‖ T004 ‖ T005
- **Batch B (Phase 3):** T007 ‖ T008 ‖ T009 (plus T006 which can run in parallel after T005)
- **Batch C (Phase 4):** T010 ‖ T012 ‖ T013, then T011 (serial with T008)
- **Batch D (Phase 7):** T016 ‖ T017

## Notes for the executing-plans session

- **Commit per task or per small batch** — each task ends in a commit whose subject references `[SR:req-N]` IDs.
- **Message body text must not change** (req-22). Log wrap is purely `LOG.log(L, "msg", args)` → `LOG.log(L, LogCtx.fmt("msg", args))`. Any temptation to "also improve wording" must be resisted; push that change to a separate PR.
- **Level changes are 3 sites only.** Do not downgrade other WARN sites on your own — req-10's criteria listed in plan.md must be applied strictly.
- **Public API gate:** after each phase, run `./gradlew :test-app:compileJava :cluster-test:compileJava`. Downstream modules do not depend on `com.authx.sdk.trace.*` — they must stay green.
- **Full suite before Phase 7:** `./gradlew :test -x :test-app:test -x :cluster-test:test` should be green before writing docs.
- **Scope discipline (T018):** the diff should touch only: spec dir, `com.authx.sdk.trace/*`, 15 modified source files, 4 transport files for span/MDC, `build.gradle`, and 4 doc files.
- **Trace-id format** — 16 hex characters, last 16 of the full 32. Verify via LogCtxTest `traceIdSuffix_last16Chars` (once the integration test provides a real span).
