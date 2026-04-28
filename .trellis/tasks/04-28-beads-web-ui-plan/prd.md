# Beads Web UI Plan

## Goal

Design a long-running implementation plan for a Beads-native Web UI similar in spirit to Vibe Kanban: a local task cockpit for planning, tracking, and eventually running AI coding work from Beads issues.

The UI should keep Beads as the source of truth and use Trellis task folders as execution context. It should first solve visibility and control over long task graphs, then add agent/worktree orchestration and review workflows.

## What I Already Know

* The user wants a complete Web UI page/app for Beads, similar to Vibe Kanban.
* This repo currently has no frontend framework configuration at the root.
* Trellis now uses Beads-first tasks through `.bead` markers and Beads `metadata.trellis_task`.
* Existing Beads integration lives in `.trellis/scripts/task.py`, `.trellis/scripts/common/beads_cli.py`, `.trellis/scripts/common/task_store.py`, and `.trellis/scripts/common/tasks.py`.
* Beads supports task lifecycle, ready/blocked views, parent/child relations, dependencies, graph, comments, labels, worktrees, JSON output, JSONL export/import, and Dolt-backed history.
* Vibe Kanban's relevant reference model is planning issues, running coding agents in isolated workspaces, reviewing diffs, and previewing apps from one UI.

## Assumptions

* The first target is a local developer tool, not a hosted SaaS product.
* Beads remains the task database; the UI must not create a parallel task store.
* Trellis folders remain the AI execution context for PRD, implement/check context, archives, and `.bead` links.
* MVP should optimize for AI-assisted long-flow work: many tasks, parent/child graphs, ready queues, and current work visibility.

## Requirements

### MVP: Beads Operations Cockpit

* Show a Kanban board grouped by Beads status: open/ready, in progress, blocked, deferred, closed.
* Show a task list with search, filters, priority, assignee, labels, and stale/blocked indicators.
* Show task detail with title, description, status, priority, owner, assignee, dependencies, dependents, parent, children, comments, notes, labels, and metadata.
* Show Trellis-specific metadata when present: task folder, PRD link, current status, branch, base branch, related files, commit, PR URL.
* Support safe mutations through a local API: create, update status, priority, assignee, labels, parent/child, dependency links, comments, notes, close/reopen/defer.
* Provide a graph/tree view for parent-child and dependency relationships.
* Provide a ready queue based on `bd ready` and blocked/dependency state.
* Make all Beads command failures visible and recoverable in the UI.

### Phase 2: Agent/Worktree Control

* Create or attach a Trellis folder from a Beads issue.
* Start a task and sync status between Beads and Trellis.
* Create isolated worktrees using Beads/Trellis conventions.
* Display active agent sessions, current task, worktree path, branch, and last activity.
* Provide execution prompts derived from PRD and Trellis context files.

### Phase 3: Review and Preview

* Show git diff per task/worktree.
* Surface test/lint/build results.
* Add feedback loop: comments or review notes can be sent back into the task context.
* Add dev server preview links and eventually embedded preview/browser if this becomes a standalone app.

## Non-Goals for MVP

* Hosted multi-user auth.
* Replacing Beads storage with a new database.
* Full PR review UI in the first milestone.
* Running arbitrary shell commands from the browser without a constrained local API.
* Full Vibe Kanban parity in one pass.

## Proposed Architecture

### Option A: Local Web App + Beads API Wrapper (Recommended)

* Add a small local backend process that exposes typed HTTP endpoints and internally calls Beads through the existing `bd --json` command model.
* Add a frontend app for board/list/detail/graph views.
* Keep mutation logic centralized in the backend so the UI never shells out directly.

Pros:

* Smallest reliable path from current repo.
* Keeps Beads source-of-truth clean.
* Easy to test by mocking the backend command adapter.
* Can later support worktrees, agents, diffs, and previews.

Cons:

* Requires introducing a frontend stack and local server.
* Needs careful command allowlisting and error handling.

### Option B: Static UI Reading Exported JSON

* Generate a JSON/JSONL snapshot from Beads and render it as a static dashboard.

Pros:

* Fastest prototype.
* Very low risk.

Cons:

* Read-only or awkward for mutations.
* Does not become a real Vibe Kanban-like workflow surface.

### Option C: Fork/Extend Vibe Kanban

