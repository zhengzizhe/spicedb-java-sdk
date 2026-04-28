# Beads handoff feasibility for Trellis task.json

Date: 2026-04-28

## Question

Can this project's Trellis `task.json` files be handed off to Beads (`bd`) so Beads can track or execute those tasks?

## Short answer

Not directly. A raw Trellis `task.json` is not a valid Beads issue import record or Beads graph plan. Beads can accept equivalent information after transformation.

The lowest-risk path is a one-way exporter that converts Trellis task directories into a Beads graph plan consumed by:

```bash
bd create --graph <generated-plan.json>
```

or into JSONL records consumed by:

```bash
bd import <generated-issues.jsonl>
```

`bd create --graph` is the better fit when preserving parent/child relationships and dependencies matters.

## Trellis task.json shape

Observed fields from `.trellis/scripts/common/task_store.py` and active task files:

```json
{
  "id": "slug",
  "name": "slug",
  "title": "Human title",
  "description": "",
  "status": "planning|in_progress|review|completed",
  "dev_type": null,
  "scope": null,
  "package": null,
  "priority": "P0|P1|P2|P3",
  "creator": "developer",
  "assignee": "developer",
  "createdAt": "YYYY-MM-DD",
  "completedAt": null,
  "branch": null,
  "base_branch": "main",
  "worktree_path": null,
  "commit": null,
  "pr_url": null,
  "subtasks": [],
  "children": [],
  "parent": null,
  "relatedFiles": [],
  "notes": "",
  "meta": {}
}
```

Important: most product/implementation detail lives outside `task.json`, primarily in `prd.md`, `research/`, `info.md`, `implement.jsonl`, and `check.jsonl`.

## Beads accepted shapes

### JSONL import

`/tmp/beads/cmd/bd/import.go` reads newline-delimited JSON. Each issue line is unmarshaled into `types.Issue`. The minimum useful field is `title`; optional fields include `description`, `issue_type`, `priority`, `acceptance_criteria`, labels, dependencies, comments, and metadata.

`/tmp/beads/cmd/bd/import_shared.go` delegates to `CreateIssuesWithFullOptions` with `SkipPrefixValidation: true`, but it is still expecting Beads issue-shaped JSON, not arbitrary Trellis task metadata.

### Graph creation

`/tmp/beads/cmd/bd/graph_apply.go` defines:

```json
{
  "commit_message": "optional",
  "nodes": [
    {
      "key": "stable-symbolic-key",
      "title": "Issue title",
      "type": "task",
      "description": "...",
      "assignee": "...",
      "priority": 2,
      "labels": ["..."],
      "metadata": {"trellis_task_dir": "..."},
      "parent_key": "..."
    }
  ],
  "edges": [
    {"from_key": "child", "to_key": "parent", "type": "parent-child"}
  ]
}
```

This is a strong fit for Trellis because it supports symbolic keys, parent-child edges, labels, metadata, and atomic creation.

## Field mapping proposal

| Trellis field/source | Beads field |
| --- | --- |
| `task.json.id` or directory name | `metadata.trellis_task_id`, graph `node.key` |
| `task.json.title` | `title` |
| `task.json.description` plus summary of `prd.md` | `description` |
| `task.json.priority` (`P0`..`P3`) | numeric `priority` (`0`..`3`) |
| `task.json.status=planning` | Beads `status=open` |
| `task.json.status=in_progress` | Beads `status=in_progress`, assignee preserved |
| `task.json.status=completed` | Beads `status=closed`, `close_reason` optional |
| `task.json.assignee` | `assignee` |
| `task.json.creator` | `created_by` or metadata |
| `task.json.createdAt` | metadata unless importer needs RFC3339 `created_at` |
| `task.json.completedAt` | `closed_at` only if valid RFC3339 can be generated |
| `task.json.children` / child `parent` | graph `parent-child` edges |
| `task.json.relatedFiles` | `metadata.related_files` |
| `task.json.branch`, `commit`, `pr_url`, `worktree_path` | metadata |
| `prd.md` | description/notes attachment or metadata pointer |
| `research/*.md` | metadata pointer, not inline by default |
| `implement.jsonl`, `check.jsonl` | metadata pointers; Beads should not execute these directly |

## Mismatches and risks

* Raw `task.json` is Trellis-specific; Beads ignores or fails to use most fields unless transformed.
* Trellis priority is string (`P2`), while Beads priority is integer (`0..4`).
* Trellis status names do not exactly match Beads status semantics.
* Beads issue IDs are generated from its configured prefix/hash model; reusing Trellis IDs as Beads IDs could violate prefix expectations or make collision handling awkward.
* Trellis task context is file-oriented. Beads issue import is record-oriented. Large PRDs/research should likely be linked or summarized, not blindly copied into Beads descriptions.
* Beads `bd import` can create issues from JSONL, but code inspection shows `importIssuesCore` is currently a creation bridge rather than a rich bidirectional reconciliation layer. Re-running a naive exporter may duplicate work unless the exporter uses stable IDs, external refs, or dedup logic.
* Beads has its own Dolt database lifecycle. Initializing it inside this repository should be a deliberate decision because it adds `.beads/` state and operational rules.

