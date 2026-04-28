# Research Beads Official Project Status

## Goal

Use the real Beads-backed Trellis workflow for a small search/research task and record findings from official Beads sources.

## Requirements

* Use the actual local Beads database, not fake `bd`.
* Confirm the Trellis task folder is linked to the Beads issue through `.bead`.
* Start the task so Beads status and Trellis metadata move to `in_progress`.
* Search official Beads sources and summarize current install/usage facts.
* Store research output under this task's `research/` directory.
* Archive the task and confirm the Beads issue closes.

## Acceptance Criteria

* [x] `.bead` exists and no legacy local task state file exists.
* [x] `bd show` exposes `external_ref` and `metadata.trellis_task_dir`.
* [x] `task.py start` makes the task visible as `in_progress`.
* [x] `research/beads-official-status.md` contains source-backed findings.
* [x] `task.py archive` closes the Beads issue.

## Out of Scope

* Changing Trellis runtime code unless the real test exposes a bug.
* Committing `.beads` database contents.
