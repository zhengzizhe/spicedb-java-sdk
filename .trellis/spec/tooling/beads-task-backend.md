# Beads Task Backend

## Scenario: Beads-First Trellis Tasks

### 1. Scope / Trigger

- Trigger: Trellis task lifecycle commands, Codex hooks, and Linear sync now use Beads as the task state source of truth.
- Applies to `.trellis/scripts/task.py`, `.trellis/scripts/common/task_store.py`, `.trellis/scripts/common/tasks.py`, `.codex/hooks/*`, and `.trellis/scripts/hooks/*`.

### 2. Signatures

- `python3 .trellis/scripts/task.py create "<title>" [--slug <name>] [--beads-id <id>]`
- `python3 .trellis/scripts/task.py create "<title>" --legacy-local-state`
- `python3 .trellis/scripts/task.py beads-ready`
- `python3 .trellis/scripts/task.py beads-claim <beads-id>`
- `python3 .trellis/scripts/task.py start <task-dir>`
- `python3 .trellis/scripts/task.py archive <task-name> [--no-commit]`
- `python3 .trellis/scripts/task.py add-subtask <parent> <child>`
- `python3 .trellis/scripts/task.py remove-subtask <parent> <child>`

### 3. Contracts

- New task creation is Beads-first by default. It creates a Beads issue, then materializes a Trellis folder with `.bead`, `prd.md`, and context JSONL files.
- `.bead` contains exactly one Beads issue id plus a trailing newline.
- Beads issue `metadata.trellis_task` stores the Trellis execution snapshot: package, branch, base branch, scope, dev type, related files, worktree path, commit, PR URL, parent/children, notes, and compatibility fields.
- `common.tasks.load_task(task_dir)` is the only supported runtime loader for task data. It loads Beads via `.bead` first, then falls back to legacy local state when no Beads marker exists.
- Lifecycle hooks receive `TASK_DIR` and `BEADS_ISSUE_ID`. Legacy-only folders may also receive `LEGACY_TASK_STATE_PATH`.
- Codex hooks must import `common.tasks.load_task` / `iter_active_tasks`; they must not parse task state files directly.

### 4. Validation & Error Matrix

- Beads executable missing during default create -> fail with a clear `bd executable not found` error.
- `--beads-id` provided -> link `.bead` without creating a new Beads issue.
- `.bead` present and `bd show` unavailable -> loader returns a local Beads fallback instead of failing hooks.
- Beads-backed archive -> update Beads metadata, then `bd close`; if either fails, do not archive the folder.
- Legacy-only command path missing local state -> fail with a legacy-state error, not a Beads error.

### 5. Good/Base/Bad Cases

- Good: `task.py create "Add auth cache"` creates a Beads issue and a `.bead` folder without legacy local state.
- Base: `task.py create "Old flow" --legacy-local-state` creates a legacy folder for explicit compatibility work.
- Bad: a hook reads task state by hard-coding the legacy file path.

### 6. Tests Required

- Default create calls `bd create`, sends `metadata.trellis_task`, writes `.bead`, and does not write legacy local state.
- `.bead`-only folders are listed and expose package/base branch/related files through `TaskInfo.raw`.
- Codex statusline, prompt injection, and session-start hooks render a `.bead`-only current task.
- Beads-backed start claims via `bd update --claim`.
- Beads-backed archive updates metadata and closes via `bd close`.
- Explicit legacy and `--beads-id` compatibility paths remain covered.

### 7. Wrong vs Correct

#### Wrong

```python
data = json.loads((task_dir / ("task" + ".json")).read_text(encoding="utf-8"))
status = data["status"]
```

#### Correct

```python
from common.tasks import load_task

task = load_task(task_dir)
status = task.status if task else "unknown"
```
