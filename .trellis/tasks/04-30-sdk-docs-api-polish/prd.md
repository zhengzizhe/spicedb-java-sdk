# SDK docs and public API polish

## Goal

Make the SDK's public documentation and release notes match the latest API
surface, remove caveat examples that can be mistaken for hard-coded business
rules, and explicitly defer Watch/change-stream support instead of presenting
it as a near-term feature.

## What I already know

* Schema management API and typed caveat builder support have been implemented.
* The user flagged `.clientIp("10.0.0.5")` as misleading because it can read as
  a hard-coded SDK condition.
* Watch/change stream is considered too operationally fragile for a polished
  business API right now.
* Existing docs include root `README.md`, `MIGRATION.md`, `CHANGELOG.md`,
  `RELEASE.md`, and bundled `src/main/resources/META-INF/authx-sdk/GUIDE.md`.

## Assumptions

* This is a docs/API polish task, not a new runtime feature task.
* Watch should be documented as intentionally deferred/unsupported for now.
* Caveat examples should use domain-neutral runtime variables such as
  `expiresAt`, `clock.instant()`, or `requestContext` rather than literal IP
  allowlist examples.

## Requirements

* Audit public docs and generated examples for stale API names or misleading
  examples.
* Replace caveat examples that look like SDK-owned policy conditions with
  examples where values clearly come from application runtime state.
* Make Watch/change-stream status explicit: no formal API in this release; use
  business-side audit/write hooks or own a serious token/idempotency/rebuild
  model if required.
* Ensure README, bundled GUIDE, CHANGELOG, RELEASE, and MIGRATION are
  consistent with the current API.
* Do not change runtime semantics unless a doc/API mismatch exposes a real bug.

## Acceptance Criteria

* [x] Root README caveat examples no longer imply hard-coded SDK business
  conditions.
* [x] Bundled GUIDE caveat examples are aligned with README.
* [x] Watch/change-stream is explicitly deferred and not marketed as a current
  feature.
* [x] Release/migration notes mention schema management and typed caveat
  builders accurately.
* [x] Documentation references current write listener and schema result APIs.
* [x] `git diff --check` passes.

## Definition of Done

* Docs updated.
* Small compile/test check run if examples/API references touch generated
  class expectations.
* Trellis context validates.

## Out of Scope

* Implementing Watch/change streams.
* Implementing cursor page API.
* Changing published coordinates or doing another Maven Central release.
* Large runtime refactors.

## Technical Notes

Likely files:

* `README.md`
* `MIGRATION.md`
* `CHANGELOG.md`
* `RELEASE.md`
* `src/main/resources/META-INF/authx-sdk/GUIDE.md`
* `.trellis/tasks/04-30-sdk-watch-change-stream-design/prd.md`
