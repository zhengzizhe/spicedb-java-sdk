#!/usr/bin/env python3
"""Show a best-effort macOS notification for Codex notify and Stop payloads.

Optional environment variables:
- CODEX_NOTIFY_TITLE: override the notification title.
- CODEX_NOTIFY_SOUND: macOS sound name; set to "none" or "off" to disable.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
from typing import Any


MAX_TITLE_LENGTH = 72
MAX_SUBTITLE_LENGTH = 140
MAX_MESSAGE_LENGTH = 260


def _compact(value: Any, fallback: str, limit: int) -> str:
    if value is None:
        return fallback
    if not isinstance(value, str):
        value = str(value)
    value = " ".join(value.split())
    if not value:
        return fallback
    if len(value) > limit:
        return value[: limit - 1].rstrip() + "..."
    return value


def _payload_from_stdin() -> dict[str, Any]:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    if isinstance(payload, dict):
        return payload
    return {}


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _task_label(repo_root: Path) -> str:
    try:
        task_path = (repo_root / ".trellis" / ".current-task").read_text().strip()
        if not task_path:
            return ""
        task_dir = repo_root / task_path
        task_json = task_dir / "task.json"
        if task_json.is_file():
            task = json.loads(task_json.read_text())
            name = task.get("name") or task.get("title") or task.get("id")
            return _compact(name, "", MAX_SUBTITLE_LENGTH)
        return _compact(Path(task_path).name, "", MAX_SUBTITLE_LENGTH)
    except Exception:
        return ""


def _context_label() -> str:
    try:
        repo_root = _repo_root()
        repo_name = _compact(repo_root.name, "repository", 80)
        task = _task_label(repo_root)
        if task:
            return _compact(f"{repo_name} / {task}", repo_name, MAX_SUBTITLE_LENGTH)
        return repo_name
    except Exception:
        return "repository"


def _first_payload_text(payload: dict[str, Any], keys: tuple[str, ...]) -> Any:
    for key in keys:
        value = payload.get(key)
        if value is not None:
            return value
    return None


def _sound_name() -> str:
    sound = os.environ.get("CODEX_NOTIFY_SOUND", "").strip()
    if sound.lower() in {"", "0", "false", "none", "off", "silent"}:
        return ""
    return _compact(sound, "", 80)


def _message_for(payload: dict[str, Any]) -> tuple[str, str, str]:
    context = _context_label()
    title = _compact(
        os.environ.get("CODEX_NOTIFY_TITLE"),
        "Codex complete",
        MAX_TITLE_LENGTH,
    )

    hook_event_name = payload.get("hook_event_name")
    if hook_event_name == "Stop":
        message = _compact(
            payload.get("last_assistant_message"),
            "Codex finished this turn.",
            MAX_MESSAGE_LENGTH,
        )
        return title, context, message

    event_type = _compact(
        payload.get("type") or payload.get("event") or payload.get("kind"),
        "notification",
        80,
    )
    message = _compact(
        _first_payload_text(
            payload,
            (
                "message",
                "last-assistant-message",
                "last_assistant_message",
                "status",
                "reason",
            ),
        ),
        "Codex finished this turn.",
        MAX_MESSAGE_LENGTH,
    )
    subtitle = _compact(
        f"{event_type.replace('_', ' ').replace('-', ' ')} - {context}",
        context,
        MAX_SUBTITLE_LENGTH,
    )
    return title, subtitle, message


def _run_notification(command: list[str]) -> bool:
    try:
        result = subprocess.run(
            command,
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=5,
        )
        return result.returncode == 0
    except Exception:
        return False


def _notify_with_osascript(title: str, subtitle: str, message: str, sound: str) -> None:
    _run_notification(
        [
            "osascript",
            "-e",
            "on run argv",
            "-e",
            "if (count of argv) is greater than 3 and (item 4 of argv) is not \"\" then",
            "-e",
            "display notification (item 3 of argv) with title (item 1 of argv) subtitle (item 2 of argv) sound name (item 4 of argv)",
            "-e",
            "else",
            "-e",
            "display notification (item 3 of argv) with title (item 1 of argv) subtitle (item 2 of argv)",
            "-e",
            "end if",
            "-e",
            "end run",
            title,
            subtitle,
            message,
            sound,
        ]
    )


def _deliver(title: str, subtitle: str, message: str) -> None:
    sound = _sound_name()
    _notify_with_osascript(title, subtitle, message, sound)


def main() -> int:
    payload = _payload_from_stdin()
    title, subtitle, message = _message_for(payload)
    is_stop_hook = payload.get("hook_event_name") == "Stop"

    try:
        _deliver(title, subtitle, message)
    except Exception:
        pass
    if is_stop_hook:
        print(json.dumps({"continue": True}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
