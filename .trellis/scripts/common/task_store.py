#!/usr/bin/env python3
"""
Task CRUD operations.

Provides:
    ensure_tasks_dir   - Ensure tasks directory exists
    cmd_create         - Create a new task
    cmd_archive        - Archive completed task
    cmd_set_branch     - Set git branch for task
    cmd_set_base_branch - Set PR target branch
    cmd_set_scope      - Set scope for PR title
    cmd_add_subtask    - Link child task to parent
    cmd_remove_subtask - Unlink child task from parent
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping

from .config import (
    get_packages,
    is_monorepo,
    resolve_package,
    validate_package,
)
from .beads_cli import (
    BeadsCliError,
    issue_from_payload,
    issue_id_from_payload,
    issue_metadata,
    run_bd_json,
)
from .git import run_git
from .beads_link import (
    BeadsLinkError,
    find_task_by_bead_id,
    link_task_to_bead,
    read_bead_marker,
    task_external_ref,
)
from .io import read_json, write_json
from .log import Colors, colored
from .paths import (
    DIR_ARCHIVE,
    DIR_TASKS,
    DIR_WORKFLOW,
    FILE_TASK_JSON,
    clear_current_task,
    generate_task_date_prefix,
    get_current_task,
    get_developer,
    get_repo_root,
    get_tasks_dir,
    set_current_task,
)
from .task_utils import (
    archive_task_complete,
    find_task_by_name,
    resolve_task_dir,
    run_task_hooks,
)


# =============================================================================
# Helper Functions
# =============================================================================

def _slugify(title: str) -> str:
    """Convert title to slug (only works with ASCII)."""
    result = title.lower()
    result = re.sub(r"[^a-z0-9]", "-", result)
    result = re.sub(r"-+", "-", result)
    result = result.strip("-")
    return result


def ensure_tasks_dir(repo_root: Path) -> Path:
    """Ensure tasks directory exists."""
    tasks_dir = get_tasks_dir(repo_root)
    archive_dir = tasks_dir / "archive"

    if not tasks_dir.exists():
        tasks_dir.mkdir(parents=True)
        print(colored(f"Created tasks directory: {tasks_dir}", Colors.GREEN), file=sys.stderr)

    if not archive_dir.exists():
        archive_dir.mkdir(parents=True)

    return tasks_dir


def _repo_relative_path(path: Path, repo_root: Path) -> str:
    """Return a repo-relative POSIX path when possible."""
    try:
        return path.relative_to(repo_root).as_posix()
    except ValueError:
        return path.as_posix()


def _current_branch(repo_root: Path) -> str:
    """Return the current git branch, falling back to main."""
    _, branch_out, _ = run_git(["branch", "--show-current"], cwd=repo_root)
    return branch_out.strip() or "main"


def _build_task_data(
    *,
    slug: str,
    title: str,
    description: str,
    status: str,
    package: str | None,
    priority: str,
    creator: str,
    assignee: str,
    today: str,
    base_branch: str,
    meta: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Build the Trellis task snapshot used in Beads metadata or legacy state."""
    return {
        "id": slug,
        "name": slug,
        "title": title,
        "description": description,
        "status": status,
        "dev_type": None,
        "scope": None,
        "package": package,
        "priority": priority,
        "creator": creator,
        "assignee": assignee,
        "createdAt": today,
        "completedAt": None,
        "branch": None,
        "base_branch": base_branch,
        "worktree_path": None,
        "commit": None,
        "pr_url": None,
        "subtasks": [],
        "children": [],
        "parent": None,
        "relatedFiles": [],
        "notes": "",
        "meta": meta or {},
    }


def _seed_context_files(task_dir: Path, repo_root: Path) -> bool:
    """Seed implement/check JSONL files when this platform consumes them."""
    if not _has_subagent_platform(repo_root):
        return False

    seeded = False
    for jsonl_name in ("implement.jsonl", "check.jsonl"):
        jsonl_path = task_dir / jsonl_name
        if not jsonl_path.exists():
            _write_seed_jsonl(jsonl_path)
            seeded = True
    return seeded


