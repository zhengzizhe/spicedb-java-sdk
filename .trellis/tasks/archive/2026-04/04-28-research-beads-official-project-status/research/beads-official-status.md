# Beads Official Project Status

Date: 2026-04-28

## Sources Checked

* Beads documentation introduction: https://gastownhall.github.io/beads/
* Beads architecture documentation: https://gastownhall.github.io/beads/architecture
* Official GitHub repository: https://github.com/gastownhall/beads

## Findings

* Beads describes `bd` as a git-backed issue tracker for AI-supervised coding workflows. Its documented focus areas are AI-native workflows, Dolt-backed storage, dependency-aware execution, formulas, and multi-agent coordination.
* Official quick-start paths include Homebrew, the install script, project initialization with `bd init --quiet`, creating work items with `bd create`, and listing unblocked work with `bd ready`.
* The official docs recommend `--json` for programmatic agent access. That matches the Trellis integration design, which routes all Beads CLI reads through `run_bd_json`.
* The GitHub README describes Beads as a distributed graph issue tracker powered by Dolt. It positions Beads as structured memory for coding agents and calls out dependency tracking, JSON output, auto-ready detection, hash-based IDs, and graph links.
* The official GitHub page showed, at lookup time, about 22.4k stars, 1.5k forks, 98 issues, 53 pull requests, 8,599 commits, and latest release `v1.0.3` dated 2026-04-24.
* The architecture docs state that Dolt is the source of truth and that Beads writes to a version-controlled SQL database, with push/pull available through Dolt remotes.

## Workflow Result

This real task used the local Beads database, not fake `bd`.

* Trellis folder: `.trellis/tasks/04-28-research-beads-official-project-status`
* Beads issue: `authcses-sdk-25o`
* Folder binding: `.bead` contains `authcses-sdk-25o`
* Beads reverse binding: `external_ref` is `trellis:.trellis/tasks/04-28-research-beads-official-project-status`
* Beads metadata includes `trellis_task_dir` and `trellis_task`

## Notes

The real test confirms the current integration model is viable: Trellis owns working files and context artifacts; Beads owns issue identity/status/dependencies and stores the Trellis execution snapshot in metadata.
