# Implement Beads graph dry-run exporter

## Goal

Implement a low-risk Trellis-to-Beads exporter that converts active Trellis task directories into a Beads `bd create --graph` JSON plan, without initializing Beads or writing to a Beads database.

## What I already know

* The user approved moving from Beads adoption research to a minimal implementation.
* Prior research recommends starting with a dry-run graph exporter before any Beads-first task creation.
* Beads `bd create --graph` accepts a JSON plan with `commit_message`, `nodes`, and optional `edges`.
* Beads graph nodes support `key`, `title`, `type`, `description`, `assignee`, `priority`, labels, and string metadata.
* Beads graph edges support `from_key`, `to_key`, and `type`.
* Trellis active tasks live under `.trellis/tasks/*/task.json`; archived tasks live under `.trellis/tasks/archive/`.
* This implementation must not call `bd`, must not create `.beads/`, and must not modify Java SDK runtime code.
* The repository has unrelated dirty changes. Keep edits scoped to this new task and `.trellis/scripts`.

## Requirements

* Add a CLI script under `.trellis/scripts/` for exporting Beads graph plans.
* Read active Trellis tasks using existing Trellis helpers where possible.
* Generate JSON compatible with Beads graph apply:
  * `commit_message`
  * `nodes`
  * `edges`
* Map Trellis priority strings to Beads numeric priority:
  * `P0 -> 0`
  * `P1 -> 1`
  * `P2 -> 2`
  * `P3 -> 3`
* Include stable node keys based on Trellis task directory names.
* Include metadata as string values only, because Beads graph node metadata is `map[string]string`.
* Include Trellis correlation metadata:
  * `trellis_task_dir`
  * `trellis_task_id`
  * `trellis_status`
  * `source=trellis`
* Include optional metadata for package, branch, base branch, commit, PR URL, and related files when present.
* Include parent-child edges from Trellis `parent` and `children` relationships without duplicating the same edge.
* Support writing the plan to a file and printing to stdout.
* Support filtering to the current task, all active tasks, and optionally only incomplete tasks.
* Print or document the manual command a human can run: `bd create --graph <plan-file>`.
* Add focused tests for mapping and graph generation.

## Acceptance Criteria

* [x] A dry-run exporter script exists under `.trellis/scripts/`.
* [x] Exporter can generate a valid Beads graph JSON plan for active tasks.
* [x] Exporter does not call `bd`.
* [x] Exporter does not create or modify `.beads/`.
* [x] Priority, metadata, and parent-child mapping are covered by tests.
* [x] Tests can run without Beads installed.
* [x] Trellis task context validates.

## Out of Scope

* Installing Beads.
* Running `bd create --graph`.
* Beads-first `task.py create --backend beads`.
* Bidirectional sync.
* Status synchronization from Beads back to Trellis.
* Human-facing UI work.

## Technical Notes

* Relevant helpers:
  * `.trellis/scripts/common/paths.py`
  * `.trellis/scripts/common/tasks.py`
  * `.trellis/scripts/common/io.py`
* Relevant prior research:
  * `.trellis/tasks/04-28-beads-adoption-plan/research/beads-adoption-plan.md`
  * `.trellis/tasks/04-28-beads-task-json-handoff-research/research/beads-first-task-creation.md`
* Tooling spec:
  * `.trellis/spec/tooling/index.md`
* Shared guide:
  * `.trellis/spec/guides/code-reuse-thinking-guide.md`
* Implemented exporter:
  * `.trellis/scripts/beads_graph_export.py`
  * `.trellis/scripts/test_beads_graph_export.py`
* Verification run:
  * `python3 .trellis/scripts/test_beads_graph_export.py`
  * `python3 .trellis/scripts/beads_graph_export.py --current`
  * `python3 .trellis/scripts/beads_graph_export.py --incomplete-only --output /tmp/beads-graph-plan.json`
  * `python3 -m json.tool /tmp/beads-graph-plan.json`
  * `python3 ./.trellis/scripts/task.py validate .trellis/tasks/04-28-beads-graph-dry-run-exporter`
