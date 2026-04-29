# Beads Web UI: Relationships, Ready Queue, and Blocked Navigation

## Goal

Make long Beads task graphs understandable by exposing parent/child, dependency, ready, and blocked flows.

## Scope

* Render parent/child tree for selected issue and visible task set.
* Render dependencies and dependents with blocked explanations.
* Provide a ready queue view based on Beads readiness.
* Add graph/tree navigation for jumping between related issues.
* Add filters for has parent, has children, blocked, ready, and has Trellis folder.

## Acceptance Criteria

* [x] A parent task shows its children, and a child links back to its parent.
* [x] A blocked issue shows which dependency is blocking it.
* [x] Ready queue excludes blocked tasks.
* [x] Relationship navigation preserves current context and selected issue.
* [x] Large hierarchies remain scannable without nested cards inside cards.
* [x] Tests cover parent/child render, dependency render, ready queue, and blocked explanation.

## Verification

* Product commits: `8b05795 Add relationship navigation and ready queue`, `5f28fdb Fix related issue selection context`.
* `pnpm test` passed in `/Users/cses-38/workspace/beads-web-ui` with 22 tests.
* `pnpm lint` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `pnpm build` passed in `/Users/cses-38/workspace/beads-web-ui`.
* `git diff --check` passed.
* Live API smoke against `/Users/cses-38/workspace/authcses-sdk` passed: health OK, selected detail contained dependencies and dependents.

## Dependencies

* Depends on board/list/detail views.