def _beads_create_metadata(
    *,
    task_dir: Path,
    repo_root: Path,
    slug: str,
    title: str,
    description: str,
    priority: str,
    creator: str,
    assignee: str,
    package: str | None,
    base_branch: str,
    parent_dir: Path | None = None,
) -> dict[str, Any]:
    """Build metadata stored on a Beads issue for Trellis re-materialization."""
    task_data = _build_task_data(
        slug=slug,
        title=title,
        description=description,
        status="planning",
        package=package,
        priority=priority,
        creator=creator,
        assignee=assignee,
        today=datetime.now().strftime("%Y-%m-%d"),
        base_branch=base_branch,
        meta={
            "source_of_truth": "beads",
            "beads_external_ref": task_external_ref(task_dir, repo_root),
            "task_storage": "beads_metadata",
        },
    )
    if parent_dir:
        task_data["parent"] = parent_dir.name

    metadata = {
        "source": "trellis",
        "trellis_schema_version": 1,
        "trellis_task_dir": _repo_relative_path(task_dir, repo_root),
        "trellis_task_id": slug,
        "trellis_task_name": slug,
        "trellis_task": task_data,
    }
    if package:
        metadata["package"] = package
    return metadata


def _create_beads_issue_for_task(
    *,
    title: str,
    description: str,
    priority: str,
    assignee: str,
    task_dir: Path,
    slug: str,
    package: str | None,
    repo_root: Path,
    parent_dir: Path | None = None,
) -> str:
    """Create the Beads issue that will be the source of truth for a task."""
    metadata = _beads_create_metadata(
        task_dir=task_dir,
        repo_root=repo_root,
        slug=slug,
        title=title,
        description=description,
        priority=priority,
        creator=get_developer(repo_root) or assignee,
        assignee=assignee,
        package=package,
        base_branch=_current_branch(repo_root),
        parent_dir=parent_dir,
    )

    bd_args = [
        "create",
        title,
        "--type",
        "task",
        "--priority",
        priority,
        "--assignee",
        assignee,
        "--external-ref",
        task_external_ref(task_dir, repo_root),
        "--metadata",
        json.dumps(metadata, ensure_ascii=False, separators=(",", ":")),
    ]
    if description:
        bd_args.extend(["--description", description])

    if parent_dir:
        parent_beads_id = read_bead_marker(parent_dir)
        if parent_beads_id:
            bd_args.extend(["--parent", parent_beads_id])

    result = run_bd_json(bd_args, repo_root)
    return issue_id_from_payload(result.payload)


def _metadata_task_dir(value: Any, repo_root: Path) -> Path | None:
    """Resolve trusted Trellis task directory metadata under .trellis/tasks."""
    if not isinstance(value, str) or not value.strip():
        return None

    normalized = value.strip()
    if normalized.startswith("trellis:"):
        normalized = normalized.removeprefix("trellis:")
    normalized = normalized.replace("\\", "/")
    while normalized.startswith("./"):
        normalized = normalized[2:]

    path_value = Path(normalized)
    if path_value.is_absolute():
        return None

    tasks_dir = get_tasks_dir(repo_root).resolve()
    if normalized.startswith(f"{DIR_WORKFLOW}/{DIR_TASKS}/"):
        candidate = (repo_root / path_value).resolve()
    elif "/" not in normalized:
        candidate = (tasks_dir / normalized).resolve()
    else:
        return None

    if candidate == tasks_dir or tasks_dir not in candidate.parents:
        return None
    return candidate


def _task_dir_from_beads_issue(issue: Mapping[str, Any], repo_root: Path) -> Path:
    """Resolve or derive the Trellis folder for a Beads issue."""
    metadata = issue_metadata(issue)
    metadata_dir = _metadata_task_dir(metadata.get("trellis_task_dir"), repo_root)
    if metadata_dir:
        return metadata_dir

    external_ref = issue.get("external_ref")
    external_dir = _metadata_task_dir(external_ref, repo_root)
    if external_dir:
        return external_dir

    issue_title = str(issue.get("title") or issue.get("id") or "beads-task")
    slug = _slugify(issue_title) or str(issue.get("id") or "beads-task")
    return get_tasks_dir(repo_root) / f"{generate_task_date_prefix()}-{slug}"


def _materialize_beads_issue(issue: Mapping[str, Any], repo_root: Path) -> Path:
    """Create or update a Trellis folder for a Beads issue."""
    issue_id = str(issue.get("id") or "").strip()
    if not issue_id:
        raise BeadsLinkError("bd output did not include an issue id")

    existing = find_task_by_bead_id(get_tasks_dir(repo_root), issue_id)
    if existing:
        return existing

    task_dir = _task_dir_from_beads_issue(issue, repo_root)
    task_dir.mkdir(parents=True, exist_ok=True)
    _seed_context_files(task_dir, repo_root)
    link_task_to_bead(task_dir, issue_id, repo_root)
    return task_dir


