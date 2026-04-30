# Extract relationship write planning

## Goal

Move relationship update assembly out of fluent stage classes into
package-private write planning helpers. `WriteFlow` and
`CrossResourceBatchBuilder` should read as stage/state code rather than a mix of
state machine, conversion, and update generation.

## Scope

* Introduce internal helper(s), for example `RelationshipUpdateBuilder` or
  `RelationshipWritePlan`, only if they reduce real duplication.
* Keep `WriteFlow.commit()` and `WriteListenerStage.commit()` behavior
  unchanged.
* Preserve atomic `WriteRelationships` submission.
* Centralize batch update count/capacity calculation and overflow protection.

## Acceptance Criteria

* [x] `WriteFlow` remains responsible for fluent state and listener-stage
  lifecycle, not low-level update assembly details.
* [x] `CrossResourceBatchBuilder` delegates update fan-out to a shared helper.
* [x] Necessary cartesian-product loops are centralized, pre-sized, and not
  repeated across stage classes.
* [x] Existing write-flow and batch tests pass.
* [x] `./gradlew test --tests com.authx.sdk.WriteFlowTest` passes.

## Notes

This task depends on `04-29-sdk-ref-conversion-helpers`.
