# Backlog

Living registry of known-but-not-yet-addressed issues. Each item links to
where it was originally identified. Triage: `[open]` = confirmed open,
`[verify]` = flagged in an earlier pass but not re-verified against current
code, `[deferred]` = understood and intentionally postponed, `[wontfix]` =
closed with rationale.

Add items from code review, ADRs, spec post-mortems, and hygiene passes.
Resolve by linking to the PR or spec that closed it.

---

## Open

### GUIDE-1 `[partial]` · Jar-bundled `META-INF/authx-sdk/GUIDE.md` cleanup pass

**Found**: 2026-04-23 hygiene pass
**Location**: `src/main/resources/META-INF/authx-sdk/GUIDE.md` (688 lines,
shipped inside the published jar)

**Resolved in 2026-04-23 pre-release-hygiene follow-up**:
- Installation coordinates: `com.authx:authx-sdk` → `io.github.authxkit:authx-spicedb-sdk`, version 1.0.0 → 2.0.1, dropped nonexistent `authx-sdk-typed` submodule line.
- Section 7 (Schema Validation): reversed the "removed" narrative to the actual "metadata-only restored 2026-04-21" state.
- Section 11 (Typed Constants): rewrote to describe `AuthxCodegen` in the main SDK + flat descriptors, removed `sdk-typed` references.
- Section 4 & 12 & 14: replaced bare-id examples (`by("alice")`, `to("alice")` — which throw in most overloads) with canonical `"user:alice"` form; removed fictional `toSubjects()` / `fromSubjects()` methods (use `.to(...)` / `.from(...)` with canonical string); added required subject-type arg to `doc.who(...)`.
- Top-of-file banner added noting partial-update status.

**Still deferred**: full re-review of sections 5, 6, 8, 9, 10, 13, 15 against current code; decision on whether to keep a jar-bundled guide at all (the bundled copy will always drift from repo reality). Revisit as part of the 2.0-convergence spec.

---

### README-1 `[closed]` · Two README sections cited non-existent top-level API

**Found**: 2026-04-23 hygiene pass
**Closed by**: 2026-04-23 pre-release-hygiene follow-up commit. Both
`README.md` and `README_en.md` "权限检查" / "Permission Check" + "授权 /
撤权" / "Grant / Revoke" sections now use the real APIs:
`client.on("document").resource(id).check(perm).by("user:alice")` for the
untyped path, and `client.on(Document).select(id).check(Document.Perm.X).by(User, id)` / `.grant(...).commit()` for the typed path.

---

### API-1 `[open]` · Typed / untyped API surface has parallel duplication

**Found**: 2026-04-23 review
**Details**: Every user-facing action has a Typed form and an Untyped form,
plus writes have further variants via `WriteFlow` and
`CrossResourceBatchBuilder`:

- check: `TypedCheckAction` (265 lines) vs `action/CheckAction`
- checkAll: `TypedCheckAllAction` (173 lines) vs `action/CheckAllAction`
- grant: `TypedHandle.grant` (→ `WriteFlow`, 465 lines) vs
  `action/GrantAction` vs `action/BatchGrantAction` vs
  `CrossResourceBatchBuilder` (533 lines) — **4 entry points**
- revoke: 5 entry points (mirror of grant + `RevokeAllAction`)
- who: `TypedWhoQuery` vs `action/WhoBuilder` + `action/SubjectQuery`
- findBy: `TypedFinder` vs `action/RelationQuery` + `LookupQuery`

Each behavior change must be implemented in two (sometimes four) places.
No ADR documents the rationale for keeping both.

**Resolution direction**: Write an `audit.md` per-API-pair report
(planned for `specs/2026-04-24-2.0-convergence`). Decide: keep both
(document when to use which), keep one (remove the other), or make one
a thin wrapper over the other. Do not delete code until the audit
lands.

---

### WriteFlow-1 `[closed]` · Compile-time guard against forgotten `.commit()`

**Found**: ADR 2026-04-22 (Open Questions, Option A)
**Closed by**: 2026-04-23 pre-release-hygiene follow-up commit.
- `net.ltgt.errorprone` plugin 4.0.1 + `error_prone_core` 2.28.0 wired
  into `build.gradle`. All ErrorProne checks disabled except
  `CheckReturnValue` (WARN severity) — conservative rollout to avoid
  unrelated findings flooding the build.
- `WriteFlow` marked `@CheckReturnValue` at class level;
  `commit`/`commitAsync`/`pending`/`pendingCount` opt out via
  `@CanIgnoreReturnValue`. Private helpers `beginBatch`/`addSubject`
  also opt out to keep internal plumbing noise-free.
- `TypedHandle.grant(R)` / `.revoke(R)` marked `@CheckReturnValue`
  (catches "started but never chained").
