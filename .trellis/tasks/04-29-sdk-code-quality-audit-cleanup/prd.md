# SDK code quality audit cleanup

## Goal

Systematically review the Java SDK implementation and remove code that is
rough, stale, overly duplicated, awkwardly named, or written only to make a
feature pass locally without a coherent model. Apply focused cleanup patches
where the improvement is objective and verify the SDK still builds and tests.

## What I already know

* The user wants a long-running, one-by-one review of the SDK code.
* The review target is not cosmetic formatting alone; the focus is elegance,
  maintainability, coherent design, and avoiding performance-hostile loops or
  crude “just make it work” implementations.
* Recent work removed legacy public business APIs and deleted stale action
  builders.
* Current public fluent API should stay centered on
  `client.on(...).select(...)`, `lookupResources(...)`, `WriteFlow`, and
  `WriteListenerStage`.

## Assumptions

* Public API behavior should remain compatible with the new API surface unless
  a current API is demonstrably stale or internally inconsistent.
* Prefer small, reviewable cleanup batches over broad rewrites.
* If a finding is subjective style only, record it rather than changing it.

## Requirements

* Audit all SDK production packages under `src/main/java/com/authx/sdk`.
* Include tests and `test-app` only where stale API usage, misleading examples,
  or fragile test patterns hide production issues.
* Remove obsolete code rather than quarantine it when no current API uses it.
* Consolidate repeated conversion, validation, request-building, or fan-out
  patterns through existing helpers before adding new abstractions.
* Keep fail-fast validation for empty/null business inputs.
* Avoid performance regressions from unnecessary nested loops, repeated
  materialization, or all-result fetches where an existence/limit path exists.

## Acceptance Criteria

* [x] Produce a short audit inventory by package with each candidate classified
  as fixed, deferred, or intentionally left alone.
* [x] Apply focused cleanup patches for objective issues.
* [x] No removed/stale API names remain in production, tests, docs, or demo
  examples.
* [x] `./gradlew clean test` passes.
* [x] `git diff --check` passes.
* [x] `rg -n '\bvar\s+[A-Za-z_$]' src test-app --glob '*.java'` has no hits.

## Definition of Done

* Tests added or updated for behavior-affecting cleanup.
* Specs updated when a new design rule or cleanup decision should persist.
* Final response summarizes changed areas, skipped/deferred areas, and checks.

## Out of Scope

* Reintroducing removed cache/watch infrastructure.
* Adding new optional runtime dependencies.
* Rewriting the SDK architecture wholesale when a local cleanup is enough.
* Changing generated schema API contracts without a concrete bug.

## Technical Notes

* Applicable specs: `.trellis/spec/backend/quality-guidelines.md`,
  `.trellis/spec/backend/error-handling.md`,
  `.trellis/spec/backend/directory-structure.md`.
* Shared thinking guide: `.trellis/spec/guides/code-reuse-thinking-guide.md`.
