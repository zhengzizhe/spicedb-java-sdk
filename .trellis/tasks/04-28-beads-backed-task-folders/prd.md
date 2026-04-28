# Upgrade Trellis for Beads-backed task folders

## Goal

Introduce the first concrete Trellis upgrade for the target model: one Beads issue maps to one Trellis task folder. Keep current `task.json` compatibility for now, but add a stable folder-level Beads link so future work can move task identity/status/dependencies into Beads.

## What I already know

* The user wants Beads to handle long workflows and many tasks better than file-only `task.json`.
* The desired model is one Beads issue per Trellis task folder.
* Trellis task folders should continue to hold `prd.md`, `research/`, `implement.jsonl`, `check.jsonl`, and `info.md`.
* `task.json` should not be removed in this step because hooks, statusline, context injection, and task scripts still depend on it.
* The previous commit added Beads research and a dry-run graph exporter.
* Current repository still has unrelated dirty changes. Keep edits scoped to `.trellis/scripts` and this task.

## Requirements

* Add a durable folder marker file, `.bead`, that stores the Beads issue ID for a Trellis task folder.
* Add helper logic to link a task folder to a Beads issue ID.
* When linking, also update `task.json.meta` as a compatibility cache:
  * `source_of_truth = "beads"`
  * `beads_issue_id = <id>`
  * `beads_external_ref = "trellis:<task-dir>"`
* Add a `task.py` subcommand for linking an existing Trellis task folder to a Beads issue.
* Add optional `task.py create --beads-id <id>` support so a newly-created folder can be linked immediately.
* Do not call `bd` in this step.
* Do not initialize or modify `.beads/`.
* Add tests for marker writing, task metadata update, and create-time linking.

## Acceptance Criteria

* [x] `task.py create "Title" --beads-id <id>` creates a folder with `.bead` and `task.json.meta.beads_issue_id`.
* [x] `task.py link-bead <task-dir> <id>` links an existing task folder.
* [x] `.bead` contains only the Beads issue ID plus newline.
* [x] `task.json.meta.source_of_truth` is `beads` after linking.
* [x] Tests cover helper behavior and CLI-level create-time linking.
* [x] No command calls `bd`.
* [x] No `.beads/` directory is created.

## Out of Scope

* Replacing `task.json` reads throughout Trellis.
* Creating Beads issues from Trellis.
* Running `bd create`, `bd update`, or `bd ready`.
* Beads status synchronization.
* Migration of existing tasks to Beads.

## Technical Notes

* Existing task creation lives in `.trellis/scripts/common/task_store.py`.
* CLI command wiring lives in `.trellis/scripts/task.py`.
* JSON helpers live in `.trellis/scripts/common/io.py`.
* Task path helpers live in `.trellis/scripts/common/task_utils.py` and `.trellis/scripts/common/paths.py`.
* Prior implementation: `.trellis/scripts/beads_graph_export.py`.
* Implemented files:
  * `.trellis/scripts/common/beads_link.py`
  * `.trellis/scripts/test_beads_task_link.py`
  * `.trellis/scripts/common/task_store.py`
  * `.trellis/scripts/task.py`
* Verification run:
  * `python3 .trellis/scripts/test_beads_task_link.py`
  * `python3 .trellis/scripts/test_beads_graph_export.py`
  * `python3 -m py_compile .trellis/scripts/common/beads_link.py .trellis/scripts/common/task_store.py .trellis/scripts/task.py .trellis/scripts/test_beads_task_link.py`
  * `python3 .trellis/scripts/task.py create --help`
