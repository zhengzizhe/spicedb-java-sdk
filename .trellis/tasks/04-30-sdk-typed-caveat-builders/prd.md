# Typed caveat builder support

## Goal

Improve caveat developer experience by generating or exposing typed builders
that validate caveat parameter names and Java value types before requests reach
SpiceDB.

## What I already know

* The SDK already supports caveat context via map-like data on write/check
  paths.
* `SchemaCache` stores reflected caveat names, parameter maps, expressions, and
  comments.
* Authzed caveats have typed parameters such as string, bool, int, timestamp,
  duration, list, map, IP address, and bytes.
* This is mainly developer experience and fail-fast quality, not a missing
  core permission capability.
* Current write path is `WriteFlow.withCaveat(String, Map<String, Object>)` /
  `withCaveat(CaveatRef)`.
* Current check path is `TypedCheckAction.withContext/given(...)` and
  `TypedCheckAllAction.withContext/given(...)`.
* `GrpcTransport.toStruct(...)` already converts map-like values into protobuf
  `Struct`, but only supports primitive/map/list values today.
* `AuthxCodegen` already emits one class per caveat, but the generated API is
  `Object... keyValues`, so it does not enforce parameter names or Java value
  types at compile time.

## Requirements

* Add a typed caveat context API that prevents common mistakes:
  * unknown parameter name
  * missing required parameter
  * unsupported Java value type
  * timestamp/duration/IP conversion confusion
* Integrate with code generation if the generated schema has caveat
  definitions.
* Preserve existing map-based escape hatch.
* Do not add reflection-heavy or dependency-heavy conversion libraries.
* Generated builders should be fluent and terminal methods should make their
  intended use obvious:
  * `ref()` for grant-time caveat binding.
  * `context()` for check-time caveat evaluation.
* Generated builders should support split context, because SpiceDB allows a
  relationship caveat to bind some parameters at write time and receive the
  rest at check time:
  * `ref()` and `context()` validate provided parameter names/types and allow
    partial context.
  * `completeRef()` and `completeContext()` require all known parameters for
    users who want full local missing-parameter validation.
* SDK core should accept the typed context object without forcing callers back
  to raw maps.
* Type conversion should be conservative:
  * use Java primitives/wrappers and standard JDK time/network types where the
    schema type is known.
  * preserve current `Map<String, Object>` escape hatch for unsupported or
    future SpiceDB caveat types.
  * do not claim to validate the CEL expression locally.

## Proposed API Shape

Generated caveat class example:

```java
// caveat not_expired(now timestamp, expires_at timestamp)
Instant expiresAt = subscription.expiresAt();
Instant now = clock.instant();

CaveatRef grantCaveat = NotExpired.builder()
    .expiresAt(expiresAt)
    .ref();

CaveatContext checkContext = NotExpired.builder()
    .now(now)
    .context();

client.on(Document)
    .select("doc-1")
    .grant(Document.Rel.VIEWER)
    .to(User, "alice")
    .withCaveat(grantCaveat)
    .commit();

CheckResult result = client.on(Document)
    .select("doc-1")
    .check(Document.Perm.VIEW)
    .given(checkContext)
    .detailedBy(User, "alice");
```

Public SDK additions:

* `com.authx.sdk.model.CaveatContext` immutable value object:
  * `Map<String, Object> values()`
  * `asMap()` alias if needed for readability
* `WriteFlow.withCaveat(String, CaveatContext)`
* `TypedCheckAction.withContext(CaveatContext)` and `given(CaveatContext)`
* `TypedCheckAllAction.withContext(CaveatContext)` and `given(CaveatContext)`
* Existing `Map<String, Object>` and `Object... keyValues` APIs remain.

## Acceptance Criteria

* [x] Generated schema can expose caveat builders for reflected caveat
  definitions.
* [x] Builders produce the same protobuf context shape as the existing map
  path.
* [x] Invalid parameter names/types fail fast locally.
* [x] Real SpiceDB e2e covers at least one caveated relationship and one
  caveated check.
* [x] Existing map-based caveat usage still works.

## Out of Scope

* General CEL interpreter.
* Replacing SpiceDB caveat evaluation.
* Watch/change streams.
* Cursor page APIs.

## Technical Notes

Start after cursor page API unless user reprioritizes. Inspect
`WriteFlow.withCaveat`, gRPC struct conversion in `GrpcTransport`, and
`AuthxCodegen` output before designing public names.

Inspected files:

* `src/main/java/com/authx/sdk/WriteFlow.java`
* `src/main/java/com/authx/sdk/TypedCheckAction.java`
* `src/main/java/com/authx/sdk/TypedCheckAllAction.java`
* `src/main/java/com/authx/sdk/model/CaveatRef.java`
* `src/main/java/com/authx/sdk/model/CheckRequest.java`
* `src/main/java/com/authx/sdk/transport/GrpcTransport.java`
* `src/main/java/com/authx/sdk/AuthxCodegen.java`
