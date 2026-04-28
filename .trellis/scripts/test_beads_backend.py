#!/usr/bin/env python3
"""Focused tests for using Beads as the Trellis task backend."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from common.tasks import load_task


SCRIPT_DIR = Path(__file__).resolve().parent
TASK_PY = SCRIPT_DIR / "task.py"
REPO_ROOT = SCRIPT_DIR.parent.parent
STATUSLINE_HOOK = REPO_ROOT / ".codex" / "hooks" / "statusline.py"
PROMPT_HOOK = REPO_ROOT / ".codex" / "hooks" / "inject-workflow-state.py"
SESSION_HOOK = REPO_ROOT / ".codex" / "hooks" / "session-start.py"
LEGACY_TASK_FILE = "task" + ".json"


class BeadsBackendTest(unittest.TestCase):
    def test_create_beads_creates_issue_before_task_folder(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = self._make_trellis_repo(tmp)
            fake_bd, log_path = self._make_fake_bd(repo_root)

            result = subprocess.run(
                [
                    sys.executable,
                    str(TASK_PY),
                    "create",
                    "Beads First",
                    "--slug",
                    "beads-first",
                    "--description",
                    "Backend description",
                    "--priority",
                    "P1",
                ],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            task_dir = self._single_task_dir(repo_root)
            self.assertEqual((task_dir / ".bead").read_text(encoding="utf-8"), "bd-created-1\n")
            self.assertFalse((task_dir / LEGACY_TASK_FILE).exists())
            self.assertFalse((repo_root / ".beads").exists())

            entries = self._read_log(log_path)
            self.assertEqual(entries[0]["command"], "create")
            self.assertFalse(entries[0]["folder_exists_at_create"])
            self.assertEqual(entries[0]["metadata"]["trellis_task_id"], "beads-first")
            task_snapshot = entries[0]["metadata"]["trellis_task"]
            self.assertEqual(task_snapshot["id"], "beads-first")
            self.assertEqual(task_snapshot["description"], "Backend description")
            self.assertEqual(task_snapshot["status"], "planning")
            self.assertEqual(task_snapshot["priority"], "P1")
            self.assertEqual(task_snapshot["base_branch"], "main")
            self.assertEqual(task_snapshot["relatedFiles"], [])
            self.assertEqual(task_snapshot["meta"]["source_of_truth"], "beads")

    def test_beads_ready_delegates_to_bd_ready_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = self._make_trellis_repo(tmp)
            fake_bd, log_path = self._make_fake_bd(repo_root)

            result = subprocess.run(
                [sys.executable, str(TASK_PY), "beads-ready"],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout)[0]["id"], "bd-ready-1")
            entries = self._read_log(log_path)
            self.assertEqual(entries[0]["command"], "ready")
            self.assertIn("--json", entries[0]["argv"])
            self.assertFalse((repo_root / ".beads").exists())

    def test_beads_claim_sets_current_task_for_existing_link(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = self._make_trellis_repo(tmp)
            fake_bd, log_path = self._make_fake_bd(repo_root)

            create = subprocess.run(
                [
                    sys.executable,
                    str(TASK_PY),
                    "create",
                    "Claim Existing",
                    "--slug",
                    "claim-existing",
                    "--beads-id",
                    "bd-claim-1",
                ],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            self.assertEqual(create.returncode, 0, create.stderr)
            task_dir = self._single_task_dir(repo_root)

            claim = subprocess.run(
                [sys.executable, str(TASK_PY), "beads-claim", "bd-claim-1"],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            self.assertEqual(claim.returncode, 0, claim.stderr)
            expected_ref = task_dir.relative_to(repo_root).as_posix()
            self.assertEqual((repo_root / ".trellis" / ".current-task").read_text(encoding="utf-8"), expected_ref)
            entries = self._read_log(log_path)
            self.assertEqual(entries[0]["command"], "update")
            self.assertIn("--claim", entries[0]["argv"])

    def test_beads_claim_materializes_folder_from_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = self._make_trellis_repo(tmp)
            fake_bd, log_path = self._make_fake_bd(repo_root)

            claim = subprocess.run(
                [sys.executable, str(TASK_PY), "beads-claim", "bd-claim-new"],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            self.assertEqual(claim.returncode, 0, claim.stderr)
            task_dir = repo_root / ".trellis" / "tasks" / "04-28-materialized-bead"
            self.assertTrue(task_dir.is_dir())
            self.assertEqual((task_dir / ".bead").read_text(encoding="utf-8"), "bd-claim-new\n")
            self.assertFalse((task_dir / LEGACY_TASK_FILE).exists())
            self.assertEqual((repo_root / ".trellis" / ".current-task").read_text(encoding="utf-8"), ".trellis/tasks/04-28-materialized-bead")
            self.assertFalse((repo_root / ".beads").exists())

    def test_task_list_loads_beads_folder_from_beads_only(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = self._make_trellis_repo(tmp)
            fake_bd, log_path = self._make_fake_bd(repo_root)
            task_dir = repo_root / ".trellis" / "tasks" / "04-28-beads-only"
            task_dir.mkdir(parents=True)
            (task_dir / ".bead").write_text("bd-list-1\n", encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(TASK_PY), "list"],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("04-28-beads-only/", result.stdout)
            self.assertIn("in_progress", result.stdout)
            entries = self._read_log(log_path)
            self.assertEqual(entries[0]["command"], "show")
            old_bd = os.environ.get("TRELLIS_BD")
            old_log = os.environ.get("FAKE_BD_LOG")
            os.environ["TRELLIS_BD"] = str(fake_bd)
            os.environ["FAKE_BD_LOG"] = str(log_path)
            try:
                task = load_task(task_dir)
            finally:
                if old_bd is None:
                    os.environ.pop("TRELLIS_BD", None)
                else:
                    os.environ["TRELLIS_BD"] = old_bd
                if old_log is None:
                    os.environ.pop("FAKE_BD_LOG", None)
                else:
                    os.environ["FAKE_BD_LOG"] = old_log
            self.assertIsNotNone(task)
            self.assertEqual(task.raw["base_branch"], "main")
            self.assertEqual(task.raw["relatedFiles"], ["src/Auth.java"])
            self.assertEqual(task.raw["meta"]["beads_issue_id"], "bd-list-1")
            self.assertFalse((repo_root / ".beads").exists())

    def test_task_start_claims_beads_only_task(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = self._make_trellis_repo(tmp)
            fake_bd, log_path = self._make_fake_bd(repo_root)
            task_dir = repo_root / ".trellis" / "tasks" / "04-28-beads-start"
            task_dir.mkdir(parents=True)
            (task_dir / ".bead").write_text("bd-start-1\n", encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(TASK_PY), "start", ".trellis/tasks/04-28-beads-start"],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((repo_root / ".trellis" / ".current-task").read_text(encoding="utf-8"), ".trellis/tasks/04-28-beads-start")
            entries = self._read_log(log_path)
            self.assertEqual(entries[0]["command"], "update")
            self.assertIn("--claim", entries[0]["argv"])
            self.assertIn("--status", entries[0]["argv"])
            self.assertIn("in_progress", entries[0]["argv"])
            self.assertFalse((task_dir / LEGACY_TASK_FILE).exists())

    def test_create_beads_child_updates_parent_metadata_children(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = self._make_trellis_repo(tmp)
            fake_bd, log_path = self._make_fake_bd(repo_root)

            parent = subprocess.run(
                [
                    sys.executable,
                    str(TASK_PY),
                    "create",
                    "Hierarchy Parent",
                    "--slug",
                    "hierarchy-parent",
                ],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            self.assertEqual(parent.returncode, 0, parent.stderr)

            child = subprocess.run(
                [
                    sys.executable,
                    str(TASK_PY),
                    "create",
                    "Hierarchy Child",
                    "--slug",
                    "hierarchy-child",
                    "--parent",
                    ".trellis/tasks/04-28-hierarchy-parent",
                ],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            self.assertEqual(child.returncode, 0, child.stderr)

            entries = self._read_log(log_path)
            create_child = entries[1]
            self.assertEqual(create_child["command"], "create")
            self.assertIn("--parent", create_child["argv"])
            self.assertIn("bd-hierarchy-parent", create_child["argv"])

            update_entries = [entry for entry in entries if entry["command"] == "update"]
            self.assertEqual(len(update_entries), 1)
            parent_metadata = update_entries[0]["metadata"]
            self.assertEqual(
                parent_metadata["trellis_task"]["children"],
                ["04-28-hierarchy-child"],
            )

    def test_task_archive_closes_beads_only_task(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = self._make_trellis_repo(tmp)
            fake_bd, log_path = self._make_fake_bd(repo_root)
            task_dir = repo_root / ".trellis" / "tasks" / "04-28-beads-archive"
            task_dir.mkdir(parents=True)
            (task_dir / ".bead").write_text("bd-archive-1\n", encoding="utf-8")

            result = subprocess.run(
                [sys.executable, str(TASK_PY), "archive", "04-28-beads-archive", "--no-commit"],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(task_dir.exists())
            self.assertTrue((repo_root / ".trellis" / "tasks" / "archive" / "2026-04" / "04-28-beads-archive" / ".bead").is_file())
            commands = [entry["command"] for entry in self._read_log(log_path)]
            self.assertEqual(commands, ["show", "update", "close"])

    def test_codex_hooks_load_beads_only_task(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = self._make_trellis_repo(tmp, link_scripts=True)
            fake_bd, log_path = self._make_fake_bd(repo_root)
            task_dir = repo_root / ".trellis" / "tasks" / "04-28-beads-hook"
            task_dir.mkdir(parents=True)
            (task_dir / ".bead").write_text("bd-hook-1\n", encoding="utf-8")
            (task_dir / "prd.md").write_text("# Hook Bead\n", encoding="utf-8")
            (task_dir / "implement.jsonl").write_text('{"file": ".trellis/spec/tooling/index.md"}\n', encoding="utf-8")
            (repo_root / ".trellis" / ".current-task").write_text(".trellis/tasks/04-28-beads-hook", encoding="utf-8")

            statusline = subprocess.run(
                [sys.executable, str(STATUSLINE_HOOK)],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                input=json.dumps({"model": {"display_name": "test"}, "context_window": {"used_percentage": 1, "context_window_size": 1000}}),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            self.assertEqual(statusline.returncode, 0, statusline.stderr)
            self.assertIn("Listed Bead", statusline.stdout)

            prompt = subprocess.run(
                [sys.executable, str(PROMPT_HOOK)],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                input=json.dumps({"cwd": str(repo_root)}),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            self.assertEqual(prompt.returncode, 0, prompt.stderr)
            self.assertIn("materialized-bead", prompt.stdout)
            self.assertIn("in_progress", prompt.stdout)

            session = subprocess.run(
                [sys.executable, str(SESSION_HOOK)],
                cwd=repo_root,
                env=self._env(fake_bd, log_path),
                input=json.dumps({"cwd": str(repo_root)}),
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            self.assertEqual(session.returncode, 0, session.stderr)
            self.assertIn("READY", session.stdout)
            self.assertIn("Listed Bead", session.stdout)

    def _make_trellis_repo(self, root: str, *, link_scripts: bool = False) -> Path:
        repo_root = Path(root)
        trellis_dir = repo_root / ".trellis"
        trellis_dir.mkdir()
        (trellis_dir / ".developer").write_text("name=tester\n", encoding="utf-8")
        if link_scripts:
            (trellis_dir / "scripts").symlink_to(SCRIPT_DIR, target_is_directory=True)
            (trellis_dir / "workflow.md").write_text(
                "[workflow-state:in_progress]\nFlow from Beads.\n[/workflow-state:in_progress]\n",
                encoding="utf-8",
            )
        return repo_root

    def _make_fake_bd(self, repo_root: Path) -> tuple[Path, Path]:
        fake_bd = repo_root / "fake-bd.py"
        log_path = repo_root / "fake-bd.log"
        fake_bd.write_text(
            """#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