def _load_beads_issue(beads_issue_id: str, repo_root: Path) -> dict[str, Any]:
    """Load one Beads issue as a dict."""
    result = run_bd_json(["show", beads_issue_id], repo_root)
    return issue_from_payload(result.payload)


def _update_beads_trellis_task(
    task_dir: Path,
    updates: dict[str, Any],
    repo_root: Path,
) -> None:
    """Patch the nested Trellis task snapshot stored in Beads metadata."""
    beads_issue_id = read_bead_marker(task_dir)
    if not beads_issue_id:
        raise BeadsLinkError(f"Task folder is not linked to Beads: {task_dir}")

    issue = _load_beads_issue(beads_issue_id, repo_root)
    metadata = issue_metadata(issue)
    task_snapshot = metadata.get("trellis_task")
    if not isinstance(task_snapshot, dict):
        task_snapshot = {}

    task_snapshot.update(updates)
    metadata.update(
        {
            "source": "trellis",
            "trellis_schema_version": metadata.get("trellis_schema_version") or 1,
            "trellis_task_dir": _repo_relative_path(task_dir, repo_root),
            "trellis_task_id": str(task_snapshot.get("id") or metadata.get("trellis_task_id") or task_dir.name),
            "trellis_task_name": str(task_snapshot.get("name") or metadata.get("trellis_task_name") or task_dir.name),
            "trellis_task": task_snapshot,
        }
    )

    run_bd_json(
        [
            "update",
            beads_issue_id,
            "--metadata",
            json.dumps(metadata, ensure_ascii=False, separators=(",", ":")),
        ],
        repo_root,
    )


# =============================================================================
# Sub-agent platform detection + JSONL seeding
# =============================================================================

# Config directories of platforms that consume implement.jsonl / check.jsonl.
# Keep in sync with src/types/ai-tools.ts AI_TOOLS entries — these are the
# platforms listed in workflow.md's "agent-capable" Skill Routing block
# (Class-1 hook-inject + Class-2 pull-based preludes). Kilo / Antigravity /
# Windsurf are NOT in this list: they do not consume JSONL.
_SUBAGENT_CONFIG_DIRS: tuple[str, ...] = (
    ".claude",
    ".cursor",
    ".codex",
    ".kiro",
    ".gemini",
    ".opencode",
    ".qoder",
    ".codebuddy",
    ".factory",   # Factory Droid
    ".github/copilot",
)

_SEED_EXAMPLE = (
    "Fill with {\"file\": \"<path>\", \"reason\": \"<why>\"}. "
    "Put spec/research files only — no code paths. "
    "Run `python3 .trellis/scripts/get_context.py --mode packages` to list available specs. "
    "Delete this line once real entries are added."
)


def _has_subagent_platform(repo_root: Path) -> bool:
    """Return True if any sub-agent-capable platform is configured.

    Detected by probing well-known config directories at the repo root. Used
    only to decide whether ``task.py create`` should seed empty
    ``implement.jsonl`` / ``check.jsonl`` files.
    """
    for config_dir in _SUBAGENT_CONFIG_DIRS:
        if (repo_root / config_dir).is_dir():
            return True
    return False


def _write_seed_jsonl(path: Path) -> None:
    """Write a one-line seed JSONL file with a self-describing ``_example``.

    The seed row has no ``file`` field, so downstream consumers (hooks +
    preludes) that iterate entries via ``item.get("file")`` naturally skip
    it. The row exists purely as an in-file prompt for the AI curator.
    """
    seed = {"_example": _SEED_EXAMPLE}
    path.write_text(json.dumps(seed, ensure_ascii=False) + "\n", encoding="utf-8")


# =============================================================================
# Command: create
# =============================================================================

