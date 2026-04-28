# Beads Web UI: API Contract and Local Server Scaffold

## Goal

Define the local Web UI API boundary and scaffold the smallest server/frontend foundation without implementing the full UI.

## Scope

* Choose the local app layout inside this repo.
* Add a local server entrypoint with health/status endpoint.
* Define typed API contracts for Beads issues, statuses, details, relationships, and errors.
* Implement a command adapter boundary that can call `bd --json` through an allowlist.
* Document local-only security assumptions and no arbitrary shell execution.

## Acceptance Criteria

* [ ] One documented command starts the local dev server.
* [ ] A health endpoint returns repo path, Beads availability, and UI API version.
* [ ] API types cover IssueSummary, IssueDetail, StatusDefinition, Relation, Comment, TrellisTaskMetadata, and CommandError.
* [ ] Beads command execution is centralized behind an allowlisted adapter.
* [ ] Tests prove unsupported commands cannot be executed through the API layer.
* [ ] No UI write operation exists yet beyond the health/status read path.

## Out of Scope

* Full board UI.
* Mutating Beads data.
* Agent/worktree execution.

