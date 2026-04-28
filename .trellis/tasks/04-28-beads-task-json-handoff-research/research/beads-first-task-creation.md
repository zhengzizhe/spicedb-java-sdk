# Beads-first task creation design

Date: 2026-04-28

## Question

If Beads is the task system, why should Trellis generate `.trellis/tasks/*/task.json` first? Could new work generate a Beads issue first, then derive the Trellis working files from it?

## Short answer

Yes, Beads-first creation is feasible, but only if we separate two concepts:

* The task record: identity, status, priority, assignee, blockers, parent/child graph.
* The task workspace: `prd.md`, `research/`, `implement.jsonl`, `check.jsonl`, and `.trellis/.current-task`.

Beads can own the task record. Trellis still needs to materialize the task workspace because the current Codex hooks, Trellis workflow scripts, and sub-agent context loading are file-based.

So the practical design is not "stop generating Trellis task files." It is:

1. Create the Beads issue first.
2. Store the Beads issue ID in Trellis metadata.
3. Generate the Trellis task directory as a local working copy.
4. Treat Beads as authoritative for task graph/status/dependency decisions.

## Why raw replacement is not safe

Current Trellis code assumes a real task directory:

* `.trellis/scripts/common/task_store.py` creates `task.json`, seeds `implement.jsonl` and `check.jsonl`, and establishes parent/child task links.
* `.trellis/scripts/common/paths.py` stores the active work item in `.trellis/.current-task` as a repo-relative task directory.
* `.trellis/scripts/common/tasks.py` discovers active tasks by scanning `.trellis/tasks/*/task.json`.
* `.codex/hooks/statusline.py` and `.codex/hooks/session-start.py` read the current task's `task.json`.
* `.codex/agents/trellis-implement.toml` and `.codex/agents/trellis-check.toml` expect task-local PRD/context files.
* `.trellis/scripts/hooks/linear_sync.py` receives `TASK_JSON_PATH`.

Removing `task.json` would require a broad refactor of task discovery, current-task state, statusline, session injection, Linear sync, and sub-agent context loading.

## What Beads provides

Local Beads source inspection shows the useful primitives:

* `bd create` supports JSON output, metadata, parent, dependency, assignee, priority, type, external reference, dry-run, and silent modes.
* `bd create --graph <plan.json>` creates multiple issues and dependency edges atomically from symbolic node keys.
* Graph plan nodes support `key`, `title`, `type`, `description`, `assignee`, `priority`, labels, string metadata, metadata references, and parent references.
* `bd update` can merge metadata or set/unset metadata keys after creation.
* `bd ready` and `bd list` can filter by metadata fields.
* `bd show --current` resolves current work by in-progress assignment, hooked issue, then last touched issue.

Online docs describe Beads as a Dolt-backed distributed graph issue tracker for AI agents, with `bd ready`, `bd create`, `bd update --claim`, and dependency links as core workflow commands. The project README also documents embedded Dolt storage, `bd init`, and `bd init --stealth`.

## Proposed Beads-first flow

### Single task

1. Compute Trellis slug and predicted task directory name from the title.
2. Ensure Beads is installed and `.beads` is initialized. In this repository, initialization should remain explicit until the team decides to adopt Beads.
3. Create the Beads issue:

```bash
bd create \
  --json \
  --title "Research Beads handoff for Trellis task.json" \
  --type task \
  --priority 2 \
  --assignee cses-38 \
  --external-ref "trellis:.trellis/tasks/04-28-beads-task-json-handoff-research" \
  --metadata @/tmp/trellis-beads-metadata.json
```

4. Parse the returned Beads issue ID.
5. Materialize `.trellis/tasks/<MM-DD-slug>/`:

```json
{
  "id": "beads-task-json-handoff-research",
  "name": "beads-task-json-handoff-research",
  "title": "Research Beads handoff for Trellis task.json",
  "status": "planning",
  "priority": "P2",
  "assignee": "cses-38",
  "meta": {
    "source_of_truth": "beads",
    "beads_issue_id": "bd-....",
    "beads_external_ref": "trellis:.trellis/tasks/04-28-beads-task-json-handoff-research"
  }
}
```

6. Seed `prd.md`, `implement.jsonl`, `check.jsonl`, and optional `research/`.
7. Write `.trellis/.current-task` to the materialized Trellis directory.
8. If the final task dir differs from the predicted one, run `bd update <id> --set-metadata trellis_task_dir=<actual-dir>`.

### Requirement decomposition

For a requirement that becomes an epic plus child tasks, prefer `bd create --graph`:

```json
{
  "commit_message": "trellis: create requirement graph",
  "nodes": [
    {
      "key": "epic",
      "title": "Research Beads handoff for Trellis task.json",
      "type": "epic",
      "priority": 2,
      "metadata": {
        "trellis_task_dir": ".trellis/tasks/04-28-beads-task-json-handoff-research"
      }
    },
    {
      "key": "research",
      "title": "Evaluate Beads-first task creation",
      "type": "task",
      "priority": 2,
      "parent_key": "epic"
    }
  ],
  "edges": [
    {
      "from_key": "research",
      "to_key": "epic",
      "type": "parent-child"
    }
  ]
}
```

After graph creation, parse the returned symbolic-key-to-Beads-ID map and write each ID into the matching Trellis task directory metadata.

