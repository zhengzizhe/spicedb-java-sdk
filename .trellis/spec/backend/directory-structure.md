# Directory Structure

> How backend code is organized in this project.

---

## Overview

This repository is a Java 21 Gradle SDK for SpiceDB, plus a Spring test
application. It is not organized as a web service with
routes/controllers/services in the main module. The main SDK code lives under
`src/main/java/com/authx/sdk` and is split by SDK responsibility: public API,
action builders, transport adapters, SPI, model records, observability, and
policy/lifecycle support.

Real examples:

- `src/main/java/com/authx/sdk/AuthxClient.java` and
  `src/main/java/com/authx/sdk/AuthxClientBuilder.java` expose the main public
  API.
- `src/main/java/com/authx/sdk/transport/GrpcTransport.java`,
  `ResilientTransport.java`, and `InterceptorTransport.java` hold transport
  composition and SpiceDB gRPC mapping.
- `src/main/java/com/authx/sdk/model/CheckRequest.java`,
  `ResourceRef.java`, and `Tuple.java` are immutable request/value objects.
- `test-app/src/main/java/com/authx/testapp/PermissionController.java` is a
  Spring demo/test-app controller, not the pattern for main SDK code.

---

## Directory Layout

```text
src/main/java/com/authx/sdk/
|-- builtin/      Built-in interceptors such as validation/debug/resilience.
|-- cache/        Schema cache used for schema reflection and validation.
|-- event/        Typed SDK event bus and event types.
|-- exception/    SDK exception hierarchy.
|-- health/       Health probes for gRPC channel and schema reads.
|-- internal/     Internal config/infrastructure/observability holders.
|-- lifecycle/    SDK phase tracking and startup/degradation events.
|-- metrics/      In-process SDK metrics.
|-- model/        Immutable request/result/value records.
|-- policy/       Retry, circuit breaker, consistency, and policy registry.
|-- spi/          Public extension points.
|-- telemetry/    Periodic telemetry reporting.
|-- trace/        OpenTelemetry, trace-context, log field, and MDC helpers.
`-- transport/    SdkTransport implementations and gRPC conversion.

src/test/java/com/authx/sdk/
|-- <same package names as main code for focused unit tests>
`-- e2e/          Testcontainers/direct SpiceDB scenario tests.

test-app/
`-- test-app/src/main/java/com/authx/testapp/
```

---

## Module Organization

- Put public fluent API entry points in the root `com.authx.sdk` package when
  callers are expected to import them directly. Existing examples include
  `AuthxClient`, `TypedHandle`, `WriteFlow`, `RelationQuery`, and
  `CrossResourceBatchBuilder`. `ResourceFactory` is an internal dependency
  holder, not a public business API entry point.
- Keep root-package helper classes package-private when they support fluent
  internals but are not user-facing API. Current examples are `SdkRefs` for
  canonical subject/type/relation/permission conversion,
  `RelationshipUpdates` for write update fan-out, and
  `ResourceLookupSupport` for shared typed/dynamic lookupResources execution.
- Put immutable request/result/domain values in `com.authx.sdk.model`. The
  project commonly uses Java records with compact constructors and static
  factories, as in `ResourceRef.of(...)`, `CheckRequest.of(...)`, and
  `SubjectRef.parse(...)`.
- Put external-call logic and protocol conversion in `com.authx.sdk.transport`.
  `GrpcTransport` converts SDK models to Authzed gRPC types and maps
  `StatusRuntimeException` through `GrpcExceptionMapper`; wrapper transports
  such as `ResilientTransport`, `CoalescingTransport`, and
  `InstrumentedTransport` add behavior around a delegate.
- Put extension contracts in `com.authx.sdk.spi`, and implementations that are
  optional or built-in outside `spi`. The SDK keeps `DistributedTokenStore` as
  an SPI only; callers provide any concrete distributed token storage
  themselves.
- Put package-level nullness markers in `package-info.java` when a package uses
  JSpecify `@NullMarked`. Existing packages with this marker include `action`,
  `exception`, `model`, `model.enums`, `policy`, and `spi`.
- Keep tests in the package that matches the production class. Examples:
  `transport/GrpcExceptionMapperTest.java`, `trace/LogFieldsTest.java`,
  `model/ValueObjectTest.java`.

---

## Naming Conventions

- Production packages use `com.authx.sdk...`; optional third-party
  integrations must not be added to the main SDK if they introduce concrete
  runtime dependencies.
- Transport interfaces are named by capability: `SdkCheckTransport`,
  `SdkWriteTransport`, `SdkLookupTransport`, `SdkReadTransport`,
  `SdkExpandTransport`, with `SdkTransport` composing them.
- Wrapper transports are named for the behavior they add:
  `ForwardingTransport`, `CoalescingTransport`, `ResilientTransport`,
  `InstrumentedTransport`, `PolicyAwareConsistencyTransport`.
- Test class names mirror the behavior or class under test:
  `WriteFlowTest`, `GrpcExceptionMapperTest`, `SchemaCacheValidateSubjectTest`,
  `CoalescingTransportFailureEvictionTest`.
- Constants for logging fields are centralized in `LogFields` and use the
  `authx.*` namespace, for example `authx.resourceType` and
  `authx.permission`.

---

## Common Mistakes

- Do not add service/controller-style code to the main SDK module. Web
  controller examples belong in `test-app`, as shown by
  `PermissionController.java`.
- Do not put optional third-party integrations in the main SDK package if they
  add concrete runtime dependencies. `DistributedTokenStore` remains an SPI;
  concrete Redis/database implementations are user-owned code.
- Do not duplicate protocol conversion outside transport classes. gRPC object,
  relationship, consistency, caveat, and exception conversion already lives in
  `GrpcTransport` and `GrpcExceptionMapper`.
- Do not reintroduce removed cache/watch infrastructure. The README documents
  that L1 cache and Watch stream invalidation were removed; current read paths
  go to SpiceDB and may use SpiceDB server-side caching via consistency.
