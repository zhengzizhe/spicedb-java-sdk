# Use Beads as Trellis task backend

## Goal

Make Beads the primary task backend for new Trellis work while preserving Trellis task folders as the workspace for PRD, research, implement/check context, and local hooks.

## Target Model

One Beads issue maps to one Trellis task folder:

```text
Beads issue
  id/status/priority/assignee/dependencies/ready queue
  metadata.trellis_task_dir = .trellis/tasks/<dir>

Trellis task folder
  .bead
  task.json compatibility cache
  prd.md
  research/
  implement.jsonl
  check.jsonl
  info.md
```

## Requirements

* Add a Beads CLI adapter that shells out to `bd` but is testable without Beads installed.
* Support resolving the Beads binary from:
  * `$TRELLIS_BD`
  * `bd` on `PATH`
  * `/tmp/beads/bd` as local development fallback
* Add `task.py create --beads`:
  * call `bd create --json` first
  * pass title, description, priority, type task, assignee, external ref, and metadata
  * parse the returned Beads issue ID
  * create/materialize the Trellis task folder
  * write `.bead`
  * write `task.json.meta.source_of_truth=beads`
* Keep `task.py create --beads-id` for linking an already-created Beads issue.
* Add `task.py beads-ready`:
  * call `bd ready --json`
  * print Beads JSON by default
  * do not mutate Trellis folders
* Add `task.py beads-claim <beads-id>`:
  * call `bd update <id> --claim --json`
  * resolve or materialize the Trellis folder from the Beads issue metadata
  * set `.trellis/.current-task`
* Do not initialize `.beads/` automatically.
* Do not replace task discovery/statusline/hooks in this step.
* Add focused tests using fake `bd` scripts or mocked subprocess behavior.

## Acceptance Criteria

* [x] `task.py create --beads "Title"` creates a Beads issue before materializing a Trellis folder.
* [x] Beads-created folders contain `.bead` and task metadata cache.
* [x] `task.py beads-ready` delegates to `bd ready --json`.
* [x] `task.py beads-claim <id>` delegates to `bd update <id> --claim --json` and sets current task.
* [x] Tests do not require real Beads or `.beads/`.
* [x] Existing non-Beads `task.py create` behavior still works.
* [x] No `.beads/` directory is created by tests or normal dry-run checks.

## Out of Scope

* Auto-running `bd init`.
* Removing `task.json`.
* Rewriting statusline/session hooks to call Beads.
* Bidirectional sync.
* Migrating all existing tasks into a real Beads database.

## Technical Notes

* Folder linkage is already implemented in `.trellis/scripts/common/beads_link.py`.
* Existing task creation is in `.trellis/scripts/common/task_store.py`.
* CLI wiring is in `.trellis/scripts/task.py`.
* Prior dry-run exporter is `.trellis/scripts/beads_graph_export.py`.
