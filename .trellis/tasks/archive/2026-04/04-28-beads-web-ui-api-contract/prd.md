# Beads Web UI: API Contract and Local Server Scaffold

## Goal

Define the local Web UI API boundary and scaffold the smallest server/frontend foundation in `/Users/cses-38/workspace/beads-web-ui` without implementing the full UI.

## Scope

* Create or use the sibling workspace project at `/Users/cses-38/workspace/beads-web-ui`.
* Keep all Web UI source, package files, tests, and app docs out of `/Users/cses-38/workspace/authcses-sdk`.
* Add a local server entrypoint with health/status endpoint.
* Define typed API contracts for Beads issues, statuses, details, relationships, and errors.
* Implement a command adapter boundary that can call `bd --json` through an allowlist.
* Document local-only security assumptions and no arbitrary shell execution.

## Acceptance Criteria

* [x] One documented command starts the local dev server.
* [x] A health endpoint returns Web UI project path, selected Beads target path, Beads availability, and UI API version.
* [x] API types cover IssueSummary, IssueDetail, StatusDefinition, Relation, Comment, TrellisTaskMetadata, and CommandError.
* [x] Beads command execution is centralized behind an allowlisted adapter.
* [x] Tests prove unsupported commands cannot be executed through the API layer.
* [x] No UI write operation exists yet beyond the health/status read path.
* [x] `authcses-sdk` receives no Web UI source files beyond Trellis task planning metadata.

## Verification

* `pnpm test` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `pnpm lint` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `pnpm build` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `BEADS_TARGET_PATH=/Users/cses-38/workspace/authcses-sdk pnpm dev --host 127.0.0.1` served `http://127.0.0.1:5173`.
* `GET /api/health` returned `ok: true`, project path `/Users/cses-38/workspace/beads-web-ui`, and Beads target path `/Users/cses-38/workspace/authcses-sdk`.

## Out of Scope

* Full board UI.
* Mutating Beads data.
* Agent/worktree execution.
