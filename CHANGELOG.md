# Changelog

## 3.0.1 - 2026-04-30

### Added

- `client.schema().readRaw()`, `writeRaw(...)`, `diffRaw(...)`, and
  `refresh()` for live SpiceDB schema management.
- Stable schema result types: `SchemaReadResult`, `SchemaWriteResult`,
  `SchemaDiffResult`, and `SchemaDiff`.
- Real SpiceDB end-to-end coverage for schema read, write, diff, refresh,
  invalid schema errors, blank input fail-fast, and in-memory unsupported
  behavior.
- `CaveatContext` and generated typed caveat builders with local parameter
  name/type/required-field validation.
- Real SpiceDB end-to-end coverage for caveated relationship writes and
  caveated checks.

### Changed

- Watch/change-stream support is explicitly deferred. The SDK does not expose
  a stable `client.watch(...)` API or reintroduce Watch-backed L1 cache
  invalidation.

## 3.0.0 - 2026-04-30

This is a breaking release that finalizes the SDK's public business API around
`AuthxClient.on(...)` and real SpiceDB behavior.

### Breaking Changes

- Removed legacy public APIs: `PermissionResource`, `ResourceHandle`,
  `LookupQuery`, and the `com.authx.sdk.action` package.
- Removed `commitAsync()`. Application code that needs async dispatch should
  run the write operation on its own executor.
- Moved write listener registration before commit. `listener(...)` now returns
  `WriteListenerStage`; its terminal `commit()` returns
  `CompletableFuture<WriteCompletion>`.
- Replaced operation-specific write result records with the unified
  `WriteResult` / `WriteCompletion` model.
- Removed `CheckResult.expiresAt()` because the SDK had no real gRPC source for
  that value.
- `BulkCheckResult` now keys results by canonical subject reference, such as
  `user:alice` or `group:eng#member`, instead of bare subject id.

### Added

- Real SpiceDB Testcontainers suite with 257 end-to-end cases.
- Data correctness matrix with 200 parameterized SpiceDB checks.
- Exception-path matrix for real gRPC failures and local fail-fast validation.
- Root `README.md`, `MIGRATION.md`, and refreshed bundled
  `META-INF/authx-sdk/GUIDE.md`.
- `WriteResult.submittedUpdateCount()` and
  `submittedUpdateCountKnown()` for explicit submitted-count semantics.
- `WriteResult.unknownSubmittedUpdateCount(...)` for successful write-like RPCs
  where SpiceDB returns a revision token but no count.
- `TransportStackFactory` to keep the transport decoration order out of
  `AuthxClientBuilder`.

### Changed

- `WriteFlow.commit()` returns `WriteCompletion`; `WriteCompletion` exposes
  `updateCount()`, `count()`, `zedToken()`, and `asConsistency()`.
- `deleteByFilter` no longer reports a fake count of `0`; it now returns an
  unknown submitted update count.
- gRPC bulk-check item errors are mapped through `GrpcExceptionMapper` instead
  of being treated as denied permissions.
- Transport wrapper metadata for relationship write batches is centralized via
  `RelationshipBatchInfo`.

### Verification

- `./gradlew clean test spicedbE2eTest`
- `git diff --check`
- `rg -n '\bvar\s+[A-Za-z_$]' src test-app --glob '*.java'`
