# Vibe Kanban and Beads UI Research

## Sources

* https://www.vibekanban.com/
* https://www.vibekanban.com/docs/index
* https://github.com/BloopAI/vibe-kanban
* Local `bd --help`, `bd statuses`, `bd ready`, `bd worktree --help`, and `bd export --help`.

## Vibe Kanban reference points

Vibe Kanban focuses on planning and reviewing AI coding work. The useful reference model is:

* Kanban issue planning with priorities, assignment, and statuses.
* Agent execution from issues, with isolated worktrees per task.
* Parallel agent monitoring from a single UI.
* Review surfaces for diffs, feedback, previews, and PR handoff.

## Beads constraints and advantages

Beads already provides a local task database and command model:

* Issue lifecycle: create, list, ready, update, close, reopen, defer, blocked, search.
* Structure: parent/child, dependencies, epics, graph, swarm, duplicates.
* Collaboration data: comments, notes, labels, owners, assignees.
* AI-friendly coordination: gates, merge slots, worktrees, audit, memories.
* Data access: JSON output, JSONL export/import, Dolt-backed versioning.

The UI should avoid replacing Beads storage. It should be a thin, reliable web layer over Beads commands or a dedicated local API that preserves Beads as the source of truth.

## Product direction

The best first version is not a full Vibe Kanban clone. It should be a Beads-native operations cockpit:

* Board/list/graph views for the Beads issue database.
* Task detail with metadata, dependencies, parent/child tree, comments, notes, and Trellis folder link.
* Ready/blocked queue for AI execution planning.
* Worktree and agent session surfaces added after the core issue UI is solid.

## Implementation implications

Recommended sequence:

1. Read-only explorer UI over `bd --json` commands.
2. Controlled mutations through a small local API wrapper around Beads.
3. Agent/worktree orchestration using `bd worktree` and Trellis task folders.
4. Review/preview surfaces only after the execution model is stable.

