# Beads Web UI: MVP Acceptance and Docs

## Goal

Verify the read-only Beads Web UI MVP is usable, documented, and safe before adding write operations.

## Scope

* Run backend unit tests, frontend tests, typecheck, lint, and build.
* Verify local startup from a clean checkout with Beads available.
* Verify UI against a real Beads DB with parent/child and dependency data.
* Verify Web UI implementation files live under `/Users/cses-38/workspace/beads-web-ui`, not under `/Users/cses-38/workspace/authcses-sdk`.
* Document run commands, security boundary, known limitations, and rollback.
* Record screenshots or notes for desktop and narrow viewport smoke checks.

## Acceptance Criteria

* [ ] Health/status endpoint passes with real Beads installed.
* [ ] Product source, tests, package files, and docs are located under `/Users/cses-38/workspace/beads-web-ui`.
* [ ] Board/list/detail/relationship/ready queue flows work against a real local Beads database.
* [ ] Tests and build pass from the documented command set.
* [ ] The app cannot mutate Beads data in MVP.
* [ ] Documentation explains that Beads remains usable through CLI if the UI is disabled.
* [ ] Open follow-up issues exist for mutations, Trellis bridge, and agent/review expansion.

## Dependencies

* Depends on API contract, read-only data service, board/list/detail UI, and relationships/queue.
