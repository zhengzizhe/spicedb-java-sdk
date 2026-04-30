# Schema management API

## Goal

Expose practical schema management on `client.schema()` so SDK users can read,
write, diff, and refresh SpiceDB schema through the same configured client and
auth path they already use for permissions.

## What I already know

* `AuthxClient#schema()` currently returns a read-only metadata view backed by
  `SchemaCache`.
* `AuthxClientBuilder` already creates gRPC auth metadata for schema reflection
  during startup.
* Current generated Authzed Java classes include:
  `SchemaServiceGrpc.getReadSchemaMethod`,
  `getWriteSchemaMethod`, `getReflectSchemaMethod`, and
  `getDiffSchemaMethod`.
* `ReadSchemaResponse` exposes `schemaText` and `readAt`.
* `WriteSchemaResponse` exposes `writtenAt`.
* `DiffSchemaRequest` compares the current SpiceDB schema against a
  `comparisonSchema` string and returns reflection diffs plus `readAt`.
* There is no standalone `ValidateSchema` RPC in the current `SchemaServiceGrpc`
  class, so this task must not add a fake `validateSchema()` API.

## Requirements

* Add public raw schema methods on `SchemaClient`:
  * `readRaw()`
  * `writeRaw(String schema)`
  * `diffRaw(String comparisonSchema)`
  * `refresh()`
* Preserve existing read-only metadata methods:
  `resourceTypes`, `relationsOf`, `permissionsOf`, `subjectTypesOf`,
  `getCaveatNames`, and `getCaveat`.
* `readRaw()` should return both schema text and ZedToken, not only a string.
* `writeRaw(...)` should return the write ZedToken and refresh the local
  `SchemaCache` when a cache is attached.
* `diffRaw(...)` should expose a stable SDK result shape rather than leaking
  raw generated protobufs in the public API.
* In-memory clients should fail fast with a clear unsupported-state exception
  for remote schema mutations, not silently pretend success.
* gRPC `StatusRuntimeException` must be mapped through existing
  `GrpcExceptionMapper`.
* Empty schema input must fail fast locally.

## Acceptance Criteria

* [x] `client.schema().readRaw()` reads the current SpiceDB schema.
* [x] `client.schema().writeRaw(schema)` writes schema and returns a token.
* [x] `client.schema().diffRaw(comparisonSchema)` returns deterministic diff
  data for a changed schema.
* [x] `client.schema().refresh()` reloads metadata into `SchemaCache`.
* [x] Invalid schema write maps to the SDK exception hierarchy.
* [x] Real SpiceDB e2e tests cover read, write, diff, refresh, and invalid
  schema failure.
* [x] README/GUIDE mention the schema management API.
* [x] `./gradlew clean test spicedbE2eTest` passes.

## Out of Scope

* Full migration framework with rollback plans.
* Watch/change-stream support.
* Cursor page APIs.
* Typed caveat builder generation.
* A public `validateSchema()` method unless a real supported RPC or safe
  implementation contract is introduced in a later task.

## Technical Notes

Likely files:

* `src/main/java/com/authx/sdk/SchemaClient.java`
* `src/main/java/com/authx/sdk/AuthxClient.java`
* `src/main/java/com/authx/sdk/AuthxClientBuilder.java`
* `src/main/java/com/authx/sdk/transport/SchemaLoader.java`
* New schema result records under `src/main/java/com/authx/sdk/model`
* New e2e test under `src/test/java/com/authx/sdk/e2e`

Use existing conventions:

* `GrpcExceptionMapper` for gRPC status mapping.
* `Objects.requireNonNull` / `IllegalArgumentException` for local fail-fast.
* Real SpiceDB Testcontainers tests for server behavior.
