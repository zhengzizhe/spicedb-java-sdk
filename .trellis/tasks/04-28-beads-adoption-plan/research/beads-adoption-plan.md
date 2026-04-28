# Beads adoption plan for this project

Date: 2026-04-28

## Decision

Use Beads as the AI-agent task backend candidate.

Do not use Beads as a replacement for Trellis files. The right split is:

| Layer | Owner |
| --- | --- |
| Agent task graph | Beads |
| Ready queue / blockers | Beads |
| Claiming and active work state | Beads |
| PRD, research, implementation/check context | Trellis task directory |
| Current Codex workspace pointer | `.trellis/.current-task` |
| Human-facing project UI | Not Beads; Linear or another UI if needed later |

## Current local state

* `bd` is not installed on this machine's PATH.
* A local test binary was built from `/tmp/beads` at `/tmp/beads/bd`.
* This repository has no `.beads/` directory.
* Existing Trellis tasks are file-based and several hooks/scripts read `.trellis/.current-task` and task-local `task.json`.
* The safe thing now is to document and plan, not initialize Beads inside the repository.

## Trial run results

The Beads pilot was run with a local binary and an isolated `/tmp` database:

```bash
env GOCACHE=/tmp/beads-go-cache GOPATH=/tmp/beads-go make build
env HOME=/tmp/authcses-sdk-beads-trial-home \
  BEADS_DIR=/tmp/authcses-sdk-beads-trial2/.beads \
  /tmp/beads/bd init --quiet --stealth
```

Successful checks:

* `/tmp/beads/bd --version` returned `bd version 1.0.3 (f4c46d9)`.
* `bd init --quiet --stealth` succeeded when run from a non-git `/tmp` workspace with `HOME` redirected to `/tmp`.
* `bd create --json` created issues and returned parseable JSON.
* `bd dep add <implementation> <schema>` created a blocking dependency.
* `bd ready --json` initially returned only the unblocked schema task.
* `bd update <id> --claim --json` set assignee and `in_progress`.
* `bd show --current --json` resolved the claimed issue and showed its dependent issue.
* `bd close <id> --reason "..." --suggest-next --json` closed the blocker and returned the newly unblocked implementation task.
* `bd ready --json` then returned the implementation task.
* `bd create --external-ref ... --metadata ... --json` successfully stored Trellis correlation data.
* `bd list --metadata-field source=trellis --json` and `bd list --has-metadata-key trellis_task_dir --json` found the linked task.

Important findings:

* Running `bd init --quiet --stealth` from inside this Git repository still tried to write `.git/info/exclude`. The sandbox blocked that write. For a no-side-effect pilot, run from a non-git temp directory or accept that Beads may update git exclude metadata.
* Embedded Dolt tried to access `~/.dolt`. In the sandbox this required `HOME=/tmp/authcses-sdk-beads-trial-home`. Outside the sandbox, this means Beads/Dolt needs normal home-directory config access.
* `bd close <id> "reason"` is wrong; the reason must be passed as `--reason`.
* In a non-git temp workspace, Beads warned that `beads.role` was not configured. It did not block basic use, but a real repository should decide maintainer/contributor role.
* No `.beads/` directory was created in `/Users/cses-38/workspace/authcses-sdk`.

Trial issue IDs:

| Purpose | Beads ID |
| --- | --- |
| Metadata schema blocker | `authcses-sdk-beads-trial-workspace-tdw` |
| Implementation task blocked by schema | `authcses-sdk-beads-trial-workspace-dkc` |
| Trellis-linked metadata sample | `authcses-sdk-beads-trial-workspace-d35` |

## Recommended adoption path

### Phase 1: Local reversible pilot

Goal: prove Beads fits the agent loop without changing the repo.

Install the CLI:

```bash
brew install beads
```

Alternative if Homebrew is not desired:

```bash
npm install -g @beads/bd
```

Run the pilot in an isolated Beads directory. In sandboxed environments, also redirect `HOME` so Dolt can write its local config:

```bash
export HOME=/tmp/authcses-sdk-beads-home
export BEADS_DIR=/tmp/authcses-sdk-beads/.beads
bd init --quiet --stealth
```

Why this mode:

* No `.beads/` state appears in the repository.
* `--stealth` disables git hook installation and git operations.
* It is suitable for evaluation and can be deleted.
* Running from a non-git temp workspace avoids `.git/info/exclude` writes during evaluation.

Try the basic agent loop:

```bash
bd create "Analyze Beads adoption for authcses-sdk" -p 2 -t task
bd ready --json
bd update <issue-id> --claim
bd show <issue-id>
bd close <issue-id> --reason "Research completed"
```

Try dependency behavior:

```bash
bd create "Implement Trellis Beads backend" -p 2 -t task
bd create "Decide Beads metadata schema" -p 1 -t task
bd dep add <implementation-id> <schema-id>
bd ready --json
```

Expected result: implementation is not ready until the schema task is closed.

### Phase 2: One-way Trellis to Beads dry-run

Goal: validate mapping without writing to Beads.

Build or manually generate a plan file from active Trellis tasks:

```json
{
  "commit_message": "trellis: import active tasks",
  "nodes": [
    {
      "key": "04-28-beads-adoption-plan",
      "title": "Analyze how to use Beads in this project",
      "type": "task",
      "priority": 2,
      "metadata": {
        "trellis_task_dir": ".trellis/tasks/04-28-beads-adoption-plan",
        "trellis_task_id": "beads-adoption-plan",
        "source": "trellis"
      }
    }
  ],
  "edges": []
}
```

