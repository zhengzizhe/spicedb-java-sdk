# Watch change stream design spike

## Goal

Record the decision that the SDK should not expose SpiceDB Watch as a stable
business API in the current release line, and document what reliability model
would be required before revisiting it.

## What I already know

* Watch is a SpiceDB streaming API and must handle disconnects.
* Clients need to persist the last observed token and resume from it.
* Handlers must be idempotent because reconnect/replay can happen.
* History is bounded by the datastore GC window; after that, consumers may
  need full rebuild.
* Watch may be useful for audit, search-index sync, and data warehouse
  pipelines, but the user judged it too operationally fragile and low-value for
  this SDK right now.
* Business-side audit/write hooks are usually more direct and reliable for the
  current SDK audience.

## Requirements

* Do not implement Watch in the SDK.
* Document why Watch is deferred.
* Record the reliability semantics that would be required before revisiting:
  * reconnect loop
  * checkpoint handling
  * persisted resume token ownership
  * at-least-once delivery
  * idempotent handler requirement
  * GC-window failure behavior
* Make it explicit that Watch must not be used for SDK-side L1 permission
  decision cache invalidation.

## Acceptance Criteria

* [x] The task explicitly rejects a production Watch API for this release line.
* [x] The design explicitly rejects Watch-backed L1 decision caching.
* [x] The design states token persistence responsibilities.
* [x] The design includes failure modes and recovery behavior.
* [x] No production Watch API is implemented.

## Out of Scope

* Implementing Watch in this task.
* Client-side permission decision cache.
* Streaming abstraction for non-Watch APIs.

## Technical Notes

Priority is deferred. Revisit only if a user explicitly needs a durable
at-least-once event stream and accepts token persistence, idempotency, replay,
and rebuild responsibilities.
