# macOS Codex Completion Notification Hook

## Goal

Notify the developer with a native macOS notification whenever Codex finishes an agent turn/loop.

## Requirements

- Do not configure the project-scoped Codex `notify` config key for turn completion notifications.
- Add a repo-local Codex `Stop` lifecycle hook so every turn completion triggers exactly one notification.
- Implement a small script under `.codex/hooks/` that reads Codex's JSON payload from stdin.
- Support both Codex notify payloads and `Stop` hook payloads in the same script for compatibility.
- Use macOS Notification Center to display the notification, with optional
  `terminal-notifier` support when it exists on `PATH` and `osascript` as the
  reliable fallback.
- Build richer notification content from cheap local context: Codex completion
  title, repo/task subtitle, and a concise body from `last_assistant_message`
  or compatible notify payload fields.
- Allow small environment-variable customization such as
  `CODEX_NOTIFY_TITLE`, `CODEX_NOTIFY_GROUP`, and `CODEX_NOTIFY_SOUND`, with no
  configuration required.
- Keep the script safe and non-blocking:
  - never fail the Codex turn if notification delivery fails
  - tolerate missing or unknown JSON fields
  - no network calls
- Preserve existing Trellis `UserPromptSubmit` behavior exactly.
- Preserve `SessionStart` context injection behavior and JSON hook protocol,
  but keep it silent: no `statusMessage` loading text and no injected
  first-reply notice instruction.

## Acceptance Criteria

- `.codex/config.toml` does not configure `notify` to call the notification script.
- `.codex/hooks.json` configures a `Stop` hook that invokes the notification script through the repository root.
- For `hook_event_name == "Stop"`, the script emits valid JSON stdout such as `{"continue": true}` and exits `0`.
- For normal notify payloads, the script does not emit stdout.
- If `terminal-notifier` is installed, the script may use it for richer
  title/subtitle/message/group/sound presentation; if it is missing or fails,
  the script falls back to `osascript`.
- The notification script can be tested manually by piping a JSON payload to it.
- The script exits `0` even when input is empty or malformed.

## Definition of Done

- Hook script is executable.
- Manual smoke test succeeds or reports a clear macOS notification limitation.
- No unrelated files are modified.

## Technical Notes

- Codex config reference documents `notify` as an `array<string>` command invoked for notifications, receiving a JSON payload from Codex. It is distinct from lifecycle hooks and is not used for per-turn completion in this project because wiring both `notify` and `Stop` to the same script produces duplicate notifications.
- Codex hooks docs define `Stop` as a turn-scoped lifecycle event. It receives JSON stdin with fields including `hook_event_name`, `turn_id`, and `last_assistant_message`.
- `Stop` hook stdout must be valid JSON if it outputs anything.
- `.codex/hooks.json` lifecycle hooks require the user-level `[features] codex_hooks = true` feature flag.
- Native macOS Notification Center limits custom styling. `terminal-notifier`
  improves presentation controls without changing the problem domain, but it is
  optional and must not become an install requirement.
- A custom Swift helper app could offer deeper styling, but it is intentionally
  out of scope for this repository right now.
