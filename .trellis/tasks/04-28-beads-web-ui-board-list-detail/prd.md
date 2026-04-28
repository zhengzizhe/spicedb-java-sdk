# Beads Web UI: Board, List, and Detail Views

## Goal

Build the core read-only UI experience for daily Beads task navigation.

## Scope

* Board view grouped by Beads status.
* Dense list view with search and filters.
* Detail panel or detail route for selected issue.
* Display priority, assignee, owner, labels, status, dates, and blocked/ready indicators.
* Display Trellis folder path, PRD presence, branch/base branch, commit, PR URL, and related files when present.
* Provide empty, loading, and error states.

## Acceptance Criteria

* [ ] A developer can open the app and see Beads issues without using the CLI.
* [ ] Board columns reflect Beads statuses from the backend, not hard-coded task.json states.
* [ ] List filtering supports status, assignee, priority, label, and text search.
* [ ] Selecting an issue opens details without losing current filters.
* [ ] Trellis metadata is visibly separated from raw Beads metadata.
* [ ] UI tests cover board render, list filtering, detail selection, empty state, and backend error state.

## Dependencies

* Depends on read-only Beads data service.