## Options

### Option A: One-way graph exporter

Generate a Beads graph plan from Trellis active tasks and run `bd create --graph`.

Pros:
* Preserves parent/child relationships naturally.
* Avoids forcing Trellis IDs into Beads IDs.
* Can store Trellis directory and metadata on each Beads node.
* Good for initial migration or periodic snapshots.

Cons:
* Needs idempotency design if run repeatedly.
* Does not automatically sync Beads changes back to Trellis.

### Option B: Beads-first generation

Generate Beads issues directly when a new requirement starts, then derive or materialize the Trellis task directory from the Beads issue metadata.

Pros:
* Avoids duplicate task creation if Beads becomes the operational source of truth.
* Uses Beads for the thing it is strongest at: task graph, ready queue, blockers, and multi-agent coordination.
* Can make `bd ready` the primary "what should we work on next" interface.

Cons:
* Trellis currently needs a real task directory for `prd.md`, `research/`, `implement.jsonl`, `check.jsonl`, `.trellis/.current-task`, and workflow phase context.
* Existing Trellis scripts and hooks read files, not Beads. They would need a Beads-backed task resolver or a materialization step.
* Beads issue descriptions/metadata are not a clean replacement for larger PRD and research artifacts.
* Sub-agent context injection currently depends on task-local JSONL files. Beads-first generation must still create or expose those files before Phase 2.
* Beads initialization and Dolt lifecycle would become mandatory for every Trellis-managed project.
* This changes source-of-truth ownership. Conflict rules are needed when Beads status differs from Trellis files.

Feasible design:
1. `trellis task create` becomes a wrapper around `bd create` or `bd create --graph`.
2. The Beads issue stores `trellis_task_dir`, `prd_path`, and context file paths in metadata.
3. Trellis still materializes `.trellis/tasks/<date-slug>/` for PRD/research/context artifacts.
4. `.trellis/.current-task` points to the materialized task directory, while Beads stores operational status and dependencies.

This is viable, but it is not "use Beads instead of generating task" in the literal sense. It is "use Beads as the authoritative task record, then generate the Trellis artifact directory as a working copy."

### Option C: One-way JSONL exporter

Generate Beads issue JSONL and run `bd import`.

Pros:
* Simple format.
* Matches Beads export/import interoperability story.
* Easier to inspect and archive.

Cons:
* Dependency/parent graph handling is more error-prone.
* Idempotency and duplicate handling need careful treatment.
* Less expressive for atomic graph creation than `bd create --graph`.

### Option D: Bidirectional sync

Maintain a mapping between Trellis tasks and Beads issues, then sync status/assignee/comments both ways.

Pros:
* Best long-term UX if Beads becomes the operational task queue.

Cons:
* Highest risk. Requires conflict resolution rules, ownership boundaries, and lifecycle decisions.
* Could undermine Trellis as the source of truth unless clearly scoped.

## Recommendation

Start with Option A if the goal is a low-risk experiment.

Do not initialize Beads in this repository yet. Do not replace Trellis. Treat Beads as an optional downstream task graph that can answer questions like "what work is ready" after Trellis tasks are transformed.

If the product decision is to make Beads central, choose Option B deliberately. In that model, Trellis task directories do not disappear immediately; they become generated working directories for PRDs, research, and agent context files. The Beads issue becomes the authoritative task identity and dependency node.

See `research/beads-first-task-creation.md` for the detailed Beads-first creation design.

Suggested MVP:

1. Read `.trellis/tasks/*/task.json`.
2. For each active task, create a graph node with stable `key` equal to Trellis task directory name.
3. Map title, priority, assignee, status, and metadata.
4. Add `parent-child` edges from Trellis parent/children.
5. Include `trellis_task_dir`, `trellis_task_id`, `prd_path`, and context JSONL paths in metadata.
6. Generate a plan file only. Human reviews the plan before any `bd create --graph` run.

## Open questions

* Should Beads become the authoritative source of task identity at creation time, or should it remain a projection of Trellis tasks?
* Should Beads be a read-only projection of Trellis, or can Beads status changes become authoritative later?
* Should the exporter include full PRD text in Beads descriptions, or only path references and short summaries?
* If a Trellis task is renamed or archived, should the Beads issue be updated, closed, or left as historical record?
