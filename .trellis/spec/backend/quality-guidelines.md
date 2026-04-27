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
  `WriteFlow`; chains must end in `commit()` or `commitAsync()`.
- Do not mutate builder state during `build()` in ways that stack duplicate
  behavior. `AuthxClientBuilder` builds an `effectiveInterceptors` list locally
  so repeated `build()` calls do not keep adding `ValidationInterceptor`.
- Do not bypass existing central helpers: use `GrpcExceptionMapper` for gRPC
  status mapping, `LogFields` for logging keys/suffixes, `LogCtx` for trace
  enrichment, and model factories such as `ResourceRef.of(...)`.
- Do not make optional integrations mandatory in the main SDK. Caffeine,
  Micrometer, and SLF4J are compile-only where applicable; Redis support is in
  `sdk-redisson`.
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
- Use explicit Java local variable types in SDK source and tests. Do not use
  Java `var` declarations in `src/**/*.java`, `sdk-redisson/**/*.java`, or
  `test-app/**/*.java`; this keeps generated code, examples, and reviews
  consistent.

---

## Testing Requirements

- Add or update focused unit tests next to the package being changed. Existing
  test layout mirrors production packages under `src/test/java/com/authx/sdk`.
- Use JUnit Jupiter and AssertJ for most assertions. Mockito is available but
  not required for simple value/transport tests.
- Use `InMemoryTransport`/`AuthxClient.inMemory()` when a test only needs SDK
  behavior and not real SpiceDB semantics.
- Use Testcontainers/e2e tests only when real SpiceDB or Redis behavior is the
  point of the change. Existing locations are `src/test/java/com/authx/sdk/e2e`
  and `sdk-redisson/src/test/java/com/authx/sdk/redisson`.
- For behavioral bug fixes, add regression tests at the lowest layer that owns
  the behavior. Examples already present include exception mapping,
  coalescing failure eviction, schema cache validation, and write-flow commit
  semantics.

---

## Lightweight Checks

- For SDK code changes, run the smallest relevant Gradle test task first, then
  broader `./gradlew test` when the blast radius is not local.
- For `sdk-redisson` changes, include that module's tests when Redis behavior
  changed.
- For Java style refactors, verify no `var` declarations remain with
  `rg -n '\bvar\s+[A-Za-z_$]' src sdk-redisson test-app --glob '*.java'`.
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