- `WriteCompletion.listener(...)` / `.listenerAsync(...)` marked
  `@CanIgnoreReturnValue` (chainable but commonly called as a
  statement).
- Clean compile of main SDK + test sources + test-app; 788 SDK tests
  still pass.

**Follow-up**: consider extending the plugin to `sdk-redisson` and
`test-app` as a separate pass (low priority; those modules don't own
the `WriteFlow` surface).

---

### arch-1 `[open]` · `AuthxClientBuilder.build()` mixes many responsibilities

**Found**: `specs/2026-04-07-sdk-evaluation/spec.md` (arch-1)
**Location**: `AuthxClientBuilder.build()` — currently 165 lines
(after the L1 removal shrank it from ~200)
**Details**: Still mixes customizer application, validation, policy
resolution, channel build, transport-stack assembly, scheduler,
schema load, client wiring, and shutdown-hook registration.

**Resolution direction**: Extract ~6 private helpers:
`resolvePolicies`, `buildInfrastructure`, `loadSchemaIfEnabled`,
`assembleClient`, `registerShutdownHookIfEnabled`, keeping `build()`
as a short pipeline. Planned in `specs/2026-04-24-2.0-convergence`
block B. Cost: ~0.5 dev-day.

---

### cluster-test-1 `[open]` · cluster-test is not in CI

**Found**: 2026-04-23 review; premise of ADR 2026-04-18
**Details**: The "no client-side decision cache, rely on SpiceDB
server-side dispatch cache" decision is predicated on cluster
benchmark numbers that were produced manually. Reports under
`docs/archive/benchmark-report-*.md` dated 2026-04-08 through
2026-04-11 are the last public artifacts. There is no automated
CI job that reruns them against current main; regressions would
go unnoticed.

**Resolution direction**: GitHub Actions nightly job that runs
the `cluster-test` suite and publishes results as an artifact. Do
not fail CI on benchmark regression initially; observe for one
release cycle, then decide thresholds. Planned in
`specs/2026-04-24-2.0-convergence` block D. Cost: ~2 dev-days.

---

### schema-1 `[open]` · Schema fail-fast silently no-ops on `UNIMPLEMENTED`

**Found**: 2026-04-23 review
**Location**: `WriteFlow.validateSchemaFailFast()` (line 457) —
`if (schemaCache == null) return;`; `SchemaLoader` treats gRPC
`UNIMPLEMENTED` from older SpiceDB as non-fatal.
**Details**: Subject-type validation effectively disabled for older
SpiceDB versions, with no user-facing signal. "Will my grant catch
a typed error before RPC?" depends on the deployed SpiceDB version.
Not documented in user-facing docs.

**Resolution direction**: Either (a) emit a WARNING at `AuthxClient`
build time when `loadSchemaOnStart` succeeds but the cache ended up
empty so users know validation is off, or (b) document the behavior
prominently. Pick (a) if possible — silent degradation is the issue.
Cost: ~0.5 dev-day.

---

## Needs verification (from 2026-04-07 evaluation — 2 weeks stale)

### arch-3 `[verify]` · ResilientTransport shared breaker at `MAX_INSTANCES=1000`

**Found**: `specs/2026-04-07-sdk-evaluation/spec.md` (arch-3)
**Location**: `ResilientTransport.java:42`
**Details**: `MAX_INSTANCES = 1000` still present. Line 229
comment mentions "with proper LRU + frequency tracking" —
possibly partial resolution. Re-read the class to confirm whether
the stated multi-tenant state-loss failure mode is still reachable,
then either close or promote to `[open]`.

### arch-5 `[verify]` · `InterceptorTransport` two-model inconsistency

**Found**: `specs/2026-04-07-sdk-evaluation/spec.md` (arch-5)
**Location**: `InterceptorTransport.java`
**Details**: Evaluation claimed two interception models (chain for
check/write, hook for others). Class is 38 lines at the header;
re-read full file to confirm the inconsistency still holds, then
close or promote.

---

## Deferred

### arch-4 `[deferred]` · `CoalescingTransport.existing.join()` has no timeout

**Found**: `specs/2026-04-07-sdk-evaluation/spec.md` (arch-4)
**Location**: `CoalescingTransport.java:50`
**Details**: Code comment at line 50 reads "F12-1: use
`get(timeout, unit)` rather than `orTimeout(...).join()`" —
suggests the concern was addressed. Mark deferred pending a
focused read of the class to confirm; if confirmed resolved,
move to a closed section.

---

## Closed

_(Move items here with a link to the resolving PR / spec.)_

### arch-2 `[wontfix]` · Implicit decorator ordering (CachedTransport below PolicyConsistency)

**Closed by**: ADR 2026-04-18 (L1 cache removal deleted
`CachedTransport` entirely, dissolving the ordering constraint).
