# Quality Guidelines

> Code quality standards for backend development.

---

## Overview

The project is a Java 21 Gradle SDK. Quality is enforced mainly through focused
unit tests, integration/e2e tests where SpiceDB or Redis behavior matters,
JSpecify nullness annotations in selected packages, and a narrow Error Prone
configuration for `@CheckReturnValue` on write flows. The codebase favors
small immutable model records, explicit builder validation, transport wrappers
with single responsibilities, and tests that lock down regressions.

Real examples:

- `build.gradle` sets Java 21, UTF-8 compilation, JUnit Platform, and Error
  Prone with only `CheckReturnValue` enabled as a warning.
- `src/main/java/com/authx/sdk/WriteFlow.java` is annotated
  `@CheckReturnValue`; terminal methods such as `commit()` are
  `@CanIgnoreReturnValue`.
- `src/test/java/com/authx/sdk/WriteFlowTest.java` covers typed write flow
  semantics and listener behavior.
- `src/test/java/com/authx/sdk/transport/GrpcExceptionMapperTest.java` covers
  transport error mapping.
- `src/test/java/com/authx/sdk/trace/LogFieldsTest.java` and
  `LogEnrichmentIntegrationTest.java` cover logging/trace helpers.

---

## Forbidden Patterns

- Do not reintroduce the removed L1 decision cache or Watch stream invalidation
  infrastructure. README breaking-change notes state these were removed; use
  SpiceDB reads and consistency modes instead.
- Do not add uncommitted typed write chains. Typed `grant`/`revoke` return a
  `WriteFlow`; chains must end in `commit()`. `WriteFlow` does not expose
  a separate async commit terminal; callers that need async dispatch own that
  at the application layer.
- Register write listeners on `WriteFlow` before commit as the last
  intermediate step, for example `.listener(done -> audit(done)).commit()`.
  `listener(...)` returns `WriteListenerStage`, and that stage's terminal
  `commit()` returns `CompletableFuture<WriteCompletion>` after the
  asynchronous listener finishes. Creating this stage seals the original
  `WriteFlow`; callers must commit through the returned stage. `WriteCompletion`
  is a result object and must not grow post-hoc listener registration APIs.
- Do not mutate builder state during `build()` in ways that stack duplicate
  behavior. `AuthxClientBuilder` builds an `effectiveInterceptors` list locally
  so repeated `build()` calls do not keep adding `ValidationInterceptor`.
- Do not bypass existing central helpers: use `GrpcExceptionMapper` for gRPC
  status mapping, `LogFields` for logging keys/suffixes, `LogCtx` for trace
  enrichment, model factories such as `ResourceRef.of(...)`, `SdkRefs` for
  fluent API subject/type/relation/permission conversions, and
  `RelationshipUpdates` for relationship update fan-out.
- Do not make optional integrations mandatory in the main SDK. Caffeine,
  Micrometer, and SLF4J are compile-only where applicable; distributed token
  storage is exposed through `DistributedTokenStore` and implemented by users.
- Do not use `System.out` in production SDK code. It appears only in demo/e2e
  scenario output.

---

## Required Patterns

- Use Java records for simple immutable model values when that matches the
  existing model layer. Compact constructors should call
  `Objects.requireNonNull` for required fields.
- Use `@Nullable` from JSpecify for nullable record components and return
  values where packages are null-marked or nullness is explicit.
- Keep public extension points in `spi` and inject implementations through
  existing builder/component hooks such as `SdkComponents`.
- Preserve causes when wrapping exceptions. Tests assert this for
  `GrpcExceptionMapper`.
- For resources that must be closed or popped, use try-with-resources.
  Existing examples include `CloseableGrpcIterator`,
  `TraceContext.SpanHandle`, and `Slf4jMdcBridge.push(...)`.
- Preserve interrupted status after catching `InterruptedException`.
- Prefer focused comments that explain non-obvious compatibility or safety
  decisions. Existing examples include `GrpcTransport.lookupSubjects(...)`
  avoiding `optionalConcreteLimit` and `RealWriteChain` explaining fail-closed
  write interceptor behavior.
