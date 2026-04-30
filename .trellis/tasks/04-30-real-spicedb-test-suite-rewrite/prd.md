# Real SpiceDB Test Suite Rewrite

## Goal

Replace old SDK behavior tests that only prove the in-memory fake with real
SpiceDB-backed end-to-end coverage for SDK business APIs, transport mappings,
and exception behavior. Keep only tests that are genuinely local-unit concerns
and cannot/should not require a running SpiceDB.

## What I already know

* The user wants old tests deleted and rewritten around real SpiceDB tests.
* The current suite has many `AuthxClient.inMemory()` / `new InMemoryTransport()`
  tests across root SDK, transport wrappers, typed fluent APIs, and examples.
* A real container test path now exists via `./gradlew spicedbE2eTest`.
* `spicedbE2eTest` currently starts `authzed/spicedb:v1.51.1` in real
  `serve --grpc-preshared-key ...` mode, writes schema, and fails if Docker is
  unavailable.
* Current real e2e coverage includes direct `GrpcTransport`, public
  `AuthxClient`, and mapped exception paths.

## Assumptions (temporary)

* “旧 test” means tests whose behavioral assertion depends on `InMemoryTransport`
  as a fake authorization engine.
* Pure local tests should stay when they do not exercise SpiceDB semantics:
  model records, exception mapper, lifecycle, metrics, logging, helper parsing,
  builder validation that does not perform gRPC calls, and in-process gRPC
  context/health tests.
* The new long-task validation command should be `./gradlew clean test
  spicedbE2eTest`.

## Decision

The user chose the aggressive rewrite:

* Delete old non-e2e tests instead of keeping broad local unit coverage.
* Keep and expand real SpiceDB e2e tests as the primary SDK correctness gate.
* Local helper classes used by e2e may remain, but old in-memory scenarios and
  non-e2e test classes should be removed.
* The suite must contain at least 200 real SpiceDB e2e test invocations.
* The primary focus is data correctness: written relationships, permission
  checks, relationship reads, lookupSubjects, lookupResources, and matrix
  results must match expected data.

## Requirements (evolving)

* Real SDK business-flow tests must use `spicedbE2eTest`, not
  `AuthxClient.inMemory()`.
* Real gRPC exception tests must cover wrong token, unavailable endpoint,
  deadline, invalid permission/relation, and bulk item error behavior.
* Deleted tests must be replaced by real SpiceDB assertions where they cover
  public SDK behavior.
* Local-only test classes should be removed unless they are e2e helpers.
* Add a large real-data matrix test suite with deterministic ids and expected
  relation/permission outcomes. Prefer generated parameterized cases over
  copy-pasted test bodies.
* Add a comprehensive exception matrix covering real SpiceDB failures across
  check, bulk check, write, delete, read, lookupSubjects, lookupResources,
  expand, and deleteByFilter, plus SDK fail-fast validation for malformed
  fluent API inputs.

## Acceptance Criteria (evolving)

* [x] Old non-e2e JUnit test classes are deleted.
* [x] No test source uses `AuthxClient.inMemory()` or `new InMemoryTransport()`
  as proof of SDK correctness.
* [x] `spicedbE2eTest` covers grant/revoke/check, lookupSubjects,
  lookupResources, relations/read, batch writes, batch checks, listeners where
  relevant, expand, deleteByFilter, and mapped exceptions.
* [x] `./gradlew clean test spicedbE2eTest` passes.
* [x] `spicedbE2eTest` reports zero skipped tests when Docker is available.
* [x] Remaining test source is limited to real SpiceDB e2e tests and helpers.
* [x] `spicedbE2eTest` reports at least 200 executed test invocations.
* [x] Most added cases verify normal data correctness rather than only error
  handling.
* [x] Real exception e2e coverage spans every SDK gRPC operation family and
  representative local fail-fast paths.

## Definition of Done

* Tests added/updated/deleted with a clear split between local unit tests and
  real SpiceDB e2e tests.
* Gradle verification commands pass.
* Specs updated with the real-SpiceDB testing rule.
* Final response summarizes deleted old test areas, retained unit-test areas,
  new real e2e coverage, and verification output.

## Out of Scope

* Rewriting production SDK behavior unless real e2e tests reveal a bug.
* Adding new external services beyond SpiceDB.
* Making everyday `./gradlew test` require Docker; the forced real path is
  `spicedbE2eTest`.

## Technical Notes

* Existing real e2e files:
  `src/test/java/com/authx/sdk/e2e/GrpcTransportDirectTest.java`,
  `GrpcTransportExceptionE2eTest.java`, `SdkEndToEndTest.java`,
  `SpiceDbTestServer.java`.
* Current fake-heavy files include `AuthxClientTest`, `TypedClassesTest`,
  `WriteFlowTest`, `SchemaCacheWiringTest`, `TypedChainValidationSmokeTest`,
  `ResourceLookupSupportTest`, `LookupResourcesTypedOverloadTest`, and many
  transport-wrapper tests using `new InMemoryTransport()`.
* Applicable specs: `.trellis/spec/backend/quality-guidelines.md`,
  `.trellis/spec/backend/error-handling.md`,
  `.trellis/spec/backend/directory-structure.md`.
