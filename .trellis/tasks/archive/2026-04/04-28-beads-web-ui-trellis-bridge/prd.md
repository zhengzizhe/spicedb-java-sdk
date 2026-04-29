# Beads Web UI: Trellis Execution Bridge

## Goal

Connect Beads issues to Trellis task folders and lifecycle actions from the Web UI.

## Scope

* Show whether a Beads issue has a linked Trellis folder.
* Create or claim a Trellis task folder for an existing Beads issue.
* Start/archive Trellis tasks through existing Trellis scripts.
* Display PRD, implement/check context file presence, current task, and task hierarchy.
* Keep Beads metadata and Trellis folder state synchronized.

## Acceptance Criteria

* [x] A Beads issue can be linked to a Trellis folder without creating task.json.
* [x] Starting a task updates Beads status and Trellis current-task state consistently.
* [x] Archiving a task closes Beads and moves the folder to archive.
* [x] The UI shows missing PRD/context files as actionable warnings.
* [x] Tests cover claim, start, archive, missing folder, and Beads command failure.

## Verification

* Product commit: `50710d9 Add Trellis execution bridge`.
* `pnpm test` passed in `/Users/cses-38/workspace/beads-web-ui` with 43 tests.
* `pnpm lint` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `pnpm build` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `git diff --check` passed.
* Real smoke created issue `authcses-sdk-ix8`, linked it to a `.bead`-only Trellis folder with no `task.json`, started it, archived it, and confirmed Beads closed it.
* Smoke archive `.trellis/tasks/archive/2026-04/04-29-beads-web-ui-trellis-bridge-smoke-20260429112538` validates successfully and `.bead` points to `authcses-sdk-ix8`.
* `.trellis/.current-task` was restored to `.trellis/tasks/04-28-beads-web-ui-trellis-bridge` after smoke.

## Dependencies

* Depends on core write mutations.