argv = sys.argv[1:]
command = next((arg for arg in argv if not arg.startswith("-")), "")
log_path = Path(os.environ["FAKE_BD_LOG"])

def flag_value(name):
    if name in argv:
        index = argv.index(name)
        if index + 1 < len(argv):
            return argv[index + 1]
    return ""

def issue(issue_id, title):
    return {
        "id": issue_id,
        "title": title,
        "description": "Claimed description",
        "status": "in_progress",
        "priority": 2,
        "assignee": "tester",
        "created_by": "tester",
        "created_at": "2026-04-28T00:00:00Z",
        "metadata": {
            "source": "trellis",
            "trellis_schema_version": 1,
            "trellis_task_dir": ".trellis/tasks/04-28-materialized-bead",
            "trellis_task_id": "materialized-bead",
            "trellis_task_name": "materialized-bead",
            "package": "sdk",
            "trellis_task": {
                "id": "materialized-bead",
                "name": "materialized-bead",
                "title": title,
                "description": "Claimed description",
                "status": "planning",
                "dev_type": None,
                "scope": None,
                "package": "sdk",
                "priority": "P2",
                "creator": "tester",
                "assignee": "tester",
                "createdAt": "2026-04-28",
                "completedAt": None,
                "branch": None,
                "base_branch": "main",
                "worktree_path": None,
                "commit": None,
                "pr_url": None,
                "subtasks": [],
                "children": [],
                "parent": None,
                "relatedFiles": ["src/Auth.java"],
                "notes": "",
                "meta": {"source_of_truth": "beads"},
            },
        },
    }

