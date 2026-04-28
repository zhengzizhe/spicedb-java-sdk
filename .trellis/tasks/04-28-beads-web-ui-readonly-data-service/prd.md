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

* [ ] Issue list endpoint returns stable sorted issue summaries.
* [ ] Ready queue endpoint distinguishes ready tasks from merely open tasks.
* [ ] Detail endpoint includes parent, children, dependencies, dependents, comments count, labels, and Trellis metadata when present.
* [ ] Closed/deferred/blocked statuses preserve their Beads names instead of being remapped incorrectly.
* [ ] Tests cover normal JSON, envelope JSON, empty data, malformed JSON, and Beads command failure.
* [ ] The service remains read-only.

## Dependencies

* Depends on API contract and local server scaffold.

