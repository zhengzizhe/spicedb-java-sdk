# Centralize SDK ref conversion helpers

## Goal

Create package-private conversion/validation helpers for repeated SDK internal
work: parsing subject refs, wrapping typed ids, extracting relation and
permission names, and fail-fast empty input checks.

## Scope

* Affected areas: `TypedHandle`, `DynamicHandle`, `TypedResourceEntry`,
  `DynamicResourceEntry`, `TypedFinder`, `DynamicFinder`,
  `CrossResourceBatchBuilder`, `WriteFlow`, and action builders where useful.
* Helpers should stay internal/package-private.
* Preserve current public API signatures.

## Acceptance Criteria

* [x] Repeated conversion code is centralized without adding public API.
* [x] Empty/null parameter behavior remains fail-fast.
* [x] No broad behavior changes to write/check/lookup terminals.
* [x] Focused unit tests updated only where helper extraction changes edge
  behavior.
* [x] `./gradlew test` passes.

## Notes

Run before write-plan extraction so write/batch builders can reuse shared
conversion helpers.
