#!/usr/bin/env python3
"""Focused tests for the Trellis-to-Beads graph exporter."""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from beads_graph_export import (
    DEFAULT_COMMIT_MESSAGE,
    ExportError,
    build_graph_plan,
    priority_to_beads,
    select_tasks,
)
from common.io import write_json


class BeadsGraphExportTest(unittest.TestCase):
    def test_priority_to_beads_maps_known_values_and_defaults_unknown(self) -> None:
        self.assertEqual(priority_to_beads("P0"), 0)
        self.assertEqual(priority_to_beads("p1"), 1)
        self.assertEqual(priority_to_beads(" P2 "), 2)
        self.assertEqual(priority_to_beads("P3"), 3)
        self.assertEqual(priority_to_beads("urgent"), 2)
        self.assertEqual(priority_to_beads(None), 2)

    def test_build_graph_plan_maps_nodes_metadata_and_deduplicated_edges(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = Path(tmp)
            self._write_task(
                repo_root,
                "01-parent",
                {
                    "id": "parent-id",
                    "title": "Parent task",
                    "description": "Parent description",
                    "status": "in_progress",
                    "priority": "P1",
                    "assignee": "dev-a",
                    "children": ["01-child"],
                    "package": "sdk",
                    "branch": "feature/beads",
                    "base_branch": "main",
                    "commit": "abc123",
                    "pr_url": "https://example.test/pr/1",
                    "relatedFiles": ["src/A.java", "README.md"],
                },
            )
            self._write_task(
                repo_root,
                "01-child",
                {
                    "id": "child-id",
                    "title": "Child task",
                    "status": "planning",
                    "priority": "PX",
                    "parent": "01-parent",
                },
            )

            tasks = select_tasks(repo_root)
            plan = build_graph_plan(tasks, repo_root)

            self.assertEqual(plan["commit_message"], DEFAULT_COMMIT_MESSAGE)
            self.assertEqual([node["key"] for node in plan["nodes"]], ["01-child", "01-parent"])

            parent = next(node for node in plan["nodes"] if node["key"] == "01-parent")
            self.assertEqual(parent["priority"], 1)
            self.assertEqual(parent["description"], "Parent description")
            self.assertEqual(parent["assignee"], "dev-a")

            metadata = parent["metadata"]
            self.assertEqual(metadata["trellis_task_dir"], ".trellis/tasks/01-parent")
            self.assertEqual(metadata["trellis_task_id"], "parent-id")
            self.assertEqual(metadata["trellis_status"], "in_progress")
            self.assertEqual(metadata["source"], "trellis")
            self.assertEqual(metadata["package"], "sdk")
            self.assertEqual(metadata["branch"], "feature/beads")
            self.assertEqual(metadata["base_branch"], "main")
            self.assertEqual(metadata["commit"], "abc123")
            self.assertEqual(metadata["pr_url"], "https://example.test/pr/1")
            self.assertEqual(metadata["related_files"], '["src/A.java","README.md"]')
            self.assertTrue(all(isinstance(value, str) for value in metadata.values()))

            child = next(node for node in plan["nodes"] if node["key"] == "01-child")
            self.assertEqual(child["priority"], 2)
            self.assertEqual(
                plan["edges"],
                [{"from_key": "01-child", "to_key": "01-parent", "type": "parent-child"}],
            )

    def test_current_and_incomplete_filters_are_supported(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            repo_root = Path(tmp)
            self._write_task(
                repo_root,
                "01-current",
                {
                    "id": "current-id",
                    "title": "Current task",
                    "status": "in_progress",
                },
            )
            self._write_task(
                repo_root,
                "02-done",
                {
                    "id": "done-id",
                    "title": "Done task",
                    "status": "completed",
                },
            )
            current_file = repo_root / ".trellis" / ".current-task"
            current_file.write_text(".trellis/tasks/01-current", encoding="utf-8")

            current_tasks = select_tasks(repo_root, current_only=True)
            incomplete_tasks = select_tasks(repo_root, incomplete_only=True)

            self.assertEqual([task.dir_name for task in current_tasks], ["01-current"])
            self.assertEqual([task.dir_name for task in incomplete_tasks], ["01-current"])

    def test_build_graph_plan_rejects_empty_task_set(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ExportError, "No Trellis tasks matched"):
                build_graph_plan([], Path(tmp))

    def _write_task(self, repo_root: Path, dir_name: str, data: dict) -> None:
        task_dir = repo_root / ".trellis" / "tasks" / dir_name
        task_dir.mkdir(parents=True, exist_ok=True)
        write_json(task_dir / "task.json", data)


if __name__ == "__main__":
    unittest.main()
