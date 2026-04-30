# Error Handling

> How errors are handled in this project.

---

## Overview

The SDK exposes a typed runtime exception hierarchy rooted at
`AuthxException`. SpiceDB gRPC failures are mapped once in
`GrpcExceptionMapper`. Validation and caller misuse normally throw
`IllegalArgumentException` or `NullPointerException` at the edge where invalid
input is detected. Optional SPI/integration components favor graceful
degradation when the documented contract says failures must not break callers.

Real examples:

- `src/main/java/com/authx/sdk/exception/AuthxException.java` is the base SDK
  exception and exposes `isRetryable()`.
- `src/main/java/com/authx/sdk/transport/GrpcExceptionMapper.java` maps gRPC
  status codes to SDK exceptions.
- `src/test/java/com/authx/sdk/transport/GrpcExceptionMapperTest.java`
  verifies the mapping and cause preservation.
- `src/main/java/com/authx/sdk/transport/RealCheckChain.java` and
  `RealWriteChain.java` intentionally handle interceptor exceptions
  differently for read and write paths.
- `test-app/src/main/java/com/authx/testapp/ApiExceptionHandler.java` shows
  how an application can translate SDK exceptions to HTTP responses.

---

## Error Types

- `AuthxException` is the base for SDK runtime failures. Its default
  `isRetryable()` is `false`.
- Retryable infrastructure failures include timeout/connection-style
  exceptions. Tests assert that `DEADLINE_EXCEEDED` maps to
  `AuthxTimeoutException` with `isRetryable() == true`, and `UNAVAILABLE`
  maps to `AuthxConnectionException` with `isRetryable() == true`.
- Auth and caller/schema errors are non-retryable. `UNAUTHENTICATED` and
  `PERMISSION_DENIED` map to `AuthxAuthException`; invalid/not found/already
  exists/out of range statuses map to `AuthxInvalidArgumentException`;
  `FAILED_PRECONDITION` maps to `AuthxPreconditionException`.
- Circuit breaker rejection is represented as
  `CircuitBreakerOpenException`.
- Invalid SDK inputs use JDK exceptions when they are local validation errors:
  `Objects.requireNonNull(..., "name")` for required values and
  `IllegalArgumentException` for malformed refs, invalid builder state, empty
  batches, unsupported caveat context values, and similar caller mistakes.

---

## Error Handling Patterns

- Map `StatusRuntimeException` at the transport boundary. `GrpcTransport`
  catches gRPC status failures and delegates to `GrpcExceptionMapper.map(...)`;
  callers should not need to inspect raw gRPC statuses.
- Do not turn SpiceDB bulk-check item errors into denied permissions. A denied
  permission is a valid authorization result; a bulk item error is a transport
  or request failure and must be mapped through `GrpcExceptionMapper`.
- Preserve causes when wrapping transport failures. `GrpcExceptionMapperTest`
  checks that mapped exceptions retain the original
  `StatusRuntimeException`.
- Keep lifecycle cleanup in `finally` blocks or try-with-resources. Examples
  include gRPC context detach/cancel in `GrpcTransport.withErrorHandling(...)`
  and closeable iterators in stream reads.
- Preserve interrupt status when catching `InterruptedException`. Existing
  code calls `Thread.currentThread().interrupt()` in builder shutdown and
  telemetry paths.
- Treat user interceptor bugs by path:
  - Read path: `RealCheckChain` and `RealOperationChain` log non-`Authx`
    runtime exceptions at `WARNING`, skip the broken interceptor, and continue.
  - Write path: `RealWriteChain` logs at `WARNING`, wraps non-`Authx` runtime
    exceptions in `AuthxException`, and aborts the write fail-closed.
- Keep graceful degradation where the SPI contract requires it. For example,
  user-provided `DistributedTokenStore` implementations must not throw from
  `set`, and `get` must return `null` on miss or storage failure.

---

## API Error Responses

The main SDK does not define HTTP responses. The Spring `test-app` demonstrates
one integration pattern:

- `AuthxAuthException` -> `401 Unauthorized`
- `AuthxConnectionException` and `CircuitBreakerOpenException` ->
  `503 Service Unavailable`
- `AuthxTimeoutException` -> `504 Gateway Timeout`
- `AuthxInvalidArgumentException`, `InvalidResourceException`,
  `InvalidPermissionException`, and `InvalidRelationException` ->
  `400 Bad Request`
- Other `AuthxException` -> `500 Internal Server Error`
- Response body shape is `{"error": <simple class name>, "message": <message>}`

This is an example from `test-app`, not a required SDK-level HTTP contract.

---

## Common Mistakes

- Do not leak raw `StatusRuntimeException` from new gRPC code; map it through
  `GrpcExceptionMapper`.
- Do not skip failed write interceptors and continue committing. Current code
  deliberately fail-closes writes because write interceptors may enforce audit
  or policy requirements.
- Do not convert local validation failures into generic `AuthxException` unless
  the code is preserving a typed SDK boundary. Existing model/builder code uses
  `IllegalArgumentException` and `Objects.requireNonNull` directly.
- Do not swallow errors silently. Existing graceful-degradation code logs at
  `WARNING` before disabling/skipping behavior or returning a fallback.
