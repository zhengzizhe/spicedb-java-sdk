# Replace runtime legacy task state reads with Beads

## Goal

Make `.codex`, `.agents`, and `.trellis` runtime paths use Beads-backed task data instead of hard-reading the old local task state file, while keeping that file as an explicit fallback for old/non-Beads task folders.

## What I Already Know

* New Beads-backed tasks write `.bead` and context files; task identity/status live in Beads.
* Beads native fields cover title, description, status, priority, type, assignee, creator, timestamps, external ref, labels, notes, comments, and dependencies.
* Legacy Trellis local state also carried execution metadata such as package, branch, base branch, scope, dev type, related files, worktree path, commit, PR URL, parent/children, and notes.
* Runtime hard reads existed in `.codex/hooks/statusline.py`, `.codex/hooks/inject-workflow-state.py`, `.codex/hooks/session-start.py`, `.trellis/scripts/common/task_utils.py`, `.trellis/scripts/hooks/linear_sync.py`, and several Trellis task commands.

## Requirements

* Compare the legacy local state shape with the current Beads issue payload.
* Store missing Trellis execution fields in Beads issue `metadata` during `task.py create`.
* Keep Beads native fields native where possible instead of duplicating them unnecessarily.
* Keep `.bead` as the folder binding and do not reintroduce local task state for new Beads-backed tasks.
* Teach the `.bead` task loader to hydrate its `TaskInfo.raw` from enriched Beads metadata.
* Replace runtime hard reads of task-local state in `.codex` hooks with the shared task loader.
* Replace lifecycle hook payloads with `TASK_DIR` / `BEADS_ISSUE_ID` first, with `LEGACY_TASK_STATE_PATH` only for legacy folders.
* Update Trellis start/archive/subtask/branch/scope commands to use Beads operations when a `.bead` marker exists.
* Add tests that prove metadata carries the legacy task context.

## Acceptance Criteria

* [x] `task.py create` sends metadata with Trellis parity fields.
* [x] A `.bead`-only folder can be listed with package/base branch/related metadata available in `TaskInfo.raw`.
* [x] No new Beads-backed task writes legacy local state.
* [x] `.codex` hooks can render current task status from `.bead` without legacy local state.
* [x] Lifecycle hooks receive `TASK_DIR` and `BEADS_ISSUE_ID` for Beads tasks.
* [x] Beads-backed `start`, `archive`, and parent-child commands call `bd` instead of mutating local task state.
* [x] Existing explicit legacy and `--beads-id` compatibility paths still pass tests.

## Out of Scope

* Duplicating Beads-native dependency graph data into metadata when Beads relationships already carry it.
* Migrating existing historical task folders into real Beads issues in the same change.

## Technical Notes

* Creation code: `.trellis/scripts/common/task_store.py`
* Beads CLI adapter and mapping helpers: `.trellis/scripts/common/beads_cli.py`
* Task loader: `.trellis/scripts/common/tasks.py`
* Codex hooks: `.codex/hooks/statusline.py`, `.codex/hooks/inject-workflow-state.py`, `.codex/hooks/session-start.py`
* Tests: `.trellis/scripts/test_beads_backend.py`
