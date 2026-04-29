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

* [x] Health/status endpoint passes with real Beads installed.
* [x] Product source, tests, package files, and docs are located under `/Users/cses-38/workspace/beads-web-ui`.
* [x] Board/list/detail/relationship/ready queue flows work against a real local Beads database.
* [x] Tests and build pass from the documented command set.
* [x] The app cannot mutate Beads data in MVP.
* [x] Documentation explains that Beads remains usable through CLI if the UI is disabled.
* [x] Open follow-up issues exist for mutations, Trellis bridge, and agent/review expansion.

## Verification

* Product commit: `11431ad Document read-only MVP acceptance`.
* `pnpm test` passed in `/Users/cses-38/workspace/beads-web-ui` with 24 tests.
* `pnpm lint` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `pnpm build` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `git diff --check` passed.
* Endpoint smoke passed against `/Users/cses-38/workspace/authcses-sdk` using `/tmp/beads/bd`: `/api/health`, `/api/statuses`, `/api/issues`, `/api/ready`, `/api/issues/authcses-sdk-cwm.5`, `POST /api/issues` returns 405, and `/` serves the app shell.
* Documentation updated in `/Users/cses-38/workspace/beads-web-ui/README.md` and `/Users/cses-38/workspace/beads-web-ui/docs/mvp-acceptance.md`.

## Dependencies

* Depends on API contract, read-only data service, board/list/detail UI, and relationships/queue.