entry = {"argv": argv, "command": command}

if command == "create":
    metadata = json.loads(flag_value("--metadata") or "{}")
    slug = metadata.get("trellis_task_id", "created-1")
    issue_id = "bd-created-1" if slug == "beads-first" else f"bd-{slug}"
    folder = Path.cwd() / metadata.get("trellis_task_dir", "")
    entry["metadata"] = metadata
    entry["folder_exists_at_create"] = folder.exists()
    log_path.open("a", encoding="utf-8").write(json.dumps(entry) + "\\n")
    print(json.dumps({
        "id": issue_id,
        "title": argv[argv.index("create") + 1],
        "description": flag_value("--description"),
        "status": "open",
        "priority": 1,
        "assignee": flag_value("--assignee"),
        "external_ref": flag_value("--external-ref"),
        "metadata": metadata,
        "schema_version": 1,
    }))
elif command == "ready":
    log_path.open("a", encoding="utf-8").write(json.dumps(entry) + "\\n")
    print(json.dumps([{"id": "bd-ready-1", "title": "Ready", "priority": 2}]))
elif command == "update":
    issue_id = argv[argv.index("update") + 1]
    entry["issue_id"] = issue_id
    if flag_value("--metadata"):
        entry["metadata"] = json.loads(flag_value("--metadata"))
    log_path.open("a", encoding="utf-8").write(json.dumps(entry) + "\\n")
    title = "Materialized Bead" if issue_id == "bd-claim-new" else "Claim Existing"
    print(json.dumps([issue(issue_id, title)]))
