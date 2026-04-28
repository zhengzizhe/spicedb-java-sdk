# macOS Codex Completion Notification Hook

## Goal

Superseded. The repository should no longer provide a macOS Codex turn-completion
notification tool or lifecycle hook.

## Requirements

- Remove the repo-local Codex `Stop` lifecycle hook that delivered completion
  alerts.
- Remove the local alert script and its image asset.
- Preserve existing Trellis `SessionStart` and `UserPromptSubmit` hooks exactly.
- Keep active Trellis tooling guidance aligned with the removal so future work
  does not reintroduce the old completion alert by default.

## Acceptance Criteria

- `.codex/hooks.json` remains valid JSON and contains only the Trellis
  `SessionStart` and `UserPromptSubmit` lifecycle hooks.
- The old local alert script and image asset are absent.
- Active project specs and task guidance no longer instruct agents to use the
  removed completion alert.
- Repository search finds no active references to the removed alert script,
  environment variables, helper commands, or image asset name.

## Definition of Done

- Lightweight validation passes.
- No unrelated files are modified.

## Technical Notes

- This task records the former implementation request for history, but the
  current expected repository state is removal.
