#!/usr/bin/env python3
"""Export active Trellis tasks as a Beads graph creation plan.

This script is intentionally dry-run only. It writes JSON compatible with
`bd create --graph <plan-file>` but never invokes `bd` or initializes Beads.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from common.io import write_json
from common.paths import (
    DIR_TASKS,
    DIR_WORKFLOW,
    get_current_task_abs,
    get_repo_root,
    get_tasks_dir,
)
from common.tasks import iter_active_tasks, load_task
from common.types import TaskInfo


DEFAULT_COMMIT_MESSAGE = "trellis: import active tasks"
DEFAULT_PRIORITY = 2
PRIORITY_MAP = {
    "P0": 0,
    "P1": 1,
    "P2": 2,
    "P3": 3,
}
COMPLETE_STATUSES = {"completed", "done", "closed"}


class ExportError(RuntimeError):
    """Raised when the graph plan cannot be generated."""


def priority_to_beads(priority: Any) -> int:
    """Map Trellis priority strings to Beads numeric priorities."""
    if priority is None:
        return DEFAULT_PRIORITY

    normalized = str(priority).strip().upper()
    return PRIORITY_MAP.get(normalized, DEFAULT_PRIORITY)


def is_incomplete_task(task: TaskInfo) -> bool:
    """Return True when a Trellis task should pass --incomplete-only."""
    return str(task.status).strip().lower() not in COMPLETE_STATUSES


def select_tasks(
    repo_root: Path,
    *,
    current_only: bool = False,
    incomplete_only: bool = False,
) -> list[TaskInfo]:
    """Select Trellis tasks for export."""
    if current_only:
        current_task_dir = get_current_task_abs(repo_root)
        if current_task_dir is None:
            raise ExportError("No current Trellis task is set")

        current_task = load_task(current_task_dir)
        if current_task is None:
            raise ExportError(f"Current Trellis task has no valid task data: {current_task_dir}")

        tasks = [current_task]
    else:
        tasks = list(iter_active_tasks(get_tasks_dir(repo_root)))

    if incomplete_only:
        tasks = [task for task in tasks if is_incomplete_task(task)]

    return tasks


def build_graph_plan(
    tasks: list[TaskInfo],
    repo_root: Path,
    *,
    commit_message: str = DEFAULT_COMMIT_MESSAGE,
) -> dict[str, Any]:
    """Build a Beads graph JSON plan from Trellis task records."""
    if not tasks:
        raise ExportError("No Trellis tasks matched export filters")

    return {
        "commit_message": commit_message,
        "nodes": [_task_to_node(task, repo_root) for task in tasks],
        "edges": _task_edges(tasks),
    }


def _task_to_node(task: TaskInfo, repo_root: Path) -> dict[str, Any]:
    node: dict[str, Any] = {
        "key": task.dir_name,
        "title": task.title,
        "type": "task",
        "priority": priority_to_beads(task.priority),
        "metadata": _task_metadata(task, repo_root),
    }

    description = str(task.description or "").strip()
    if description:
        node["description"] = description

    assignee = str(task.assignee or "").strip()
    if assignee:
        node["assignee"] = assignee

    return node


def _task_metadata(task: TaskInfo, repo_root: Path) -> dict[str, str]:
    metadata = {
        "trellis_task_dir": _task_dir_ref(task, repo_root),
        "trellis_task_id": str(task.raw.get("id") or task.name or task.dir_name),
        "trellis_status": str(task.status),
        "source": "trellis",
    }

    optional_values = {
        "package": task.package,
        "branch": task.raw.get("branch"),
        "base_branch": task.raw.get("base_branch"),
        "commit": task.raw.get("commit"),
        "pr_url": task.raw.get("pr_url"),
        "related_files": task.raw.get("relatedFiles") or task.raw.get("related_files"),
    }

    for key, value in optional_values.items():
        metadata_value = _metadata_string(value)
        if metadata_value is not None:
            metadata[key] = metadata_value

    return metadata


def _task_dir_ref(task: TaskInfo, repo_root: Path) -> str:
    try:
        return task.directory.relative_to(repo_root).as_posix()
    except ValueError:
        return f"{DIR_WORKFLOW}/{DIR_TASKS}/{task.dir_name}"


def _metadata_string(value: Any) -> str | None:
    if value is None:
        return None

    if isinstance(value, str):
        stripped = value.strip()
        return stripped or None

    if isinstance(value, (list, tuple)):
        if not value:
            return None
        return json.dumps([str(item) for item in value], ensure_ascii=False, separators=(",", ":"))

    return str(value)


def _task_edges(tasks: list[TaskInfo]) -> list[dict[str, str]]:
    task_keys = {task.dir_name for task in tasks}
    edge_keys: set[tuple[str, str, str]] = set()

    def add_parent_child(child: str | None, parent: str | None) -> None:
        if not child or not parent:
            return
        if child not in task_keys or parent not in task_keys:
            return
        edge_keys.add((child, parent, "parent-child"))

    for task in tasks:
        add_parent_child(task.dir_name, task.parent)
        for child in task.children:
            add_parent_child(child, task.dir_name)

    return [
        {"from_key": from_key, "to_key": to_key, "type": edge_type}
        for from_key, to_key, edge_type in sorted(edge_keys)
    ]


def write_plan(path: Path, plan: dict[str, Any]) -> None:
    """Write a Beads graph plan to disk."""
    if not write_json(path, plan):
        raise ExportError(f"Failed to write Beads graph plan: {path}")


def print_plan(plan: dict[str, Any]) -> None:
    """Print a Beads graph plan to stdout."""
    json.dump(plan, sys.stdout, indent=2, ensure_ascii=False)
    sys.stdout.write("\n")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Export active Trellis tasks as a dry-run Beads bd create --graph JSON plan.",
    )
    parser.add_argument(
        "--current",
        action="store_true",
        help="export only the current Trellis task instead of all active tasks",
    )
    parser.add_argument(
        "--incomplete-only",
        action="store_true",
        help="exclude completed, done, and closed Trellis tasks",
    )
    parser.add_argument(
        "--output",
        "-o",
        type=Path,
        help="write the graph plan to a file instead of stdout",
    )
    parser.add_argument(
        "--commit-message",
        default=DEFAULT_COMMIT_MESSAGE,
        help=f"commit_message value for the graph plan (default: {DEFAULT_COMMIT_MESSAGE!r})",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    repo_root = get_repo_root()

    try:
        tasks = select_tasks(
            repo_root,
            current_only=args.current,
            incomplete_only=args.incomplete_only,
        )
        plan = build_graph_plan(tasks, repo_root, commit_message=args.commit_message)

        if args.output:
            write_plan(args.output, plan)
            print(f"Wrote Beads graph plan to: {args.output}")
            print(f"Manual command: bd create --graph {args.output}")
        else:
            print_plan(plan)

        return 0
    except ExportError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
