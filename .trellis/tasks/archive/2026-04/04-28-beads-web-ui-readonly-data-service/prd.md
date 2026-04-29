# Beads Web UI: Read-Only Beads Data Service

## Goal

Implement the read-only data service that turns Beads CLI JSON into stable UI data models.

## Scope

* Load issue list from `bd --json list`.
* Load ready queue from `bd --json ready`.
* Load status definitions from `bd --json statuses`.
* Load issue detail from `bd --json show <id>`.
* Normalize Beads metadata, including `metadata.trellis_task`.
* Surface command, parse, and missing-data failures as structured API errors.

## Acceptance Criteria

* [x] Issue list endpoint returns stable sorted issue summaries.
* [x] Ready queue endpoint distinguishes ready tasks from merely open tasks.
* [x] Detail endpoint includes parent, children, dependencies, dependents, comments count, labels, and Trellis metadata when present.
* [x] Closed/deferred/blocked statuses preserve their Beads names instead of being remapped incorrectly.
* [x] Tests cover normal JSON, envelope JSON, empty data, malformed JSON, and Beads command failure.
* [x] The service remains read-only.

## Verification

* `pnpm test` passed in `/Users/cses-38/workspace/beads-web-ui` with 9 tests.
* `pnpm lint` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `pnpm build` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `GET /api/statuses` returned Beads status definitions including `open`, `in_progress`, `blocked`, `deferred`, `closed`, `pinned`, and `hooked`.
* `GET /api/issues` returned stable issue summaries with Trellis metadata.
* `GET /api/ready` returned an empty list while the current task was in progress, preserving Beads ready semantics.
* `GET /api/issues/authcses-sdk-cwm.2` returned detail data with parent, dependencies, dependents, labels, comments, and metadata fields.

## Dependencies

* Depends on API contract and local server scaffold.
