#!/usr/bin/env python3
"""Linear sync hook for Trellis task lifecycle.

Syncs task events to Linear via the `linearis` CLI.

Usage (called automatically by task.py hooks):
    python3 .trellis/scripts/hooks/linear_sync.py create
    python3 .trellis/scripts/hooks/linear_sync.py start
    python3 .trellis/scripts/hooks/linear_sync.py archive

Manual usage:
    TASK_DIR=.trellis/tasks/<name> python3 .trellis/scripts/hooks/linear_sync.py sync

Environment:
    TASK_DIR        - Absolute path to the task directory (set by task.py)
    BEADS_ISSUE_ID  - Beads issue ID for Beads-backed tasks
    LEGACY_TASK_STATE_PATH - Legacy local state path for non-Beads tasks only

Configuration:
    .trellis/hooks.local.json  - Local config (gitignored), example:
    {
      "linear": {
        "team": "TEAM_KEY",
        "project": "Project Name",
        "assignees": {
          "dev-name": "linear-user-id"
        }
      }
    }
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

# ─── Configuration ────────────────────────────────────────────────────────────

# Trellis priority → Linear priority (1=Urgent, 2=High, 3=Medium, 4=Low)
PRIORITY_MAP = {"P0": 1, "P1": 2, "P2": 3, "P3": 4}

# Linear status names (must match your team's workflow)
STATUS_IN_PROGRESS = "In Progress"
STATUS_DONE = "Done"


def _load_config() -> dict:
    """Load local hook config from .trellis/hooks.local.json."""
    task_dir = os.environ.get("TASK_DIR", "")
    if task_dir:
        # Walk up from task dir to find .trellis/
        trellis_dir = Path(task_dir).parent.parent
    else:
        trellis_dir = Path(".trellis")

    config_path = trellis_dir / "hooks.local.json"
    try:
        with open(config_path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return {}


CONFIG = _load_config()
LINEAR_CFG = CONFIG.get("linear", {})

TEAM = LINEAR_CFG.get("team", "")
PROJECT = LINEAR_CFG.get("project", "")
ASSIGNEE_MAP = LINEAR_CFG.get("assignees", {})

# ─── Helpers ──────────────────────────────────────────────────────────────────


def _task_dir_from_env() -> Path:
    task_dir = os.environ.get("TASK_DIR", "")
    if task_dir:
        return Path(task_dir)
    legacy_path = os.environ.get("LEGACY_TASK_STATE_PATH", "")
    if legacy_path:
        return Path(legacy_path).parent
    print("TASK_DIR not set", file=sys.stderr)
    sys.exit(1)


def _read_task() -> tuple[dict, Path]:
    task_dir = _task_dir_from_env()
    scripts_dir = task_dir.parents[1] / "scripts" if len(task_dir.parents) >= 2 else Path(".trellis/scripts")
    if str(scripts_dir) not in sys.path:
        sys.path.insert(0, str(scripts_dir))
    try:
        from common.tasks import load_task  # type: ignore[import-not-found]
    except Exception as exc:
        print(f"Cannot load Trellis task helpers: {exc}", file=sys.stderr)
        sys.exit(1)

    task = load_task(task_dir)
    if task is None:
        print(f"Task data not found: {task_dir}", file=sys.stderr)
        sys.exit(1)
    return task.raw, task_dir


def _write_legacy_task(data: dict, task_dir: Path) -> None:
    try:
        from common.paths import FILE_TASK_JSON as legacy_file_name  # type: ignore[import-not-found]
    except Exception:
        legacy_file_name = "task" + ".json"

    path = task_dir / legacy_file_name
    if not path.is_file():
        return
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def _linearis(*args: str) -> dict | None:
    result = subprocess.run(
        ["linearis", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        print(f"linearis error: {result.stderr.strip()}", file=sys.stderr)
        sys.exit(1)
    stdout = result.stdout.strip()
    if stdout:
        return json.loads(stdout)
    return None


def _get_linear_issue(task: dict) -> str | None:
    meta = task.get("meta")
    if isinstance(meta, dict):
        return meta.get("linear_issue")
    return None


# ─── Actions ──────────────────────────────────────────────────────────────────


def cmd_create() -> None:
    if not TEAM:
        print("No linear.team configured in hooks.local.json", file=sys.stderr)
        sys.exit(1)

    task, task_dir = _read_task()
    if os.environ.get("BEADS_ISSUE_ID"):
        print("Linear sync skipped for Beads-backed task")
        return

    # Skip if already linked
    if _get_linear_issue(task):
        print(f"Already linked: {_get_linear_issue(task)}")
        return

    title = task.get("title") or task.get("name") or "Untitled"
    args = ["issues", "create", title, "--team", TEAM]

    # Map priority
    priority = PRIORITY_MAP.get(task.get("priority", ""), 0)
    if priority:
        args.extend(["-p", str(priority)])

    # Set project
    if PROJECT:
        args.extend(["--project", PROJECT])

    # Assign to Linear user
    assignee = task.get("assignee", "")
    linear_user_id = ASSIGNEE_MAP.get(assignee)
    if linear_user_id:
        args.extend(["--assignee", linear_user_id])

    # Link to parent's Linear issue if available
    parent_issue = _resolve_parent_linear_issue(task)
    if parent_issue:
        args.extend(["--parent-ticket", parent_issue])

    result = _linearis(*args)
    if result and "identifier" in result:
        if not isinstance(task.get("meta"), dict):
            task["meta"] = {}
        task["meta"]["linear_issue"] = result["identifier"]
        _write_legacy_task(task, task_dir)
        print(f"Created Linear issue: {result['identifier']}")


def cmd_start() -> None:
    task, _ = _read_task()
    issue = _get_linear_issue(task)
    if not issue:
        return
    _linearis("issues", "update", issue, "-s", STATUS_IN_PROGRESS)
    print(f"Updated {issue} -> {STATUS_IN_PROGRESS}")
    cmd_sync()


def cmd_archive() -> None:
    task, _ = _read_task()
    issue = _get_linear_issue(task)
    if not issue:
        return
    _linearis("issues", "update", issue, "-s", STATUS_DONE)
    print(f"Updated {issue} -> {STATUS_DONE}")


def cmd_sync() -> None:
    """Sync prd.md content to Linear issue description."""
    task, task_dir = _read_task()
    issue = _get_linear_issue(task)
    if not issue:
        print("No linear_issue in meta, run create first", file=sys.stderr)
        sys.exit(1)

    # Find prd.md next to the task directory
    prd_path = task_dir / "prd.md"
    if not prd_path.is_file():
        print(f"No prd.md found at {prd_path}", file=sys.stderr)
        sys.exit(1)

    description = prd_path.read_text(encoding="utf-8").strip()
    _linearis("issues", "update", issue, "-d", description)
    print(f"Synced prd.md to {issue} description")


# ─── Parent Issue Resolution ─────────────────────────────────────────────────


def _resolve_parent_linear_issue(task: dict) -> str | None:
    """Find parent task's Linear issue identifier."""
    parent_name = task.get("parent")
    if not parent_name:
        return None

    current_task_dir = _task_dir_from_env()
    tasks_dir = current_task_dir.parent
    parent_dir = tasks_dir / parent_name
    scripts_dir = current_task_dir.parents[1] / "scripts" if len(current_task_dir.parents) >= 2 else Path(".trellis/scripts")
    if str(scripts_dir) not in sys.path:
        sys.path.insert(0, str(scripts_dir))
    try:
        from common.tasks import load_task  # type: ignore[import-not-found]
    except Exception:
        return None
    parent_task = load_task(parent_dir)
    if parent_task:
        return _get_linear_issue(parent_task.raw)
    return None


# ─── Main ─────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else ""
    actions = {
        "create": cmd_create,
        "start": cmd_start,
        "archive": cmd_archive,
        "sync": cmd_sync,
    }
    fn = actions.get(action)
    if fn:
        fn()
    else:
        print(f"Unknown action: {action}", file=sys.stderr)
        print(f"Valid actions: {', '.join(actions)}", file=sys.stderr)
        sys.exit(1)
