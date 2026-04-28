# Analyze how to use Beads in this project

## Goal

Decide how this project should adopt Beads as the AI-agent task backend, including the near-term trial workflow, the Trellis integration shape, and the boundaries between Beads task records and Trellis task workspaces.

## What I already know

* The user prefers Beads over Linear for this AI workflow.
* The purpose is to analyze "how to use Beads", not to implement production integration in this step.
* Prior research concluded that Beads is a good fit for agent task graph, ready queue, claiming, and dependencies.
* Prior research also concluded that Trellis task directories should remain as the PRD/research/context workspace.
* `bd` is not currently installed on this machine.
* A local `/tmp/beads/bd` binary was built from the cloned Beads repo for trial use.
* This repository does not currently have a `.beads/` directory.
* There are existing unrelated uncommitted changes in the worktree; this task should not touch source code or initialize Beads without explicit approval.
* A trial run succeeded using `BEADS_DIR=/tmp/authcses-sdk-beads-trial2/.beads` and `HOME=/tmp/authcses-sdk-beads-trial-home`.
* No `.beads/` directory was created in the repository during the trial.

## Assumptions

* Beads should be adopted first for AI-agent workflow, not as a human-facing product-management UI.
* The first usable step should be reversible and low-risk.
* Trellis remains responsible for `prd.md`, `research/`, `implement.jsonl`, `check.jsonl`, and `.trellis/.current-task`.
* Beads should eventually own task identity, dependency graph, ready queue, and claim/close lifecycle for agent work.

## Requirements

* Define a practical adoption path for Beads in this repository.
* Identify the first safe pilot mode and the commands involved.
* Define how Beads and Trellis should split responsibilities.
* Describe the metadata mapping needed to correlate Beads issues with Trellis task directories.
* Identify risks around `.beads/`, Dolt storage, concurrency, duplicate creation, UI limits, and source-of-truth drift.
* Produce a concrete next-step implementation plan without changing source code in this task.

## Acceptance Criteria

* [x] A research artifact exists under `research/`.
* [x] The plan states whether to initialize Beads now or later.
* [x] The plan includes a recommended pilot workflow.
* [x] The plan explains the Beads/Trellis responsibility split.
* [x] The plan includes concrete commands for human trial usage.
* [x] The plan identifies implementation steps for a future Beads backend.
* [x] No production code or `.beads/` state is created by this task.
* [x] A real isolated Beads pilot validates ready/claim/dependency/metadata behavior.

## Out of Scope

* Installing Beads.
* Running `bd init` in this repository.
* Creating production sync/conversion code.
* Replacing Trellis.
* Integrating Linear.

## Technical Notes

* Prior task: `.trellis/tasks/04-28-beads-task-json-handoff-research/`.
* Key prior artifacts:
  * `.trellis/tasks/04-28-beads-task-json-handoff-research/research/beads-task-json-handoff.md`
  * `.trellis/tasks/04-28-beads-task-json-handoff-research/research/beads-first-task-creation.md`
* Beads source remains cloned at `/tmp/beads`.
* Trial Beads binary: `/tmp/beads/bd`.
* Trial data directory: `/tmp/authcses-sdk-beads-trial2/.beads`.
* Trial home directory for Dolt config: `/tmp/authcses-sdk-beads-trial-home`.
* Local Beads docs inspected:
  * `/tmp/beads/docs/INSTALLING.md`
  * `/tmp/beads/docs/CLAUDE_INTEGRATION.md`
  * `/tmp/beads/docs/DOLT-BACKEND.md`
  * `/tmp/beads/README.md`
