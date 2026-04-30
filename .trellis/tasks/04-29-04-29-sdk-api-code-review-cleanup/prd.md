# review: SDK API code cleanup

## Goal

Review the current SDK public API cleanup changes and directly fix code that is
messy, hard to read, poorly named, inconsistent across docs/tests/demo, or
needlessly inefficient. The cleanup should keep the external API crisp and make
the implementation easier to maintain without broad unrelated refactors.

## What I Already Know

* The user wants a code review task that finds and fixes issues, not only a
  written review.
* The review should focus on messy or unattractive code, API design clarity,
  and performance-sensitive patterns such as excessive looping.
* The current working tree already contains the legacy-business-API cleanup,
  listener-stage redesign, fail-fast validation, tests, demo changes, and spec
  updates.
* `README.md` and `README_en.md` are already deleted in the working tree; this
  review task should not restore or modify them unless directly required.

## Assumptions

* Scope is the SDK API cleanup diff currently in the working tree.
* "Design model" means using clearer staged API/design-pattern style where it
  improves public API boundaries, not adding heavyweight abstractions.
* Performance cleanup should remove avoidable repeated work and noisy loops,
  but not replace straightforward linear validation where it is necessary and
  cheap.

## Requirements

* Review changed public API classes for naming, stage boundaries, and API
  consistency.
* Fix obvious messy or broken code found during review.
* Prefer small helper methods and staged types where they improve clarity.
* Avoid adding large loops, nested loops, or repeated scans on hot paths.
* Keep examples, tests, and bundled guide aligned with the public API.
* Preserve explicit Java local variable types; do not introduce `var`.

## Acceptance Criteria

* [x] No stale references to removed public API names remain in SDK code,
  tests, demo, guide, or backend spec.
* [x] Listener-stage API uses the intended naming and return types consistently.
* [x] Review fixes at least the concrete problems found in changed code.
* [x] `./gradlew test` passes.
* [x] Scans for old API names and Java `var` declarations pass.

## Definition Of Done

* Tests pass locally.
* Docs/spec examples match source signatures.
* Findings and fixes are summarized to the user.

## Out Of Scope

* Reintroducing or rewriting the deleted READMEs.
* Large architecture rewrites outside the changed SDK API cleanup surface.
* Adding new third-party runtime dependencies.

## Technical Notes

* Relevant specs: `.trellis/spec/backend/quality-guidelines.md`,
  `.trellis/spec/backend/directory-structure.md`, and
  `.trellis/spec/guides/approach-evaluation-thinking-guide.md`.
* Initial review will inspect current `git diff`, focused SDK API classes,
  tests, demo, and guide.