def cmd_create(args: argparse.Namespace) -> int:
    """Create a new task."""
    repo_root = get_repo_root()

    if not args.title:
        print(colored("Error: title is required", Colors.RED), file=sys.stderr)
        return 1

    # Validate --package (CLI source: fail-fast)
    package: str | None = getattr(args, "package", None)
    if not is_monorepo(repo_root):
        # Single-repo: ignore --package, no package prefix
        if package:
            print(colored(f"Warning: --package ignored in single-repo project", Colors.YELLOW), file=sys.stderr)
        package = None
    elif package:
        if not validate_package(package, repo_root):
            packages = get_packages(repo_root)
            available = ", ".join(sorted(packages.keys())) if packages else "(none)"
            print(colored(f"Error: unknown package '{package}'. Available: {available}", Colors.RED), file=sys.stderr)
            return 1
    else:
        # Inferred package for the task snapshot created during Beads create.
        package = resolve_package(repo_root=repo_root)

    # Default assignee to current developer
    assignee = args.assignee
    if not assignee:
        assignee = get_developer(repo_root)
        if not assignee:
            print(colored("Error: No developer set. Run init_developer.py first or use --assignee", Colors.RED), file=sys.stderr)
            return 1

    ensure_tasks_dir(repo_root)

    # Get current developer as creator
    creator = get_developer(repo_root) or assignee

    # Generate slug if not provided
    slug = args.slug or _slugify(args.title)
    if not slug:
        print(colored("Error: could not generate slug from title", Colors.RED), file=sys.stderr)
        return 1

    # Create task directory with MM-DD-slug format
    tasks_dir = get_tasks_dir(repo_root)
    date_prefix = generate_task_date_prefix()
    dir_name = f"{date_prefix}-{slug}"
    task_dir = tasks_dir / dir_name
    legacy_task_file = task_dir / FILE_TASK_JSON

    use_beads = not bool(getattr(args, "legacy_local_state", False))
    beads_id = getattr(args, "beads_id", None)

    parent_dir = resolve_task_dir(args.parent, repo_root) if args.parent else None

    if use_beads and task_dir.exists():
        print(colored(f"Error: Task directory already exists: {dir_name}", Colors.RED), file=sys.stderr)
        return 1

    if use_beads and not beads_id:
        try:
            beads_id = _create_beads_issue_for_task(
                title=args.title,
                description=args.description or "",
                priority=args.priority,
                assignee=assignee,
                task_dir=task_dir,
                slug=slug,
                package=package,
                repo_root=repo_root,
                parent_dir=parent_dir,
            )
            print(colored(f"Created Beads issue: {beads_id}", Colors.GREEN), file=sys.stderr)
        except BeadsCliError as exc:
            print(colored(f"Error: {exc}", Colors.RED), file=sys.stderr)
            return 1

    if task_dir.exists():
        print(colored(f"Warning: Task directory already exists: {dir_name}", Colors.YELLOW), file=sys.stderr)
    else:
        task_dir.mkdir(parents=True)

    if use_beads:
        task_data: dict[str, Any] | None = None
    else:
        today = datetime.now().strftime("%Y-%m-%d")
        task_data = _build_task_data(
            slug=slug,
            title=args.title,
            description=args.description or "",
            status="planning",
            package=package,
            priority=args.priority,
            creator=creator,
            assignee=assignee,
            today=today,
            base_branch=_current_branch(repo_root),
        )
        write_json(legacy_task_file, task_data)

    # Seed implement.jsonl / check.jsonl for sub-agent-capable platforms.
    # Agent curates real entries in Phase 1.3 (see .trellis/workflow.md).
    # Agent-less platforms (Kilo / Antigravity / Windsurf) skip this — they
    # load specs via the trellis-before-dev skill instead of JSONL.
    seeded_jsonl = _seed_context_files(task_dir, repo_root)

    # Handle --parent: establish bidirectional link
    if args.parent and parent_dir and not use_beads and task_data is not None:
        parent_json_path = parent_dir / FILE_TASK_JSON
        if not parent_json_path.is_file():
            print(colored(f"Warning: legacy parent state not found: {args.parent}", Colors.YELLOW), file=sys.stderr)
        else:
            parent_data = read_json(parent_json_path)
            if parent_data:
                # Add child to parent's children list
                parent_children = parent_data.get("children", [])
                if dir_name not in parent_children:
                    parent_children.append(dir_name)
                    parent_data["children"] = parent_children
                    write_json(parent_json_path, parent_data)

                # Set parent in child's legacy state.
                task_data["parent"] = parent_dir.name
                write_json(legacy_task_file, task_data)

                print(colored(f"Linked as child of: {parent_dir.name}", Colors.GREEN), file=sys.stderr)

    if beads_id:
        try:
            link_task_to_bead(task_dir, beads_id, repo_root)
            print(colored(f"Linked Beads issue: {beads_id}", Colors.GREEN), file=sys.stderr)
        except BeadsLinkError as exc:
            print(colored(f"Error: {exc}", Colors.RED), file=sys.stderr)
            return 1

    print(colored(f"Created task: {dir_name}", Colors.GREEN), file=sys.stderr)
    print("", file=sys.stderr)
    print(colored("Next steps:", Colors.BLUE), file=sys.stderr)
    print("  1. Create prd.md with requirements", file=sys.stderr)
    if seeded_jsonl:
        print(
            "  2. Curate implement.jsonl / check.jsonl (spec + research files only — "
            "see .trellis/workflow.md Phase 1.3)",
            file=sys.stderr,
        )
        print("  3. Run: python3 task.py start <dir>", file=sys.stderr)
    else:
        print("  2. Run: python3 task.py start <dir>", file=sys.stderr)
    print("", file=sys.stderr)

    # Output relative path for script chaining
    print(f"{DIR_WORKFLOW}/{DIR_TASKS}/{dir_name}")

    hook_path = legacy_task_file if legacy_task_file.is_file() else task_dir / ".bead"
    if hook_path.is_file():
        run_task_hooks("after_create", hook_path, repo_root)
    return 0


