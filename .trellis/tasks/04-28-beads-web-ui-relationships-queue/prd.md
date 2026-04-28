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

* [ ] A parent task shows its children, and a child links back to its parent.
* [ ] A blocked issue shows which dependency is blocking it.
* [ ] Ready queue excludes blocked tasks.
* [ ] Relationship navigation preserves current context and selected issue.
* [ ] Large hierarchies remain scannable without nested cards inside cards.
* [ ] Tests cover parent/child render, dependency render, ready queue, and blocked explanation.

## Dependencies

* Depends on board/list/detail views.