Important limitation: graph node `metadata` is `map[string]string`, so keep metadata values as strings in graph mode. Single `bd create --metadata` can accept a JSON object, but string metadata is more portable across Beads list/ready filters.

## Source of truth rules

Recommended ownership if Beads-first is adopted:

| Concern | Owner |
| --- | --- |
| Task identity for scheduling | Beads issue ID |
| Dependency graph and ready queue | Beads |
| Claiming/in-progress/blocked/closed lifecycle | Beads |
| Human PRD text and research artifacts | Trellis task directory |
| Agent context JSONL files | Trellis task directory |
| Current Codex task pointer | `.trellis/.current-task` |
| Cross-system correlation | `task.json.meta.beads_issue_id` and Beads metadata |

The key is to make `task.json` a projection/cache, not the operational source of truth.

## Status and priority mapping

| Trellis | Beads |
| --- | --- |
| `planning` | `open` |
| `in_progress` | `in_progress` or `hooked` |
| `review` | `open` plus label/metadata, unless a custom Beads status is configured |
| `completed` | `closed` |
| `P0` | `0` |
| `P1` | `1` |
| `P2` | `2` |
| `P3` | `3` |

If Beads is authoritative, Trellis status should be refreshed from Beads before statusline/session display, or clearly marked as a cached last-known status.

## Idempotency

Beads-first creation must avoid duplicate issues if the wrapper is re-run. Use at least one stable correlation key:

* `external_ref = trellis:<repo-relative-task-dir>`
* `metadata.trellis_task_dir`
* `metadata.trellis_task_id`

Before creating a new Beads issue, the wrapper should search/list by the correlation key and reuse the existing issue when present.

For graph creation, symbolic keys prevent collisions inside one plan, but they do not by themselves prevent duplicate issues across repeated runs. The wrapper still needs a preflight lookup or a persisted Beads ID mapping.

## Current-task alignment

Beads and Trellis have different "current task" mechanisms:

* Trellis uses `.trellis/.current-task`, pointing at a task directory.
* Beads writes `.beads/last-touched` and `bd show --current` also checks in-progress/hooked issues assigned to the actor.

In Beads-first mode, task switching should update both:

1. `bd update <id> --claim` or equivalent Beads lifecycle operation.
2. `.trellis/.current-task` pointing to the Beads issue's materialized Trellis directory.

If these disagree, Beads should win for scheduling and Trellis should be repaired by materializing or selecting the directory associated with the Beads issue.

## Migration path

### Phase 0: No repository side effects

Keep this research only. Do not run `bd init` here yet.

### Phase 1: Export existing Trellis tasks to a reviewable Beads plan

Generate `beads-plan.json` from `.trellis/tasks/*/task.json` and let a human inspect it before any `bd create --graph` run. This validates mapping without changing the workflow.

### Phase 2: Opt-in Beads backend for new tasks

Add an explicit mode, for example:

```bash
python3 .trellis/scripts/task.py create "Title" --backend beads
```

or a project config value such as:

```json
{
  "task_backend": "beads"
}
```

In this mode, create Beads first and then materialize the Trellis directory.

### Phase 3: Read-through Beads resolver

Teach session/context/statusline code to resolve current task data from Beads when `task.json.meta.source_of_truth == "beads"`, while preserving local files for PRD/research/context.

### Phase 4: Optional bidirectional sync

Only add bidirectional sync after the ownership rules are proven. Otherwise status and dependency conflicts will be hard to reason about.

## Risks

* Beads initialization introduces `.beads/` operational state and Dolt lifecycle decisions.
* Beads CLI output format may evolve; parsers should handle both raw JSON and the documented JSON envelope migration path.
* Graph metadata only supports string values.
* Trellis code currently treats task directory existence as truth.
* Status mismatches can confuse users unless Beads ownership is explicit.
* Re-running creation without idempotency will duplicate Beads issues.
* Large PRDs and research files should stay as files; copying them into issue descriptions will bloat Beads context.

## Recommendation

For the user's question, the answer is: generate with Beads first if Beads is intended to become the task backend. Do not generate only Beads. Generate Beads first, then materialize Trellis files.

The recommended near-term implementation is an opt-in Beads backend for `task.py create`:

1. Preflight `bd` installation and initialized database.
2. Create or reuse a Beads issue using stable metadata/external ref.
3. Materialize the Trellis task directory.
4. Store `meta.source_of_truth = "beads"` and `meta.beads_issue_id`.
5. Keep `prd.md`, `research/`, `implement.jsonl`, and `check.jsonl` in Trellis.
6. Use Beads for dependency graph, ready queue, claiming, and lifecycle.

This keeps the current Trellis/Codex workflow working while letting Beads take over the part it is designed for: a distributed dependency-aware task graph for agents.

## Sources

* Local Trellis code: `.trellis/scripts/common/task_store.py`, `.trellis/scripts/common/paths.py`, `.trellis/scripts/common/tasks.py`, `.codex/hooks/statusline.py`, `.codex/hooks/session-start.py`.
* Local Beads code: `/tmp/beads/cmd/bd/create.go`, `/tmp/beads/cmd/bd/graph_apply.go`, `/tmp/beads/cmd/bd/update.go`, `/tmp/beads/cmd/bd/ready.go`, `/tmp/beads/cmd/bd/show.go`, `/tmp/beads/internal/types/types.go`.
* Upstream Beads repository: https://github.com/gastownhall/beads
* Upstream Beads docs: https://gastownhall.github.io/beads/
