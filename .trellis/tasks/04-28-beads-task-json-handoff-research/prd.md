# Research Beads handoff for Trellis task.json

## Goal

Research whether Trellis task metadata, especially `.trellis/tasks/*/task.json`, can be handed off to Beads (`bd`) as an execution/task-tracking backend, and identify the safest integration approach for this project.

## What I already know

* The user wants this tracked as a Trellis requirement, not only answered in chat.
* The user specifically asked whether "our task.json" can be delivered to Beads.
* The user asked why new work should generate a Trellis task first instead of generating Beads issues directly.
* Beads is a Dolt-backed CLI issue tracker for AI agent workflows.
* Trellis task directories contain `task.json`, `prd.md`, optional `research/`, and agent context files such as `implement.jsonl` and `check.jsonl`.
* Current repository already has several uncommitted changes unrelated to this research; this task should avoid touching source code.

## Assumptions

* "Deliver to Beads" means converting or syncing Trellis task state into Beads issues/work graph, not replacing Trellis immediately.
* The initial deliverable is research and a recommendation, not production integration code.
* The primary compatibility question is one-way Trellis -> Beads first; bidirectional sync can be treated as a future phase unless research shows it is required.

## Requirements

* Inspect Trellis `task.json` schema and task lifecycle scripts.
* Inspect Beads source code and documentation for supported issue creation/import mechanisms.
* Determine whether raw Trellis `task.json` can be consumed directly by Beads.
* Evaluate whether Beads can become the generation-time source of truth instead of Trellis task directories.
* Propose at least two integration options and recommend one.
* Identify field mappings, data loss risks, lifecycle mismatches, and duplicate/conflict handling.
* Save findings under `research/` so the analysis survives context compaction.

## Acceptance Criteria

* [x] Research artifact exists under `research/`.
* [x] The answer states whether direct handoff is possible.
* [x] The answer includes a concrete mapping from Trellis task fields to Beads issue fields.
* [x] The answer identifies the safest MVP integration path.
* [x] The answer compares "Trellis-first projection" against "Beads-first generation".
* [x] No source-code implementation is required unless separately approved.

## Out of Scope

* Initializing Beads in this repository.
* Writing production sync/conversion code in this task.
* Changing Java SDK source code.

## Technical Notes

* Trellis task schema sources inspected:
  * `.trellis/tasks/*/task.json`
  * `.trellis/scripts/task.py`
  * `.trellis/scripts/common/task_store.py`
  * `.trellis/scripts/common/tasks.py`
* Beads repository was cloned to `/tmp/beads` for local source inspection.
* Beads source areas inspected:
  * `/tmp/beads/cmd/bd/import.go`
  * `/tmp/beads/cmd/bd/import_shared.go`
  * `/tmp/beads/cmd/bd/graph_apply.go`
  * `/tmp/beads/cmd/bd/create.go`
  * `/tmp/beads/internal/types/types.go`
  * `/tmp/beads/internal/storage/schema/migrations/`
