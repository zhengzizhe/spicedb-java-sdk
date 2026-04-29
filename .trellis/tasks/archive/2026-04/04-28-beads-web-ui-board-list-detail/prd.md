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

* [x] A developer can open the app and see Beads issues without using the CLI.
* [x] Board columns reflect Beads statuses from the backend, not hard-coded task.json states.
* [x] List filtering supports status, assignee, priority, label, and text search.
* [x] Selecting an issue opens details without losing current filters.
* [x] Trellis metadata is visibly separated from raw Beads metadata.
* [x] UI tests cover board render, list filtering, detail selection, empty state, and backend error state.

## Verification

* `pnpm test` passed in `/Users/cses-38/workspace/beads-web-ui` with 14 tests.
* `pnpm lint` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `pnpm build` passed in `/Users/cses-38/workspace/beads-web-ui`.
* The running dev server returned `HTTP/1.1 200 OK` for `/`.
* `GET /api/issues/authcses-sdk-cwm.3` returned detail data consumed by the UI for the selected task.

## Dependencies

* Depends on read-only Beads data service.
