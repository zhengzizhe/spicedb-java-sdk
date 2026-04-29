# Beads Web UI: Core Write Mutations

## Goal

Add controlled Beads write operations after the read-only MVP is accepted.

## Scope

* Create issue.
* Update title, description, status, priority, assignee, and labels.
* Close, reopen, defer, and undefer issue.
* Add comments and notes.
* Link/unlink dependencies and parent/child relationships.
* Refresh UI data after mutations and show command errors clearly.

## Acceptance Criteria

* [x] All write actions go through allowlisted backend endpoints.
* [x] UI never allows arbitrary shell command input.
* [x] Each mutation has a confirmation or low-risk undo/refresh behavior appropriate to its impact.
* [x] Failed Beads commands do not leave the UI pretending success.
* [x] Tests cover successful mutation, command failure, validation failure, and post-mutation refresh.
* [x] Beads CLI still reflects all UI mutations immediately.

## Verification

* Product commits: `8d0ac39 Add controlled Beads write mutations`, `d18119d Harden Beads mutation text arguments`.
* `pnpm test` passed in `/Users/cses-38/workspace/beads-web-ui` with 32 tests.
* `pnpm lint` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `pnpm build` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `git diff --check` passed.
* Real Beads API smoke against `/Users/cses-38/workspace/authcses-sdk` covered create, update, validation failure, command failure, comment, note, dependency link/unlink, child link/unlink, defer/undefer, close/reopen, dash-prefixed text handling, and immediate `bd show` visibility.
* Smoke issues created during verification were closed; no active smoke issue remained.

## Dependencies

* Depends on MVP acceptance.
