#!/usr/bin/env python3
"""Focused tests for Beads issue links on Trellis task folders."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from common.beads_link import link_task_to_bead, read_bead_marker
from common.io import read_json, write_json


SCRIPT_DIR = Path(__file__).resolve().parent
TASK_PY = SCRIPT_DIR / "task.py"


class BeadsTaskLinkTest(unittest.TestCase):
    def test_link_task_to_bead_writes_marker_and_task_json_meta(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = Path(tmp)
            task_dir = repo_root / ".trellis" / "tasks" / "01-linked"
            task_dir.mkdir(parents=True)
            write_json(task_dir / "task.json", {"id": "linked", "title": "Linked", "meta": {}})

            link_task_to_bead(task_dir, "bd-123", repo_root)

            self.assertEqual((task_dir / ".bead").read_text(encoding="utf-8"), "bd-123\n")
            self.assertEqual(read_bead_marker(task_dir), "bd-123")

            task_data = read_json(task_dir / "task.json")
            self.assertIsNotNone(task_data)
            meta = task_data["meta"]
            self.assertEqual(meta["source_of_truth"], "beads")
            self.assertEqual(meta["beads_issue_id"], "bd-123")
            self.assertEqual(meta["beads_external_ref"], "trellis:.trellis/tasks/01-linked")

    def test_task_create_with_beads_id_links_new_folder(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = self._make_trellis_repo(tmp)

            result = subprocess.run(
                [
                    sys.executable,
                    str(TASK_PY),
                    "create",
                    "Linked From Create",
                    "--slug",
                    "linked-from-create",
                    "--beads-id",
                    "bd-create-1",
                ],
                cwd=repo_root,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            task_dir = self._single_task_dir(repo_root)
            self.assertEqual((task_dir / ".bead").read_text(encoding="utf-8"), "bd-create-1\n")

            task_data = json.loads((task_dir / "task.json").read_text(encoding="utf-8"))
            self.assertEqual(task_data["meta"]["source_of_truth"], "beads")
            self.assertEqual(task_data["meta"]["beads_issue_id"], "bd-create-1")

    def test_task_link_bead_updates_existing_folder(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = self._make_trellis_repo(tmp)
            create = subprocess.run(
                [
                    sys.executable,
                    str(TASK_PY),
                    "create",
                    "Existing Link",
                    "--slug",
                    "existing-link",
                ],
                cwd=repo_root,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            self.assertEqual(create.returncode, 0, create.stderr)
            task_dir = self._single_task_dir(repo_root)

            link = subprocess.run(
                [
                    sys.executable,
                    str(TASK_PY),
                    "link-bead",
                    str(task_dir.relative_to(repo_root)),
                    "bd-existing-1",
                ],
                cwd=repo_root,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            self.assertEqual(link.returncode, 0, link.stderr)
            self.assertEqual((task_dir / ".bead").read_text(encoding="utf-8"), "bd-existing-1\n")
            task_data = json.loads((task_dir / "task.json").read_text(encoding="utf-8"))
            self.assertEqual(task_data["meta"]["beads_issue_id"], "bd-existing-1")

    def _make_trellis_repo(self, root: str) -> Path:
        repo_root = Path(root)
        trellis_dir = repo_root / ".trellis"
        trellis_dir.mkdir()
        (trellis_dir / ".developer").write_text("name=tester\n", encoding="utf-8")
        return repo_root

    def _single_task_dir(self, repo_root: Path) -> Path:
        tasks = [
            path for path in (repo_root / ".trellis" / "tasks").iterdir()
            if path.is_dir() and path.name != "archive"
        ]
        self.assertEqual(len(tasks), 1)
        return tasks[0]


if __name__ == "__main__":
    unittest.main()