* Use Vibe Kanban as the base and wire Beads as a backend/source.

Pros:

* Starts closer to the desired UX shape.
* May inherit agent/worktree/review capabilities.

Cons:

* Higher integration risk.
* Vibe Kanban has its own data and workflow assumptions.
* More likely to fight Beads/Trellis conventions.

## Recommended Plan

This parent task is intentionally an epic, not a direct implementation unit.
Implementation is split into child tasks so each step has a clear acceptance
surface:

* `04-28-beads-web-ui-api-contract` — local API contract and server scaffold.
* `04-28-beads-web-ui-readonly-data-service` — read-only Beads data loading.
* `04-28-beads-web-ui-board-list-detail` — main UI shell, board, list, detail.
* `04-28-beads-web-ui-relationships-queue` — parent/child, dependency, ready/blocked navigation.
* `04-28-beads-web-ui-mvp-acceptance` — MVP quality gate and documentation.
* `04-28-beads-web-ui-core-mutations` — safe write operations after MVP read-only validation.
* `04-28-beads-web-ui-trellis-bridge` — Trellis task folder execution bridge.
* `04-28-beads-web-ui-agent-review-preview` — agent/worktree/review/preview expansion.

### Milestone 0: Product and API Contract

* Define Beads UI domain model: Issue, Relation, Comment, Label, Worktree, TrellisTask.
* Define allowed read endpoints: list, ready, blocked, show, comments, graph, statuses, export.
* Define allowed write endpoints: create, update, close/reopen, link/unlink dependencies, parent/child, comment/note.
* Define error model: command failed, parse failed, stale data, unsupported mutation.

### Milestone 1: Read-Only UI

* Scaffold local backend and frontend.
* Implement issue loading from `bd --json list`, `bd ready`, `bd statuses`, `bd show`.
* Build board, list, and detail panes.
* Render Trellis metadata and task folder links.
* Add loading, empty, and error states.

### Milestone 2: Core Mutations

* Add create/update/close/reopen/defer flows.
* Add priority, assignee, label, parent/child, and dependency editing.
* Add comments and notes.
* Add optimistic refresh only where command rollback is simple; otherwise refresh after mutation.

### Milestone 3: Long-Flow Navigation

* Add dependency graph/tree.
* Add ready queue and blocked explanations.
* Add filters for large task sets: assignee, status, priority, label, package, stale, has Trellis folder.
* Add saved local view preferences.

### Milestone 4: Trellis Execution Bridge

* Create/claim Trellis task folders from selected Beads issues.
* Start/archive tasks from UI through existing Trellis scripts.
* Show PRD and context file presence.
* Show current task and hierarchy.

### Milestone 5: Agent/Worktree Execution

* Integrate `bd worktree` lifecycle.
* Show worktree branch/path/dirty state.
* Provide agent launch handoff commands.
* Track active/running sessions if the selected agent exposes a stable interface.

### Milestone 6: Review/Preview

* Show git diff for a task worktree.
* Show test/build status.
* Add review comments/feedback loop.
* Add dev server preview links.

## Acceptance Criteria

* [ ] A developer can run one local command and open the Beads Web UI.
* [ ] The UI lists Beads issues with status, priority, assignee, labels, and blocked/ready state.
* [ ] The UI renders parent/child and dependency relationships.
* [ ] The UI shows Trellis metadata and links a Beads issue to its task folder when present.
* [ ] Core mutations are routed through an allowlisted backend, not arbitrary browser shell access.
* [ ] Command failures appear with actionable error messages.
* [ ] Large task sets remain usable through filtering and search.
* [ ] Tests cover command adapter parsing, API endpoints, and key UI state transitions.

## Definition of Done

* Tests added for backend command adapter, API routes, and core UI components.
* Lint/typecheck/build pass.
* Docs explain how to run the UI and what Beads commands it needs.
* Security boundary documented: local-only server, allowlisted mutations, no arbitrary command execution.
* Rollback path documented: Beads data remains usable through `bd` if the UI is disabled.

## Research References

* [`research/vibe-kanban-and-beads-ui.md`](research/vibe-kanban-and-beads-ui.md) — Vibe Kanban reference model and Beads-local constraints.

## Open Questions

* Resolved working assumption: MVP starts read-only plus strong navigation and detail views. Write operations move to the next task after the MVP acceptance gate.

