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

* [ ] All write actions go through allowlisted backend endpoints.
* [ ] UI never allows arbitrary shell command input.
* [ ] Each mutation has a confirmation or low-risk undo/refresh behavior appropriate to its impact.
* [ ] Failed Beads commands do not leave the UI pretending success.
* [ ] Tests cover successful mutation, command failure, validation failure, and post-mutation refresh.
* [ ] Beads CLI still reflects all UI mutations immediately.

## Dependencies

* Depends on MVP acceptance.

