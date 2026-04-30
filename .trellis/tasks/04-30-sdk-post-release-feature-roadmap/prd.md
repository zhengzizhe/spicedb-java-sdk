# SDK post-release feature roadmap

## Goal

Plan the next post-3.0 SDK feature sequence without mixing unrelated feature
families into one large implementation. The roadmap prioritizes work that has
clear SpiceDB API support and production value.

## What I already know

* The SDK 3.0 release is centered on `AuthxClient.on(...)`, real SpiceDB e2e
  tests, unified write results, and cleaned public API semantics.
* The original feature sequence was schema management, cursor page APIs, typed
  caveat builders, then Watch only after a serious reliability design.
* The user later deprioritized cursor page APIs because cursor pagination cannot
  support arbitrary page jumps.
* The user judged Watch/change streams too fragile and low-value for the SDK
  right now, so Watch is deferred rather than designed as a near-term API.
* Watch should not be reintroduced as an L1 cache invalidation mechanism.
* SchemaService APIs exist in the current Authzed Java dependency for
  `ReadSchema`, `WriteSchema`, `ReflectSchema`, and `DiffSchema`.
* `LookupResources` and `ReadRelationships` expose cursor fields in generated
  Java classes. `LookupSubjects` must not be promised as cursor-supported until
  proven by real SpiceDB behavior and official docs.

## Roadmap Order

1. `04-30-sdk-schema-management-api`
   Add schema management methods on `client.schema()`. This is first because it
   is backed by clear official RPCs and has immediate operational value.

2. `04-30-sdk-cursor-page-api` (deferred)
   Add page-oriented APIs only for operations with supported cursor semantics.
   Start with `LookupResources` and `ReadRelationships`.

3. `04-30-sdk-typed-caveat-builders`
   Improve caveat ergonomics and fail-fast validation through generated typed
   builders and conversion helpers.

4. `04-30-sdk-watch-change-stream-design` (deferred / no API)
   Do not expose a stable Watch API until a concrete production need justifies
   persisted resume tokens, idempotent handlers, reconnect/replay handling, and
   datastore GC-window recovery.

## Requirements

* Keep each feature family in a separate Trellis task.
* Do not start Watch implementation from this roadmap task.
* Each implementation task must include real SpiceDB e2e coverage when it
  claims server behavior.
* Public API additions must be documented in README/GUIDE or a focused docs
  file before release.

## Acceptance Criteria

* [x] Parent roadmap task exists.
* [x] Four child tasks exist and are linked in priority order.
* [x] Each child task has a PRD with scope, out-of-scope, acceptance criteria,
  and implementation notes.
* [ ] The active implementation task is set to schema management when coding
  begins.

## Out of Scope

* Implementing any feature in the roadmap parent task.
* Releasing a new version.
* Reintroducing client-side decision caching.
