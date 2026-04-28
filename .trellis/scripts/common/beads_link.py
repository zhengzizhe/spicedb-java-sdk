"""Beads issue linkage for Trellis task folders.

This module keeps the folder-level `.bead` marker and the transitional
`task.json.meta` cache in sync. It does not call the `bd` CLI.
"""

from __future__ import annotations

from pathlib import Path

from .io import read_json, write_json
from .paths import FILE_TASK_JSON


FILE_BEAD_MARKER = ".bead"
SOURCE_OF_TRUTH_BEADS = "beads"


class BeadsLinkError(RuntimeError):
    """Raised when a task folder cannot be linked to a Beads issue."""


def normalize_beads_issue_id(beads_issue_id: str) -> str:
    """Normalize and validate a Beads issue ID for storage."""
    normalized = (beads_issue_id or "").strip()
    if not normalized:
        raise BeadsLinkError("Beads issue ID is required")
    if "\n" in normalized or "\r" in normalized:
        raise BeadsLinkError("Beads issue ID must be a single line")
    return normalized


def task_external_ref(task_dir: Path, repo_root: Path) -> str:
    """Return the stable Trellis external reference for a task directory."""
    try:
        relative = task_dir.relative_to(repo_root).as_posix()
    except ValueError:
        relative = task_dir.as_posix()
    return f"trellis:{relative}"


def write_bead_marker(task_dir: Path, beads_issue_id: str) -> Path:
    """Write the `.bead` marker file for a task folder."""
    issue_id = normalize_beads_issue_id(beads_issue_id)
    marker_path = task_dir / FILE_BEAD_MARKER
    try:
        marker_path.write_text(f"{issue_id}\n", encoding="utf-8")
    except OSError as exc:
        raise BeadsLinkError(f"Failed to write {marker_path}: {exc}") from exc
    return marker_path


def read_bead_marker(task_dir: Path) -> str | None:
    """Read a task folder's `.bead` marker, if present."""
    marker_path = task_dir / FILE_BEAD_MARKER
    if not marker_path.is_file():
        return None
    try:
        value = marker_path.read_text(encoding="utf-8").strip()
    except OSError:
        return None
    return value or None


def link_task_to_bead(task_dir: Path, beads_issue_id: str, repo_root: Path) -> dict:
    """Link a Trellis task folder to a Beads issue ID.

    Writes both:
    - `<task>/.bead` as the future durable folder marker.
    - `<task>/task.json.meta` as the compatibility cache used by current Trellis.
    """
    if not task_dir.is_dir():
        raise BeadsLinkError(f"Task directory not found: {task_dir}")

    issue_id = normalize_beads_issue_id(beads_issue_id)
    write_bead_marker(task_dir, issue_id)

    task_json_path = task_dir / FILE_TASK_JSON
    if not task_json_path.is_file():
        raise BeadsLinkError(f"task.json not found: {task_json_path}")

    task_data = read_json(task_json_path)
    if not task_data:
        raise BeadsLinkError(f"Failed to read task.json: {task_json_path}")

    meta = task_data.get("meta")
    if not isinstance(meta, dict):
        meta = {}

    meta.update(
        {
            "source_of_truth": SOURCE_OF_TRUTH_BEADS,
            "beads_issue_id": issue_id,
            "beads_external_ref": task_external_ref(task_dir, repo_root),
        }
    )
    task_data["meta"] = meta

    if not write_json(task_json_path, task_data):
        raise BeadsLinkError(f"Failed to write task.json: {task_json_path}")

    return task_data
