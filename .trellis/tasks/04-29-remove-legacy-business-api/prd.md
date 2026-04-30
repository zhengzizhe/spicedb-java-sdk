# Remove Legacy Business API

## Goal

Clean up the SDK's public business API so new business code has one clear typed/dynamic chain shape, with fail-fast validation for empty fluent calls.

## What I Already Know

* The preferred business API is `client.on(ResourceType).select(id)...`.
* The dynamic equivalent is `client.on("type").select(id)...`.
* Legacy business surfaces still expose `client.resource(...)`, `client.create(ResourceFactory)`, and `ResourceHandle` immediate-write actions.
* `WriteFlow.commitAsync()` currently exposes async write behavior the user wants removed.
* Empty `check(...)` varargs currently do not fail fast.

## Requirements

* Remove deprecated/legacy public business APIs from `AuthxClient`.
* Remove legacy `ResourceHandle` / `ResourceFactory`-style public usage from tests and demos where it blocks compilation.
* Remove outdated API names from docs/Javadoc in touched files.
* Make empty varargs/collection business API calls fail fast where a caller clearly intended to provide at least one item.
* Remove `WriteFlow.commitAsync()` from the public API.
* Move write listeners onto `WriteFlow` as pre-`commit()` intermediate methods; `listener(...)` returns a listener stage whose terminal `commit()` returns `CompletableFuture<WriteCompletion>`.

## Acceptance Criteria

* [x] `AuthxClient` no longer exposes legacy business entry points that compete with `client.on(...).select(...)`.
* [x] `WriteFlow` no longer exposes `commitAsync()`.
* [x] `WriteFlow.listener(...)` can be registered before `commit()`, returns a distinct listener stage, and `WriteCompletion` no longer registers post-hoc listeners.
* [x] Empty `select`, `check`, `grant`, `revoke`, batch add, and similar public fluent calls throw `IllegalArgumentException` where applicable.
* [x] Tests compile and pass.
* [x] Demo app compiles against the cleaned public API.

## Out of Scope

* Transport protocol changes.
* SpiceDB schema semantics changes.
* New async API replacement.
* Maven release/version changes.

## Technical Notes

* Likely impacted files: `AuthxClient`, `DynamicResourceEntry`, `DynamicHandle`, `TypedResourceEntry`, `TypedHandle`, `WriteFlow`, batch builders, and tests using removed APIs.
* This is a breaking public API cleanup by request.