# =============================================================================
# Command: archive
# =============================================================================

def cmd_archive(args: argparse.Namespace) -> int:
    """Archive completed task."""
    repo_root = get_repo_root()
    task_name = args.name

    if not task_name:
        print(colored("Error: Task name is required", Colors.RED), file=sys.stderr)
        return 1

    tasks_dir = get_tasks_dir(repo_root)

    # Find task directory
    task_dir = find_task_by_name(task_name, tasks_dir)

    if not task_dir or not task_dir.is_dir():
        print(colored(f"Error: Task not found: {task_name}", Colors.RED), file=sys.stderr)
        print("Active tasks:", file=sys.stderr)
        # Import lazily to avoid circular dependency
        from .tasks import iter_active_tasks
        for t in iter_active_tasks(tasks_dir):
            print(f"  - {t.dir_name}/", file=sys.stderr)
        return 1

    dir_name = task_dir.name
    legacy_task_file = task_dir / FILE_TASK_JSON
    beads_issue_id = read_bead_marker(task_dir)

    # Update status before archiving
    today = datetime.now().strftime("%Y-%m-%d")
    if beads_issue_id:
        try:
            _update_beads_trellis_task(
                task_dir,
                {"status": "completed", "completedAt": today},
                repo_root,
            )
            run_bd_json(
                ["close", beads_issue_id, "--reason", f"Archived Trellis task {dir_name}"],
                repo_root,
            )
        except (BeadsCliError, BeadsLinkError) as exc:
            print(colored(f"Error: failed to archive Beads issue {beads_issue_id}: {exc}", Colors.RED), file=sys.stderr)
            return 1
    elif legacy_task_file.is_file():
        data = read_json(legacy_task_file)
        if data:
            data["status"] = "completed"
            data["completedAt"] = today
            write_json(legacy_task_file, data)

            # Handle subtask relationships on archive
            task_parent = data.get("parent")
            task_children = data.get("children", [])

            # If this is a child, remove from parent's children list
            if task_parent:
                parent_dir = find_task_by_name(task_parent, tasks_dir)
                if parent_dir:
                    parent_json = parent_dir / FILE_TASK_JSON
                    if parent_json.is_file():
                        parent_data = read_json(parent_json)
                        if parent_data:
                            parent_children = parent_data.get("children", [])
                            if dir_name in parent_children:
                                parent_children.remove(dir_name)
                                parent_data["children"] = parent_children
                                write_json(parent_json, parent_data)

            # If this is a parent, clear parent field in all children
            if task_children:
                for child_name in task_children:
                    child_dir_path = find_task_by_name(child_name, tasks_dir)
                    if child_dir_path:
                        child_json = child_dir_path / FILE_TASK_JSON
                        if child_json.is_file():
                            child_data = read_json(child_json)
                            if child_data:
                                child_data["parent"] = None
                                write_json(child_json, child_data)

    # Clear if current task
    current = get_current_task(repo_root)
    if current and dir_name in current:
        clear_current_task(repo_root)

    # Archive
    result = archive_task_complete(task_dir, repo_root)
    if "archived_to" in result:
        archive_dest = Path(result["archived_to"])
        year_month = archive_dest.parent.name
        print(colored(f"Archived: {dir_name} -> archive/{year_month}/", Colors.GREEN), file=sys.stderr)

        # Auto-commit unless --no-commit
        if not getattr(args, "no_commit", False):
            _auto_commit_archive(dir_name, repo_root)

        # Return the archive path
        print(f"{DIR_WORKFLOW}/{DIR_TASKS}/{DIR_ARCHIVE}/{year_month}/{dir_name}")

        # Run hooks with the archived path
        archived_json = archive_dest / FILE_TASK_JSON
        archived_bead = archive_dest / ".bead"
        run_task_hooks("after_archive", archived_json if archived_json.is_file() else archived_bead, repo_root)
        return 0

    return 1


