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

* [ ] Worktree operations are safe, explicit, and reversible where possible.
* [ ] Agent launch is an auditable handoff, not uncontrolled background execution.
* [ ] Diff and test results are tied to a specific issue/worktree.
* [ ] Preview links are displayed only when discovered or explicitly registered.
* [ ] Feedback can be persisted to Beads or Trellis without losing source context.
* [ ] Tests cover worktree discovery, missing worktree, diff loading failure, and preview link display.

## Dependencies

* Depends on Trellis execution bridge.

