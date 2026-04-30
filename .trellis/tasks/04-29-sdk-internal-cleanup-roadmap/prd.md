# SDK internal cleanup roadmap

## Goal

Reduce SDK internal complexity after the public business API cleanup. The goal
is not a large rewrite; it is a sequence of small refactors that make fluent
API stages, conversion helpers, write planning, and legacy internals easier to
reason about while preserving current external behavior.

## Problem

The SDK currently works and tests pass, but internal code still has migration
artifacts:

* Conversion logic is scattered: `String -> SubjectRef`,
  `ResourceType + id -> type:id`, enum relation/permission names, and list
  validation appear in several classes.
* Fluent stage classes mix API state, validation, model conversion, update
  assembly, and transport execution.
* Typed and dynamic APIs have parallel implementations that can drift.
* Some compatibility holders remain inside root SDK package even though they
  are no longer public business API.

## Principles

* Preserve public API shape unless a subtask explicitly calls out a naming
  correction.
* Prefer internal helpers and package-private stage/plan classes over new
  public abstractions.
* Do not remove necessary linear loops. Instead, centralize repeated loops,
  pre-size collections, and avoid repeated scans on hot paths.
* Keep behavior locked by existing tests before moving code.
* Avoid unrelated formatting churn.

## Subtasks In Order

1. `04-29-sdk-ref-conversion-helpers`
   Centralize conversion and fail-fast helpers first so later tasks can reuse
   them instead of copying local helper methods.

2. `04-29-sdk-write-plan-extraction`
   Extract write/update assembly from `WriteFlow` and
   `CrossResourceBatchBuilder` into package-private planning helpers.

3. `04-29-sdk-typed-dynamic-unification`
   Reduce duplicated typed/dynamic finder and handle internals after conversion
   helpers and write planning are stable.

4. `04-29-sdk-legacy-internal-quarantine`
   Isolate or delete obsolete internal compatibility holders once new internals
   no longer depend on their old shape.

5. `04-29-sdk-internal-cleanup-verification`
   Final audit: tests, old API scans, docs/spec consistency, and performance
   review.

## Acceptance Criteria

* [x] Each subtask has a focused PRD and passes its local checks.
* [x] Public examples still use `client.on(...).select(...)`,
  `lookupResources(...)`, `lookupSubjects(...)`, `WriteListenerStage`, and
  `CompletableFuture<WriteCompletion>` where appropriate.
* [x] No `client.resource(...)`, `client.lookup(...)`, `commitAsync`,
  `listenerAsync`, or `WriteCompletion.listener(...)` reappears.
* [x] `./gradlew test` passes after the full sequence.
* [x] No Java `var` declarations are introduced in `src` or `test-app`.

## Out Of Scope

* Rewriting transports, gRPC mapping, resilience, telemetry, or schema loading.
* Reintroducing deleted public API.
* Adding third-party dependencies.
