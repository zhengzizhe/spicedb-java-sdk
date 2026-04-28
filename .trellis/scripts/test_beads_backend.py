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


SCRIPT_DIR = Path(__file__).resolve().parent
TASK_PY = SCRIPT_DIR / "task.py"


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
                    "--beads",
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
            self.assertFalse((repo_root / ".beads").exists())

            task_data = json.loads((task_dir / "task.json").read_text(encoding="utf-8"))
            self.assertEqual(task_data["meta"]["source_of_truth"], "beads")
            self.assertEqual(task_data["meta"]["beads_issue_id"], "bd-created-1")

            entries = self._read_log(log_path)
            self.assertEqual(entries[0]["command"], "create")
            self.assertFalse(entries[0]["folder_exists_at_create"])
            self.assertEqual(entries[0]["metadata"]["trellis_task_id"], "beads-first")

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
            task_data = json.loads((task_dir / "task.json").read_text(encoding="utf-8"))
            self.assertEqual(task_data["title"], "Materialized Bead")
            self.assertEqual(task_data["status"], "in_progress")
            self.assertEqual((repo_root / ".trellis" / ".current-task").read_text(encoding="utf-8"), ".trellis/tasks/04-28-materialized-bead")
            self.assertFalse((repo_root / ".beads").exists())

    def _make_trellis_repo(self, root: str) -> Path:
        repo_root = Path(root)
        trellis_dir = repo_root / ".trellis"
        trellis_dir.mkdir()
        (trellis_dir / ".developer").write_text("name=tester\n", encoding="utf-8")
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
            "trellis_task_dir": ".trellis/tasks/04-28-materialized-bead",
            "trellis_task_id": "materialized-bead",
        },
    }

entry = {"argv": argv, "command": command}

if command == "create":
    metadata = json.loads(flag_value("--metadata") or "{}")
    folder = Path.cwd() / metadata.get("trellis_task_dir", "")
    entry["metadata"] = metadata
    entry["folder_exists_at_create"] = folder.exists()
    log_path.open("a", encoding="utf-8").write(json.dumps(entry) + "\\n")
    print(json.dumps({
        "id": "bd-created-1",
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
    log_path.open("a", encoding="utf-8").write(json.dumps(entry) + "\\n")
    title = "Materialized Bead" if issue_id == "bd-claim-new" else "Claim Existing"
    print(json.dumps([issue(issue_id, title)]))
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
