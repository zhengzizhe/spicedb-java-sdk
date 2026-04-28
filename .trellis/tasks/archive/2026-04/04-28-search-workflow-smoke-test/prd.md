# Search Workflow Smoke Test

## Goal

Exercise the Beads-backed Trellis flow with a small search/research style task.

## Requirements

* Keep this task intentionally small; it is a workflow smoke test, not a product feature.
* Confirm the Trellis folder is linked to a Beads issue through `.bead`.
* Confirm Beads metadata includes the Trellis folder path and task snapshot.
* Start the task through `task.py start` so Beads claim behavior is exercised.
* Run a simple task list/status check to prove the shared task loader can resolve the Beads task.

## Acceptance Criteria

* [x] The task folder has `.bead` and no legacy local task state file.
* [x] `bd show <id>` returns `external_ref` and `metadata.trellis_task_dir`.
* [x] `task.py start` claims the Beads issue and sets `.trellis/.current-task`.
* [x] `task.py list --mine` shows this task through the shared loader.

## Out of Scope

* Implementing a real search feature.
* Committing or pushing `.beads` database contents.
