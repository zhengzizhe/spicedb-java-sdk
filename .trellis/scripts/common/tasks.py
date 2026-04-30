"""
Task data access layer.

Single source of truth for loading and iterating task directories.
Loads Beads-backed folders first and keeps legacy local task files as fallback.

Provides:
    load_task          — Load a single task by directory path
    iter_active_tasks  — Iterate all non-archived tasks (sorted)
    get_all_statuses   — Get {dir_name: status} map for children progress
"""

from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

from .beads_cli import (
    BeadsCliError,
    issue_from_payload,
    issue_metadata,
    priority_to_trellis,
    run_bd_json,
    status_to_trellis,
)
from .beads_link import read_bead_marker, task_external_ref
from .io import read_json
from .paths import FILE_TASK_JSON, get_repo_root
from .types import TaskInfo


def load_task(task_dir: Path) -> TaskInfo | None:
    """Load task from a directory containing a .bead marker or legacy task file.

    Args:
        task_dir: Absolute path to the task directory.

    Returns:
        TaskInfo if task data exists and is valid, None otherwise.
    """
    legacy_task_file = task_dir / FILE_TASK_JSON
    legacy_data = read_json(legacy_task_file) if legacy_task_file.is_file() else None

    beads_task = _load_beads_task(task_dir, legacy_data if isinstance(legacy_data, dict) else None)
    if beads_task is not None:
        return beads_task

    if not legacy_data:
        return None

    return TaskInfo(
        dir_name=task_dir.name,
        directory=task_dir,
        title=legacy_data.get("title") or legacy_data.get("name") or "unknown",
        status=legacy_data.get("status", "unknown"),
        assignee=legacy_data.get("assignee", ""),
        priority=legacy_data.get("priority", "P2"),
        children=tuple(legacy_data.get("children", [])),
        parent=legacy_data.get("parent"),
        package=legacy_data.get("package"),
        raw=legacy_data,
    )


def _load_beads_task(task_dir: Path, legacy_data: dict | None = None) -> TaskInfo | None:
    """Load a Beads-backed task folder from `.bead` plus Beads metadata."""
    beads_id = read_bead_marker(task_dir)
    if not beads_id:
        return None

    repo_root = get_repo_root(task_dir)
    raw = dict(legacy_data) if legacy_data else _fallback_beads_raw(task_dir, repo_root, beads_id)
    meta = raw.get("meta")
    if not isinstance(meta, dict):
        meta = {}
    meta.update(
        {
            "source_of_truth": "beads",
            "beads_issue_id": beads_id,
            "beads_external_ref": task_external_ref(task_dir, repo_root),
            "task_storage": "beads" if legacy_data is None else "legacy_file_fallback",
        }
    )
    raw["meta"] = meta

    try:
        result = run_bd_json(["show", beads_id], repo_root, timeout=15)
        issue = issue_from_payload(result.payload)
    except BeadsCliError:
        issue = None

    if issue:
        metadata = issue_metadata(issue)
        task_snapshot = metadata.get("trellis_task")
        if isinstance(task_snapshot, dict):
            raw.update(task_snapshot)

        package = raw.get("package") or metadata.get("package")
        if not isinstance(package, str):
            package = None

        meta = raw.get("meta")
        if not isinstance(meta, dict):
            meta = {}
        meta.update(
            {
                "source_of_truth": "beads",
                "beads_issue_id": str(issue.get("id") or beads_id),
                "beads_external_ref": task_external_ref(task_dir, repo_root),
                "task_storage": "beads",
            }
        )

        raw.update(
            {
                "id": str(metadata.get("trellis_task_id") or issue.get("id") or beads_id),
                "name": str(metadata.get("trellis_task_name") or raw.get("name") or task_dir.name),
                "title": str(issue.get("title") or task_dir.name),
                "description": str(issue.get("description") or ""),
                "status": status_to_trellis(issue.get("status")),
                "priority": priority_to_trellis(issue.get("priority")),
                "assignee": str(issue.get("assignee") or ""),
                "package": package,
                "meta": meta,
            }
        )

    return TaskInfo(
        dir_name=task_dir.name,
        directory=task_dir,
        title=raw.get("title") or task_dir.name,
        status=raw.get("status", "unknown"),
        assignee=raw.get("assignee", ""),
        priority=raw.get("priority", "P2"),
        children=tuple(raw.get("children", [])),
        parent=raw.get("parent"),
        package=raw.get("package"),
        raw=raw,
    )


def _fallback_beads_raw(task_dir: Path, repo_root: Path, beads_id: str) -> dict:
    """Build a local fallback when bd is unavailable."""
    return {
        "id": beads_id,
        "name": task_dir.name,
        "title": task_dir.name,
        "description": "",
        "status": "beads",
        "priority": "P2",
        "assignee": "",
        "children": [],
        "parent": None,
        "package": None,
        "meta": {
            "source_of_truth": "beads",
            "beads_issue_id": beads_id,
            "beads_external_ref": task_external_ref(task_dir, repo_root),
            "task_storage": "beads",
        },
    }


def iter_active_tasks(tasks_dir: Path) -> Iterator[TaskInfo]:
    """Iterate all active (non-archived) tasks, sorted by directory name.

    Skips the "archive" directory and directories without Beads or legacy task data.

    Args:
        tasks_dir: Path to the tasks directory.

    Yields:
        TaskInfo for each valid task.
    """
    if not tasks_dir.is_dir():
        return

    for d in sorted(tasks_dir.iterdir()):
        if not d.is_dir() or d.name == "archive":
            continue
        info = load_task(d)
        if info is not None:
            yield info


def get_all_statuses(tasks_dir: Path) -> dict[str, str]:
    """Get a {dir_name: status} mapping for all active tasks.

    Useful for computing children progress without loading full TaskInfo.

    Args:
        tasks_dir: Path to the tasks directory.

    Returns:
        Dict mapping directory names to status strings.
    """
    return {t.dir_name: t.status for t in iter_active_tasks(tasks_dir)}


def children_progress(
    children: tuple[str, ...] | list[str],
    all_statuses: dict[str, str],
) -> str:
    """Format children progress string like " [2/3 done]".

    Args:
        children: List of child directory names.
        all_statuses: Status map from get_all_statuses().

    Returns:
        Formatted string, or "" if no children.
    """
    if not children:
        return ""
    # Archived children are missing from the active task map. Count them as
    # done so parent progress does not regress after archive.
    done = sum(
        1 for c in children
        if c not in all_statuses or all_statuses.get(c) in ("completed", "done")
    )
    return f" [{done}/{len(children)} done]"
