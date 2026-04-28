"""Small adapter around the Beads ``bd`` CLI.

The rest of Trellis should not need to know how to locate ``bd``, run it,
or tolerate the current/future JSON envelope formats.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping


LOCAL_BD_FALLBACK = Path("/tmp/beads/bd")


class BeadsCliError(RuntimeError):
    """Raised when the Beads CLI cannot be executed or parsed."""


@dataclass(frozen=True)
class BeadsJsonResult:
    """Parsed JSON returned from a ``bd --json`` command."""

    command: list[str]
    payload: Any
    stdout: str
    stderr: str

    @property
    def data(self) -> Any:
        """Return envelope ``data`` when present, otherwise the raw payload."""
        return unwrap_beads_data(self.payload)


def resolve_bd_binary(env: Mapping[str, str] | None = None) -> str | None:
    """Resolve the Beads executable path without installing anything."""
    lookup_env = env or os.environ
    configured = lookup_env.get("TRELLIS_BD")
    if configured:
        return configured

    path_bd = shutil.which("bd")
    if path_bd:
        return path_bd

    if LOCAL_BD_FALLBACK.is_file() and os.access(LOCAL_BD_FALLBACK, os.X_OK):
        return str(LOCAL_BD_FALLBACK)

    return None


def run_bd_json(
    args: list[str],
    repo_root: Path,
    *,
    env: Mapping[str, str] | None = None,
    timeout: int = 60,
) -> BeadsJsonResult:
    """Run ``bd`` with ``--json`` and return parsed output."""
    bd_binary = resolve_bd_binary(env)
    if not bd_binary:
        raise BeadsCliError(
            "bd executable not found. Set TRELLIS_BD, install bd on PATH, or build /tmp/beads/bd."
        )

    if "--json" in args:
        command = [bd_binary, *args]
    else:
        command = [bd_binary, "--json", *args]

    process_env = os.environ.copy()
    if env:
        process_env.update(dict(env))

    try:
        completed = subprocess.run(
            command,
            cwd=repo_root,
            env=process_env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=timeout,
            check=False,
        )
    except OSError as exc:
        raise BeadsCliError(f"failed to run bd: {exc}") from exc
    except subprocess.TimeoutExpired as exc:
        raise BeadsCliError(f"bd command timed out: {' '.join(command)}") from exc

    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "").strip()
        if detail:
            raise BeadsCliError(f"bd failed ({completed.returncode}): {detail}")
        raise BeadsCliError(f"bd failed with exit code {completed.returncode}")

    try:
        payload = parse_bd_json_output(completed.stdout)
    except ValueError as exc:
        detail = completed.stdout.strip() or completed.stderr.strip()
        raise BeadsCliError(f"bd did not return valid JSON: {detail}") from exc

    return BeadsJsonResult(
        command=command,
        payload=payload,
        stdout=completed.stdout,
        stderr=completed.stderr,
    )


def parse_bd_json_output(stdout: str) -> Any:
    """Parse Beads JSON output, tolerating incidental text around JSON."""
    text = (stdout or "").strip()
    if not text:
        raise ValueError("empty stdout")

    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    decoder = json.JSONDecoder()
    candidates = [idx for idx, char in enumerate(text) if char in "[{"]
    for start in candidates:
        try:
            payload, _ = decoder.raw_decode(text[start:])
            return payload
        except json.JSONDecodeError:
            continue

    raise ValueError("no JSON object or array found")


def unwrap_beads_data(payload: Any) -> Any:
    """Unwrap the Beads v2-style JSON envelope when present."""
    if isinstance(payload, dict) and "data" in payload and "schema_version" in payload:
        return payload["data"]
    return payload


def issue_from_payload(payload: Any) -> dict[str, Any]:
    """Return a single Beads issue object from common create/update payloads."""
    data = unwrap_beads_data(payload)
    if isinstance(data, dict):
        return data
    if isinstance(data, list) and data and isinstance(data[0], dict):
        return data[0]
    raise BeadsCliError("bd output did not contain an issue object")


def issue_id_from_payload(payload: Any) -> str:
    """Extract a Beads issue ID from create/update JSON."""
    issue = issue_from_payload(payload)
    issue_id = str(issue.get("id") or "").strip()
    if not issue_id:
        raise BeadsCliError("bd output did not include an issue id")
    return issue_id


def issue_metadata(issue: Mapping[str, Any]) -> dict[str, Any]:
    """Return issue metadata as a dict, accepting object or JSON string."""
    metadata = issue.get("metadata")
    if isinstance(metadata, dict):
        return dict(metadata)
    if isinstance(metadata, str) and metadata.strip():
        try:
            parsed = json.loads(metadata)
        except json.JSONDecodeError:
            return {}
        if isinstance(parsed, dict):
            return parsed
    return {}