elif command == "close":
    issue_id = argv[argv.index("close") + 1]
    entry["issue_id"] = issue_id
    log_path.open("a", encoding="utf-8").write(json.dumps(entry) + "\\n")
    payload = issue(issue_id, "Closed Bead")
    payload["status"] = "closed"
    print(json.dumps(payload))
elif command == "show":
    issue_id = argv[argv.index("show") + 1]
    entry["issue_id"] = issue_id
    log_path.open("a", encoding="utf-8").write(json.dumps(entry) + "\\n")
    print(json.dumps(issue(issue_id, "Listed Bead")))
else:
    log_path.open("a", encoding="utf-8").write(json.dumps(entry) + "\\n")
    print(json.dumps({"error": "unknown command"}))
    sys.exit(1)
""",
            encoding="utf-8",
        )
        fake_bd.chmod(0o755)
        return fake_bd, log_path

    def _env(self, fake_bd: Path, log_path: Path) -> dict[str, str]:
        env = os.environ.copy()
        env["TRELLIS_BD"] = str(fake_bd)
        env["FAKE_BD_LOG"] = str(log_path)
        return env

    def _read_log(self, log_path: Path) -> list[dict]:
        return [
            json.loads(line)
            for line in log_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

    def _single_task_dir(self, repo_root: Path) -> Path:
        tasks = [
            path for path in (repo_root / ".trellis" / "tasks").iterdir()
            if path.is_dir() and path.name != "archive"
        ]
        self.assertEqual(len(tasks), 1)
        return tasks[0]


if __name__ == "__main__":
    unittest.main()