Do not run `bd create --graph` automatically in the first version. Generate the plan and inspect it.

### Phase 3: Opt-in Beads-backed task creation

Goal: create new tasks in Beads first while keeping Trellis working.

Add an explicit opt-in path later, for example:

```bash
python3 .trellis/scripts/task.py create "Title" --backend beads
```

The flow should be:

1. Preflight `bd` is installed.
2. Preflight a Beads database exists.
3. Compute Trellis slug and expected task directory.
4. Search for an existing Beads issue by `external_ref` or metadata to avoid duplicates.
5. If none exists, run `bd create --json` with metadata.
6. Parse the Beads issue ID.
7. Materialize the Trellis task directory.
8. Write `task.json.meta.source_of_truth = "beads"` and `task.json.meta.beads_issue_id`.
9. Seed `prd.md`, `implement.jsonl`, and `check.jsonl`.
10. Set `.trellis/.current-task`.

Recommended Beads metadata:

```json
{
  "trellis_task_dir": ".trellis/tasks/04-28-beads-adoption-plan",
  "trellis_task_id": "beads-adoption-plan",
  "trellis_workspace": "authcses-sdk",
  "source": "trellis"
}
```

Recommended Beads external ref:

```text
trellis:.trellis/tasks/04-28-beads-adoption-plan
```

### Phase 4: Readiness and current-task bridge

Goal: use Beads for "what should the agent do next".

Add commands or wrappers equivalent to:

```bash
bd ready --json
bd update <id> --claim
bd show --current --json
```

When an issue is claimed, Trellis should select or materialize the corresponding task directory and write `.trellis/.current-task`.

If Beads says current task is X but `.trellis/.current-task` points to Y, Beads should win and Trellis should repair its pointer.

### Phase 5: Shared team use

Only after the local pilot works, choose a shared data strategy:

| Option | Use when | Trade-off |
| --- | --- | --- |
| Repo-local `.beads/` | One repository owns shared agent tasks | Needs rules for what is committed/synced |
| Dolt backup/remote | Team wants distributed sync | More operational setup |
| Beads server mode | Multiple concurrent writers/orchestrator | More setup, but better for multiple agents |
| Per-user stealth DB | Personal agent task memory only | Not a team source of truth |

For this project, start with per-user stealth DB for evaluation. Move to shared storage only after the Trellis bridge works.

## Trellis integration rules

### Task creation

Beads-first if `--backend beads` is enabled. Otherwise keep existing Trellis behavior.

### Task start

If `task.json.meta.beads_issue_id` exists, starting the task should optionally claim it in Beads:

```bash
bd update <beads-id> --claim
```

### Task completion

Do not make `.trellis/scripts/task.py finish` close Beads issues. In Trellis today, `finish` only clears the current task. Closing should be explicit or attached to an archive/completion workflow.

### Task discovery

Keep scanning `.trellis/tasks/*/task.json` for now. Add Beads-backed discovery only after the Beads-first create path is stable.

## MVP implementation scope

The first implementation task should be small:

1. Add a dry-run exporter from Trellis tasks to Beads graph JSON.
2. Add unit tests for field mapping and idempotency keys.
3. Do not call `bd` from tests.
4. Do not initialize `.beads/`.
5. Print the exact `bd create --graph <file>` command for manual execution.

The second implementation task can add:

1. `task.py create --backend beads`.
2. Beads CLI preflight.
3. Idempotency lookup by metadata/external ref.
4. Materialization of Trellis task directory from Beads issue.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| `bd` is not installed | Preflight with clear install instructions |
| `.beads/` pollutes repo | Start with `BEADS_DIR=/tmp/...` and `bd init --stealth` |
| `bd init --stealth` writes git exclude | For no-side-effect pilot, run from a non-git `/tmp` workspace |
| Dolt writes home config | In sandboxes, run with `HOME=/tmp/...`; in normal use, ensure home config writes are acceptable |
| Duplicate Beads issues | Use stable `external_ref` and metadata lookup |
| Trellis and Beads statuses diverge | Define Beads as authoritative only for Beads-backed tasks |
| No official Linear-style UI | Accept CLI-first workflow for agents; evaluate community UI separately if needed |
| Embedded Dolt has single-writer limits | Use server mode only if multiple agents write concurrently |
| JSON output changes | Keep parser tolerant of raw JSON vs future envelope output |
| Large PRDs bloat Beads | Store file paths and short summaries; keep full docs in Trellis |

## Recommendation

Adopt Beads in three careful moves:

1. Local pilot only: install `bd`, initialize isolated stealth DB, try ready/claim/dependency loop.
2. Build Trellis-to-Beads graph export in dry-run mode.
3. Add opt-in Beads-first task creation after the dry-run mapping is proven.

Do not replace Trellis. Do not make Beads the only storage for PRD/research/context. Use Beads for the agent task graph and Trellis for the working artifacts.

## Sources

* Prior Trellis research: `.trellis/tasks/04-28-beads-task-json-handoff-research/research/beads-first-task-creation.md`
* Beads install docs: `/tmp/beads/docs/INSTALLING.md`
* Beads Claude integration design: `/tmp/beads/docs/CLAUDE_INTEGRATION.md`
* Beads Dolt backend docs: `/tmp/beads/docs/DOLT-BACKEND.md`
* Beads README: `/tmp/beads/README.md`
* Upstream docs: https://gastownhall.github.io/beads/
* Upstream repo: https://github.com/gastownhall/beads