- Keep fluent stage classes focused on lifecycle and state transitions. Do not
  reintroduce scattered loops that build relationship update cartesian products
  in individual stage methods; use `RelationshipUpdates` so capacity checks and
  overflow protection stay centralized.
- Treat multi-permission lookup limits as terminal-result limits. Do not push a
  user-facing `limit(n)` into each per-permission `LookupResources` call before
  computing union/intersection results; that can over-return for union and
  false-negative for intersection.
- Batch result mappers must preserve request shape. If a transport returns fewer
  bulk check results than requested, fill the missing cells as denied; if it
  returns more, ignore extras. Do not leak null placeholders or let result-count
  mismatches shift matrix cells.
- Bulk check maps must use canonical subject references (`type:id` or
  `type:id#relation`) as keys, not bare subject ids. Bare ids collide across
  subject types such as `user:alice` and `service:alice`.
- Relationship write results should use the unified `WriteResult` /
  `WriteCompletion` model. Do not reintroduce operation-specific result records
  for grant, revoke, or batch writes when the underlying SpiceDB response shape
  is the same.
- Public result models must only expose values that the SDK can source from
  SpiceDB or local request state without guessing. Do not add placeholder
  fields such as check-result expiry hints when the gRPC response does not
  provide them.
- When a write-like RPC succeeds but SpiceDB does not report a submitted-update
  count, use `WriteResult.unknownSubmittedUpdateCount(zedToken)` instead of
  overloading `0`. `0` is reserved for a known submitted-update count of zero.
- Use explicit Java local variable types in SDK source and tests. Do not use
  Java `var` declarations in `src/**/*.java` or `test-app/**/*.java`; this
  keeps generated code, examples, and reviews consistent.

---

## Testing Requirements

- Add or update focused unit tests next to the package being changed. Existing
  test layout mirrors production packages under `src/test/java/com/authx/sdk`.
- Use JUnit Jupiter and AssertJ for most assertions. Mockito is available but
  not required for simple value/transport tests.
- Use `InMemoryTransport`/`AuthxClient.inMemory()` when a test only needs SDK
  behavior and not real SpiceDB semantics.
- Use Testcontainers/e2e tests only when real SpiceDB behavior is the point of
  the change. Existing SpiceDB e2e tests live under
  `src/test/java/com/authx/sdk/e2e`.
- For SDK-to-SpiceDB protocol changes, run `./gradlew spicedbE2eTest`. This
  task deploys a real SpiceDB container and fails when Docker/Testcontainers is
  unavailable; it must cover both successful flows and mapped exception paths.
- For behavioral bug fixes, add regression tests at the lowest layer that owns
  the behavior. Examples already present include exception mapping,
  coalescing failure eviction, schema cache validation, and write-flow commit
  semantics.

---

## Lightweight Checks

- For SDK code changes, run the smallest relevant Gradle test task first, then
  broader `./gradlew test` when the blast radius is not local.
- For Java style refactors, verify no `var` declarations remain with
  `rg -n '\bvar\s+[A-Za-z_$]' src test-app --glob '*.java'`.
- For spec-only changes under `.trellis/spec`, a lightweight validation is
  enough: verify the Markdown files no longer contain bootstrap placeholders,
  referenced repo paths exist, and any task JSONL files still parse as JSONL.

---

## Code Review Checklist

- Does the change follow the existing package boundary rather than adding a
  new parallel abstraction?
- Are new public APIs documented with Javadoc and covered by tests?
- Are typed write flows committed, and are `@CheckReturnValue` /
  `@CanIgnoreReturnValue` annotations preserved where relevant?
- Are gRPC failures mapped to SDK exceptions with causes preserved?
- Are optional dependencies still optional from the main SDK's perspective?
- Are logs trace-aware and free of secrets such as preshared keys?
- Are Java local variables declared with explicit types rather than `var`?
- Did tests cover the behavior at the layer where the contract lives?
