# Logging Guidelines

> How logging is done in this project.

---

## Overview

Production SDK code uses JDK `System.Logger`. Log messages that can run under
an OpenTelemetry span should go through `LogCtx.fmt(...)` so the short trace id
is prepended when available. Structured log/MDC field names live in
`LogFields`; optional SLF4J MDC integration lives in `Slf4jMdcBridge` and is
compile-only/no-op when SLF4J is absent.

Real examples:

- `src/main/java/com/authx/sdk/transport/GrpcTransport.java` declares
  `private static final System.Logger LOG =
  System.getLogger(GrpcTransport.class.getName());`.
- `src/main/java/com/authx/sdk/trace/LogCtx.java` adds `[trace=<16hex>]` when
  a valid OTel span is current.
- `src/main/java/com/authx/sdk/trace/LogFields.java` centralizes `authx.*`
  MDC keys and suffix formatting.
- `src/main/java/com/authx/sdk/trace/Slf4jMdcBridge.java` pushes and pops MDC
  fields with try-with-resources.
- `src/test/java/com/authx/sdk/trace/LogEnrichmentIntegrationTest.java`
  verifies trace prefix, MDC push/pop, and suffix formatting together.

---

## Log Levels

- `INFO`: lifecycle and expected state transitions that operators may care
  about. Examples include SDK startup/recovery in `LifecycleManager`,
  circuit breaker transitions in `ResilientTransport`, and code generation
  progress in `AuthxCodegen`.
- `WARNING`: degraded behavior, swallowed integration failures, failed
  listener callbacks, interceptor bugs, telemetry sink failures, or bridge
  disablement. Examples include `Slf4jMdcBridge` disabling itself after MDC
  errors and user token-store implementations reporting storage failures they
  swallow per the SPI contract.
- `DEBUG`: low-level diagnostic noise that should not normally surface.
  `GrpcTransport` logs per-item bulk-check errors at `DEBUG` before treating
  that item as `NO_PERMISSION`.
- `ERROR`: lifecycle phase callback failures in `LifecycleManager`.

Do not add `System.out` logging to main SDK code. Existing `System.out` usage
is limited to scenario/demo tests such as `RealWorldScenarios`.

---

## Structured Logging

- Use `LogCtx.fmt("message {0}", arg)` for MessageFormat-style interpolation
  plus trace prefix. `LogCtx` catches formatting/OTel problems and returns the
  original message rather than breaking the call.
- Use `LogFields.suffixPerm(...)` or `LogFields.suffixRel(...)` for WARN+
  messages that need resource/permission/subject context in plain logs.
  Existing suffix format is:

```text
 [type=document res=d-1 perm=view subj=user:alice]
```

- Use `LogFields.toMdcMap(...)` and `try (Closeable ignored =
  Slf4jMdcBridge.push(mdc))` around transport calls when native MDC fields are
  needed. `InterceptorTransport` applies this pattern for check, write,
  lookup, read, and expand operations.
- MDC keys must stay in the `authx.*` namespace to avoid collisions with
  business application fields. Current keys include `authx.action`,
  `authx.resourceType`, `authx.resourceId`, `authx.permission`,
  `authx.relation`, `authx.subject`, `authx.consistency`, and
  `authx.zedToken`.

---

## What to Log

- Log lifecycle transitions and SDK degradation/recovery.
- Log circuit breaker transitions and retry budget/degradation events.
- Log swallowed integration failures when the code continues without the
  optional feature, such as user token-store failures or SLF4J MDC bridge
  disablement.
- Log interceptor failures with enough operation context to debug the resource
  and subject involved.
- Log telemetry sink failures with throttling/backoff behavior already present
  in `TelemetryReporter`.

---

## What NOT to Log

- Do not log preshared keys, bearer tokens, or gRPC metadata. `GrpcTransport`
  creates authorization metadata but does not log it.
- Avoid logging complete caveat context payloads unless the call site has a
  specific reason. `LogFields` has `authx.caveat` as a field constant, but
  current common suffix helpers only include resource, relation/permission,
  and subject.
- Do not log stack traces by printing them. Use `System.Logger.log(level,
  message, throwable)` or wrap/preserve causes as current code does.
- Do not add hard dependencies on SLF4J to the main SDK. SLF4J is compile-only
  and the bridge must remain no-op when it is absent.
