# Beads Web UI: Agent, Worktree, Review, and Preview Expansion

## Goal

Extend the Beads Web UI into an AI coding workbench after the task cockpit and Trellis bridge are stable.

## Scope

* Integrate `bd worktree` list/create/remove flows.
* Show worktree path, branch, dirty state, and associated Beads issue.
* Provide agent launch handoff commands instead of opaque browser shell execution.
* Show git diff and test/build status per task/worktree.
* Add preview URL management for dev servers.
* Capture review feedback back into Beads comments or Trellis notes.

## Acceptance Criteria

* [x] Worktree operations are safe, explicit, and reversible where possible.
* [x] Agent launch is an auditable handoff, not uncontrolled background execution.
* [x] Diff and test results are tied to a specific issue/worktree.
* [x] Preview links are displayed only when discovered or explicitly registered.
* [x] Feedback can be persisted to Beads or Trellis without losing source context.
* [x] Tests cover worktree discovery, missing worktree, diff loading failure, and preview link display.

## Verification

* Product commit: `65b0619 Add agent worktree review workbench`.
* `pnpm test` passed in `/Users/cses-38/workspace/beads-web-ui` with 48 tests.
* `pnpm lint` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `pnpm build` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `git diff --check` passed.
* Safe API smoke against `/Users/cses-38/workspace/authcses-sdk` passed: health OK, `/api/issues`, `/api/worktrees`, `/api/previews`, and `/api/worktrees/diff`.
* Worktree create/remove destructive flows were not live-smoked against active repos; command construction and guards are covered by tests.

## Dependencies

* Depends on Trellis execution bridge.
