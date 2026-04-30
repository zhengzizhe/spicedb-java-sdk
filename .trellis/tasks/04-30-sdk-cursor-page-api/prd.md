# Lookup and read cursor page API

## Goal

Add explicit page-oriented APIs for large result sets where SpiceDB supports
cursor semantics. The API should make paging intentional instead of overloading
current `limit(...)` methods that return only Java-side collected lists.

## What I already know

* `LookupResourcesRequest` in the current Authzed Java dependency has
  `optionalLimit` and `optionalCursor`.
* `ReadRelationshipsRequest` has `optionalLimit` and `optionalCursor`.
* The SDK currently exposes list-returning `lookupResources`,
  `lookupSubjects`, and `relations/readRelationships` paths.
* `LookupSubjects` cursor support should not be promised by the SDK until it is
  proven by official docs and real SpiceDB behavior.

## Requirements

* Add page results only for supported operations:
  * `LookupResources`
  * `ReadRelationships` / relation query paths
* Design a stable SDK page model such as `Page<T>` with:
  * `items`
  * `nextCursor`
  * `hasNext`
  * consistency token if available from the RPC response
* Existing list-returning methods must keep their current behavior.
* Page size must fail fast for negative or zero values where invalid.
* Cursor values must be opaque to callers.
* Tests must prove page continuation against real SpiceDB data.

## Acceptance Criteria

* [ ] `lookupResources(...).page(size)` returns a page and next cursor when
  more results exist.
* [ ] relation/read relationship page API returns tuples with next cursor.
* [ ] Existing `limit(...).can(...)` and `relations(...).fetch()` behavior is
  unchanged.
* [ ] Real SpiceDB e2e covers at least two pages for lookup resources and
  read relationships.
* [ ] SDK does not expose cursor API for `LookupSubjects` unless separately
  validated and documented.

## Out of Scope

* Watch/change streams.
* Schema management.
* Automatic full-result pagination helpers that hide performance costs.
* Promise of stable cursor format.

## Technical Notes

Start after `04-30-sdk-schema-management-api`. Reuse existing request records
where possible, but add new page-specific request/result records if it avoids
ambiguous `limit` semantics.
