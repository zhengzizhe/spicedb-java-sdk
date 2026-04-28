# Beads Web UI: Trellis Execution Bridge

## Goal

Connect Beads issues to Trellis task folders and lifecycle actions from the Web UI.

## Scope

* Show whether a Beads issue has a linked Trellis folder.
* Create or claim a Trellis task folder for an existing Beads issue.
* Start/archive Trellis tasks through existing Trellis scripts.
* Display PRD, implement/check context file presence, current task, and task hierarchy.
* Keep Beads metadata and Trellis folder state synchronized.

## Acceptance Criteria

* [ ] A Beads issue can be linked to a Trellis folder without creating task.json.
* [ ] Starting a task updates Beads status and Trellis current-task state consistently.
* [ ] Archiving a task closes Beads and moves the folder to archive.
* [ ] The UI shows missing PRD/context files as actionable warnings.
* [ ] Tests cover claim, start, archive, missing folder, and Beads command failure.

## Dependencies

* Depends on core write mutations.