def _auto_commit_archive(task_name: str, repo_root: Path) -> None:
    """Stage .trellis/tasks/ changes and commit after archive."""
    tasks_rel = f"{DIR_WORKFLOW}/{DIR_TASKS}"
    run_git(["add", "-A", tasks_rel], cwd=repo_root)

    # Check if there are staged changes
    rc, _, _ = run_git(
        ["diff", "--cached", "--quiet", "--", tasks_rel], cwd=repo_root
    )
    if rc == 0:
        print("[OK] No task changes to commit.", file=sys.stderr)
        return

    commit_msg = f"chore(task): archive {task_name}"
    rc, _, err = run_git(["commit", "-m", commit_msg], cwd=repo_root)
    if rc == 0:
        print(f"[OK] Auto-committed: {commit_msg}", file=sys.stderr)
    else:
        print(f"[WARN] Auto-commit failed: {err.strip()}", file=sys.stderr)


# =============================================================================
# Command: add-subtask
# =============================================================================

def cmd_add_subtask(args: argparse.Namespace) -> int:
    """Link a child task to a parent task."""
    repo_root = get_repo_root()

    parent_dir = resolve_task_dir(args.parent_dir, repo_root)
    child_dir = resolve_task_dir(args.child_dir, repo_root)

    parent_json_path = parent_dir / FILE_TASK_JSON
    child_json_path = child_dir / FILE_TASK_JSON
    parent_beads_id = read_bead_marker(parent_dir)
    child_beads_id = read_bead_marker(child_dir)

    if parent_beads_id and child_beads_id:
        try:
            run_bd_json(["update", child_beads_id, "--parent", parent_beads_id], repo_root)
            _update_beads_trellis_task(child_dir, {"parent": parent_dir.name}, repo_root)

            parent_issue = _load_beads_issue(parent_beads_id, repo_root)
            parent_meta = issue_metadata(parent_issue)
            parent_task = parent_meta.get("trellis_task")
            existing_children = []
            if isinstance(parent_task, dict) and isinstance(parent_task.get("children"), list):
                existing_children = [str(child) for child in parent_task["children"]]
            if child_dir.name not in existing_children:
                existing_children.append(child_dir.name)
            _update_beads_trellis_task(parent_dir, {"children": existing_children}, repo_root)
        except (BeadsCliError, BeadsLinkError) as exc:
            print(colored(f"Error: failed to link Beads tasks: {exc}", Colors.RED), file=sys.stderr)
            return 1

        print(colored(f"Linked Beads: {child_beads_id} -> {parent_beads_id}", Colors.GREEN), file=sys.stderr)
        return 0

    if not parent_json_path.is_file():
        print(colored(f"Error: legacy parent state not found: {args.parent_dir}", Colors.RED), file=sys.stderr)
        return 1

    if not child_json_path.is_file():
        print(colored(f"Error: legacy child state not found: {args.child_dir}", Colors.RED), file=sys.stderr)
        return 1

    parent_data = read_json(parent_json_path)
    child_data = read_json(child_json_path)

    if not parent_data or not child_data:
        print(colored("Error: failed to read legacy task state", Colors.RED), file=sys.stderr)
        return 1

    # Check if child already has a parent
    existing_parent = child_data.get("parent")
    if existing_parent:
        print(colored(f"Error: Child task already has a parent: {existing_parent}", Colors.RED), file=sys.stderr)
        return 1

    # Add child to parent's children list
    parent_children = parent_data.get("children", [])
    child_dir_name = child_dir.name
    if child_dir_name not in parent_children:
        parent_children.append(child_dir_name)
        parent_data["children"] = parent_children

    # Set parent in child's legacy state.
    child_data["parent"] = parent_dir.name

    # Write both
    write_json(parent_json_path, parent_data)
    write_json(child_json_path, child_data)

    print(colored(f"Linked: {child_dir.name} -> {parent_dir.name}", Colors.GREEN), file=sys.stderr)
    return 0


# =============================================================================
# Command: remove-subtask
# =============================================================================

