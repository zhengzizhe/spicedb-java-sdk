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

### GUIDE-1 `[open]` · Jar-bundled `META-INF/authx-sdk/GUIDE.md` is severely out of date

**Found**: 2026-04-23 hygiene pass
**Location**: `src/main/resources/META-INF/authx-sdk/GUIDE.md` (688 lines,
shipped inside the published jar as authoritative AI-agent reference)
**Details**: Multiple fundamentally wrong sections:
- **Installation** (lines 36-56): wrong Maven coordinates
  (`com.authx:authx-sdk` — actual is `io.github.authxkit:authx-spicedb-sdk`),
  wrong version (1.0.0 → 2.0.1), and references nonexistent artifacts
  `authx-sdk-typed` and uses `authx-sdk-redisson` where current module is
  `authx-spicedb-sdk-redisson`.
- **Schema validation** (lines 393-397): claims "Client-side schema
  validation was removed on 2026-04-18" — actually **restored 2026-04-21**
  as metadata-only (see ADR 2026-04-18 Addendum). This is the opposite of
  current behavior.
- **Section 11 — Typed Constants (sdk-typed)**: references a `sdk-typed`
  submodule that does not exist (codegen is in main SDK, produces nested
  `Document.Rel.EDITOR` not flat `Document.EDITOR`).
- **Section 4 API examples**: uses `doc.grant("editor").to("alice")`-style
  immediate-write calls and `doc.batch().grant().to().execute()` — needs
  reconciliation with current `WriteFlow`-based typed write path and the
  untyped `action/` chain.
- **Section 10 Transport Architecture**: describes the post-L1 layer stack
  correctly but misses that `PolicyAwareConsistencyTransport` and
  `ResilientTransport` order is codified in builder, not arbitrary.

**Resolution direction**: Rewrite as a fresh spec. This is the canonical
AI-agent reference so accuracy matters more than for README. Consider
whether to keep a jar-bundled version at all vs. linking to an online
canonical source (the bundled copy will always drift).

**Interim mitigation**: Consider adding a top-of-file `> **Note**: this
guide was last fully reviewed for a pre-2.0 release; see CHANGELOG.md for
current API shapes.` banner before 2.0.1 publish to warn readers.

---

### README-1 `[open]` · Two README sections cite non-existent top-level API

**Found**: 2026-04-23 hygiene pass
**Location**: `README.md` lines 83-106, `README_en.md` lines 69-95
**Details**: The "权限检查" / "授权 / 撤权" (Chinese) and "Permission checks" /
"Grant / Revoke" (English) sections use top-level convenience methods on
`AuthxClient` that do not exist:
- `client.check(type, id, perm, subject)`
- `client.checkAll(type, id, subject, perm...)`
- `client.grant(type, id, rel, subject)`
- `client.grantToSubjects(...)`
- `client.revoke(type, id, rel, subject)`
- `client.revokeAll(...)`

`grep -n "public.*\\s(check|grant|revoke|checkAll|grantToSubjects|revokeAll)\\s*\\(" AuthxClient.java` → zero matches.
Users copying these examples hit compile errors.

**Resolution direction**: Either rewrite both sections to use the real
API (`client.on(...)` + TypedHandle chain, or untyped `client.on(string).resource(...)`),
or add thin top-level convenience methods on `AuthxClient` if the ergonomics
are considered worth it. Prefer rewriting the docs; top-level methods would
be a 5th grant entry point (see API-1).

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

### WriteFlow-1 `[open]` · No compile-time guard against forgotten `.commit()`

**Found**: ADR 2026-04-22 (Open Questions, Option A)
**Details**: `WriteFlow` returned from `TypedHandle.grant(R)` / `.revoke(R)`
must be terminated with `.commit()` or the write is silently dropped.
ADR 2026-04-22 explicitly says "by code review / lint", but no lint is
actually wired in `build.gradle` today (no ErrorProne, SpotBugs, or
`@CheckReturnValue` usage anywhere in the repo).

**Resolution direction**: Planned in `specs/2026-04-24-2.0-convergence`
block A. Wire ErrorProne + annotate
`TypedHandle.grant/revoke` with `@CheckReturnValue` and
`WriteCompletion.listener/listenerAsync` with `@CanIgnoreReturnValue`.
Cost: ~1 dev-day.

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
