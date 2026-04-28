# Codex Notification Hooks

> Contract for project-scoped Codex completion notification hooks.

---

## Scope

- Codex notification commands may be configured in `.codex/config.toml` with the
  official `notify` key, but this project does not wire `notify` for turn
  completion.
- Codex turn-completion notifications are configured only as a
  `.codex/hooks.json` `Stop` lifecycle hook.
- Notification hooks are local developer tooling only. They must not change SDK
  runtime behavior or alter existing Trellis lifecycle hook behavior in
  `.codex/hooks.json`.
- macOS notification styling is bounded by Notification Center. Prefer small
  content and delivery improvements over custom app infrastructure.
- Trellis `SessionStart` context injection should remain silent: do not add a
  `statusMessage` for the hook, and do not inject instructions that force a
  visible first assistant reply notice.

## Scenario: Codex macOS Completion Notifications

### 1. Scope / Trigger

- Trigger: project-local Codex lifecycle hooks for per-turn completion.
- This tooling is development-only and must not affect SDK runtime behavior.

### 2. Signatures

- The official config shape is `notify = ["<command>", "<arg>", ...]`.
- Do not add `notify = [".codex/hooks/notify-macos.py"]` for per-turn
  completion notifications. Wiring both `notify` and `Stop` to the same script
  produces duplicate notifications.
- `Stop` hook command:
  `/usr/bin/python3 "$(git rev-parse --show-toplevel)/.codex/hooks/notify-macos.py"`.

### 3. Contracts

- Codex notify commands receive JSON on `stdin` and should not write `stdout`.
- `Stop` lifecycle hooks receive JSON on `stdin` with fields such as
  `hook_event_name`, `turn_id`, and `last_assistant_message`.
- If a `Stop` hook writes to `stdout`, it must write valid JSON. Return
  `{"continue": true}` for successful best-effort notification handling.
- `.codex/hooks.json` lifecycle hooks require user-level opt-in with
  `[features] codex_hooks = true`.
- `SessionStart` may inject Trellis context through `hookSpecificOutput`
  `additionalContext`, but that injected context must not require user-visible
  terminal or chat text.
- Keep Codex notification commands and lifecycle hooks explicit. Use the
  `.codex/hooks.json` `Stop` hook, not `notify`, for turn completion.
- The same script may handle both `notify` and `Stop` hook JSON from `stdin`;
  detect Stop payloads with `hook_event_name == "Stop"`.
- Notification content should include clear Codex completion text, cheap local
  repo/task context when available, and a concise body from
  `last_assistant_message` or compatible notify payload fields.
- Use macOS native notifications through `osascript display notification`.
- Small environment-variable customization is allowed for local developer
  preference, such as `CODEX_NOTIFY_TITLE` and `CODEX_NOTIFY_SOUND`. Defaults
  must work without configuration.
- Do not implement or require a custom Swift helper app for this hook.

### 4. Validation & Error Matrix

- Empty input -> use default notification text and exit `0`.
- Malformed JSON -> use default notification text and exit `0`.
- Non-object JSON -> use default notification text and exit `0`.
- Missing known message fields -> use default notification text and exit `0`.
- Missing `terminal-notifier` -> use `osascript`.
- Missing `osascript` or notification delivery failure -> suppress the delivery
  error and exit `0`.

### 5. Good / Base / Bad Cases

- Good: `{"hook_event_name":"Stop","turn_id":"...","last_assistant_message":"Done"}`
  displays "Done", writes valid JSON stdout, and exits `0`.
- Base: `{"title":"Codex","message":"Done","type":"agent-turn-complete"}`
  displays a notification, writes no stdout, and exits `0`.
- Bad input: `{bad json` still exits `0` and writes no stdout.

### 6. Tests Required

- Validate `.codex/hooks.json` as JSON.
- Compile the Python hook script.
- Assert the hook script is executable.
- Pipe representative notify JSON to the script and assert exit code `0` with
  empty stdout.
- Pipe unknown-field JSON to the script and assert exit code `0`.
- Pipe `Stop` JSON to the script and assert exit code `0` with valid JSON
  stdout containing `continue: true`.
- Pipe malformed and empty input to the script and assert exit code `0`.
- Confirm existing `.codex/hooks.json` `SessionStart` and `UserPromptSubmit`
  entries have no unintended diff when changing notification hooks.

### 7. Wrong vs Correct

#### Wrong

```json
{
  "Stop": [
    {
      "hooks": [
        {
          "type": "command",
          "command": ".codex/hooks/notify-macos.py"
        }
      ]
    }
  ]
}
```

This relies on the current working directory and omits a short timeout.

#### Correct

```json
{
  "Stop": [
    {
      "hooks": [
        {
          "type": "command",
          "command": "/usr/bin/python3 \"$(git rev-parse --show-toplevel)/.codex/hooks/notify-macos.py\"",
          "timeout": 5
        }
      ]
    }
  ]
}
```

This resolves the script from the repository root and bounds hook runtime.

## Script Notes

- If known message fields such as `message`, `last-assistant-message`,
  `last_assistant_message`, `status`, or `reason` are absent, use safe default
  notification text.
- For `Stop` payloads, prefer `last_assistant_message` when it is useful and
  otherwise use safe default notification text.
- Normal notify payload handling must not write stdout.
- macOS hooks should invoke `osascript` with argv arguments rather than
  interpolating payload text into AppleScript source.
- `osascript` is optional. If it is unavailable or notification delivery fails,
  suppress the delivery error and still exit `0`.
- Notification hooks must perform no network calls.