def cmd_remove_subtask(args: argparse.Namespace) -> int:
    """Unlink a child task from a parent task."""
    repo_root = get_repo_root()

    parent_dir = resolve_task_dir(args.parent_dir, repo_root)
    child_dir = resolve_task_dir(args.child_dir, repo_root)

    parent_json_path = parent_dir / FILE_TASK_JSON
    child_json_path = child_dir / FILE_TASK_JSON
    parent_beads_id = read_bead_marker(parent_dir)
    child_beads_id = read_bead_marker(child_dir)

    if parent_beads_id and child_beads_id:
        try:
            run_bd_json(["update", child_beads_id, "--parent", ""], repo_root)
            _update_beads_trellis_task(child_dir, {"parent": None}, repo_root)

            parent_issue = _load_beads_issue(parent_beads_id, repo_root)
            parent_meta = issue_metadata(parent_issue)
            parent_task = parent_meta.get("trellis_task")
            existing_children = []
            if isinstance(parent_task, dict) and isinstance(parent_task.get("children"), list):
                existing_children = [str(child) for child in parent_task["children"]]
            existing_children = [child for child in existing_children if child != child_dir.name]
            _update_beads_trellis_task(parent_dir, {"children": existing_children}, repo_root)
        except (BeadsCliError, BeadsLinkError) as exc:
            print(colored(f"Error: failed to unlink Beads tasks: {exc}", Colors.RED), file=sys.stderr)
            return 1

        print(colored(f"Unlinked Beads: {child_beads_id} from {parent_beads_id}", Colors.GREEN), file=sys.stderr)
        return 0

    if not parent_json_path.is_file():
        print(colored(f"Error: legacy parent state not found: {args.parent_dir}", Colors.RED), file=sys.stderr)
        return 1

    if not child_json_path.is_file():
        print(colored(f"Error: legacy child state not found: {args.child_dir}", Colors.RED), file=sys.stderr)
        return 1

    parent_data = read_json(parent_json_path)
    child_data = read_json(child_json_path)

    if not parent_data or not child_data:
        print(colored("Error: failed to read legacy task state", Colors.RED), file=sys.stderr)
        return 1

    # Remove child from parent's children list
    parent_children = parent_data.get("children", [])
    child_dir_name = child_dir.name
    if child_dir_name in parent_children:
        parent_children.remove(child_dir_name)
        parent_data["children"] = parent_children

    # Clear parent in child's legacy state.
    child_data["parent"] = None

    # Write both
    write_json(parent_json_path, parent_data)
    write_json(child_json_path, child_data)

    print(colored(f"Unlinked: {child_dir.name} from {parent_dir.name}", Colors.GREEN), file=sys.stderr)
    return 0


# =============================================================================
# Command: link-bead
# =============================================================================

def cmd_link_bead(args: argparse.Namespace) -> int:
    """Link an existing Trellis task folder to a Beads issue."""
    repo_root = get_repo_root()
    target_dir = resolve_task_dir(args.dir, repo_root)

    try:
        link_task_to_bead(target_dir, args.beads_id, repo_root)
    except BeadsLinkError as exc:
        print(colored(f"Error: {exc}", Colors.RED), file=sys.stderr)
        return 1

    print(colored(f"✓ Linked Beads issue: {args.beads_id}", Colors.GREEN))
    print(f"  Task: {target_dir}")
    print(f"  Marker: {target_dir / '.bead'}")
    return 0


# =============================================================================
# Commands: Beads backend
# =============================================================================

def cmd_beads_ready(args: argparse.Namespace) -> int:
    """List ready Beads issues as JSON without mutating Trellis folders."""
    _ = args
    repo_root = get_repo_root()

    try:
        result = run_bd_json(["ready"], repo_root)
    except BeadsCliError as exc:
        print(colored(f"Error: {exc}", Colors.RED), file=sys.stderr)
        return 1

    print(json.dumps(result.payload, ensure_ascii=False, indent=2))
    return 0


def cmd_beads_claim(args: argparse.Namespace) -> int:
    """Claim a Beads issue, materialize/link its Trellis folder, and start it."""
    repo_root = get_repo_root()
    beads_id = str(args.beads_id or "").strip()
    if not beads_id:
        print(colored("Error: Beads issue ID is required", Colors.RED), file=sys.stderr)
        return 1

    try:
        result = run_bd_json(["update", beads_id, "--claim"], repo_root)
        issue = issue_from_payload(result.payload)
        task_dir = _materialize_beads_issue(issue, repo_root)
    except (BeadsCliError, BeadsLinkError) as exc:
        print(colored(f"Error: {exc}", Colors.RED), file=sys.stderr)
        return 1

    task_ref = _repo_relative_path(task_dir, repo_root)
    if not set_current_task(task_ref, repo_root):
        print(colored(f"Error: Failed to set current task: {task_ref}", Colors.RED), file=sys.stderr)
        return 1

    legacy_task_file = task_dir / FILE_TASK_JSON
    if legacy_task_file.is_file():
        task_data = read_json(legacy_task_file)
        if task_data and task_data.get("status") == "planning":
            task_data["status"] = "in_progress"
            write_json(legacy_task_file, task_data)

    print(colored(f"✓ Claimed Beads issue: {beads_id}", Colors.GREEN), file=sys.stderr)
    print(colored(f"✓ Current task set to: {task_ref}", Colors.GREEN), file=sys.stderr)
    print(task_ref)
    return 0


# =============================================================================
# Command: set-branch
# =============================================================================

def cmd_set_branch(args: argparse.Namespace) -> int:
    """Set git branch for task."""
    repo_root = get_repo_root()
    target_dir = resolve_task_dir(args.dir, repo_root)
    branch = args.branch

    if not branch:
        print(colored("Error: Missing arguments", Colors.RED))
        print("Usage: python3 task.py set-branch <task-dir> <branch-name>")
        return 1

    legacy_task_file = target_dir / FILE_TASK_JSON
    beads_issue_id = read_bead_marker(target_dir)
    if beads_issue_id:
        try:
            _update_beads_trellis_task(target_dir, {"branch": branch}, repo_root)
        except (BeadsCliError, BeadsLinkError) as exc:
            print(colored(f"Error: failed to update Beads metadata: {exc}", Colors.RED))
            return 1
        print(colored(f"✓ Beads branch set to: {branch}", Colors.GREEN))
        return 0

    if not legacy_task_file.is_file():
        print(colored(f"Error: legacy task state not found at {target_dir}", Colors.RED))
        return 1

    data = read_json(legacy_task_file)
    if not data:
        return 1

    data["branch"] = branch
    write_json(legacy_task_file, data)

    print(colored(f"✓ Branch set to: {branch}", Colors.GREEN))
    return 0


# =============================================================================
# Command: set-base-branch
# =============================================================================

def cmd_set_base_branch(args: argparse.Namespace) -> int:
    """Set the base branch (PR target) for task."""
    repo_root = get_repo_root()
    target_dir = resolve_task_dir(args.dir, repo_root)
    base_branch = args.base_branch

    if not base_branch:
        print(colored("Error: Missing arguments", Colors.RED))
        print("Usage: python3 task.py set-base-branch <task-dir> <base-branch>")
        print("Example: python3 task.py set-base-branch <dir> develop")
        print()
        print("This sets the target branch for PR (the branch your feature will merge into).")
        return 1

    legacy_task_file = target_dir / FILE_TASK_JSON
    beads_issue_id = read_bead_marker(target_dir)
    if beads_issue_id:
        try:
            _update_beads_trellis_task(target_dir, {"base_branch": base_branch}, repo_root)
        except (BeadsCliError, BeadsLinkError) as exc:
            print(colored(f"Error: failed to update Beads metadata: {exc}", Colors.RED))
            return 1
        print(colored(f"✓ Beads base branch set to: {base_branch}", Colors.GREEN))
        print(f"  PR will target: {base_branch}")
        return 0

    if not legacy_task_file.is_file():
        print(colored(f"Error: legacy task state not found at {target_dir}", Colors.RED))
        return 1

    data = read_json(legacy_task_file)
    if not data:
        return 1

    data["base_branch"] = base_branch
    write_json(legacy_task_file, data)

    print(colored(f"✓ Base branch set to: {base_branch}", Colors.GREEN))
    print(f"  PR will target: {base_branch}")
    return 0


# =============================================================================
# Command: set-scope
# =============================================================================

def cmd_set_scope(args: argparse.Namespace) -> int:
    """Set scope for PR title."""
    repo_root = get_repo_root()
    target_dir = resolve_task_dir(args.dir, repo_root)
    scope = args.scope

    if not scope:
        print(colored("Error: Missing arguments", Colors.RED))
        print("Usage: python3 task.py set-scope <task-dir> <scope>")
        return 1

    legacy_task_file = target_dir / FILE_TASK_JSON
    beads_issue_id = read_bead_marker(target_dir)
    if beads_issue_id:
        try:
            _update_beads_trellis_task(target_dir, {"scope": scope}, repo_root)
        except (BeadsCliError, BeadsLinkError) as exc:
            print(colored(f"Error: failed to update Beads metadata: {exc}", Colors.RED))
            return 1
        print(colored(f"✓ Beads scope set to: {scope}", Colors.GREEN))
        return 0

    if not legacy_task_file.is_file():
        print(colored(f"Error: legacy task state not found at {target_dir}", Colors.RED))
        return 1

    data = read_json(legacy_task_file)
    if not data:
        return 1

    data["scope"] = scope
    write_json(legacy_task_file, data)

    print(colored(f"✓ Scope set to: {scope}", Colors.GREEN))
    return 0
