# Exception Hierarchy — Full Coverage Map

<HARD-GATE>
When adding any new exception, gRPC mapping, or resilience handling, this document MUST be updated.
When encountering an unmapped error path, treat it as a bug — not a feature.
Goal: **every possible failure mode has a named, documented SDK exception. No raw exceptions leak to the user.**
</HARD-GATE>

## Class Hierarchy

```
RuntimeException
└── AuthxException                          # Base — all SDK exceptions extend this
    ├── AuthxTimeoutException               # DEADLINE_EXCEEDED
    ├── AuthxConnectionException            # UNAVAILABLE / CANCELLED / ABORTED
    ├── AuthxAuthException                  # UNAUTHENTICATED / PERMISSION_DENIED
    ├── AuthxResourceExhaustedException     # RESOURCE_EXHAUSTED (non-retryable)
    ├── AuthxInvalidArgumentException       # INVALID_ARGUMENT / NOT_FOUND / ALREADY_EXISTS / OUT_OF_RANGE (non-retryable)
    ├── AuthxUnimplementedException         # UNIMPLEMENTED (non-retryable)
    ├── AuthxPreconditionException          # FAILED_PRECONDITION (non-retryable)
    ├── CircuitBreakerOpenException         # Resilience4j circuit breaker OPEN
    ├── InvalidPermissionException          # Schema validation — permission not found
    ├── InvalidRelationException            # Schema validation — relation not found
    └── InvalidResourceException            # Schema validation — resource type not found
```

## gRPC Status Code → SDK Exception Mapping

Mapping location: `GrpcTransport.mapGrpcException()`

| gRPC Status Code | SDK Exception | Retryable | Circuit Breaker Counts |
|---|---|---|---|
| `DEADLINE_EXCEEDED` | AuthxTimeoutException | Yes | Yes |
| `UNAVAILABLE` | AuthxConnectionException | Yes | Yes |
| `CANCELLED` | AuthxConnectionException | Yes | Yes |
| `ABORTED` | AuthxConnectionException | Yes | Yes |
| `UNAUTHENTICATED` | AuthxAuthException | **No** | **No** |
| `PERMISSION_DENIED` | AuthxAuthException | **No** | **No** |
| `RESOURCE_EXHAUSTED` | AuthxResourceExhaustedException | **No** | **No** |
| `INVALID_ARGUMENT` | AuthxInvalidArgumentException | **No** | **No** |
| `NOT_FOUND` | AuthxInvalidArgumentException | **No** | **No** |
| `ALREADY_EXISTS` | AuthxInvalidArgumentException | **No** | **No** |
| `OUT_OF_RANGE` | AuthxInvalidArgumentException | **No** | **No** |
| `UNIMPLEMENTED` | AuthxUnimplementedException | **No** | **No** |
| `FAILED_PRECONDITION` | AuthxPreconditionException | **No** | **No** |
| `UNKNOWN` | AuthxException (default) | Yes | Yes |
| `INTERNAL` | AuthxException (default) | Yes | Yes |
| `DATA_LOSS` | AuthxException (default) | Yes | Yes |

## Resilience4j → SDK Exception Mapping

| Resilience4j Event | SDK Exception | Source |
|---|---|---|
| Circuit breaker OPEN | CircuitBreakerOpenException | ResilientTransport |
| Rate limiter rejected | AuthxException("Rate limited: ...") | Resilience4jInterceptor |
| Bulkhead rejected | AuthxException("Bulkhead rejected: ...") | Resilience4jInterceptor |
| Retry exhausted | Original exception propagated | RetryPolicy config |

### Retry Policy — Non-Retryable Exceptions

These exceptions bypass retry (immediate failure):
- CircuitBreakerOpenException
- AuthxAuthException
- AuthxResourceExhaustedException
- AuthxInvalidArgumentException
- AuthxUnimplementedException
- AuthxPreconditionException

### Circuit Breaker — Ignored Exceptions

These exceptions don't count toward failure rate:
- AuthxInvalidArgumentException
- AuthxAuthException
- AuthxResourceExhaustedException
- AuthxUnimplementedException
- AuthxPreconditionException

## Schema Validation Exceptions

Thrown by `SchemaCache` before making gRPC calls:

| Exception | When |
|---|---|
| InvalidResourceException | Resource type not in schema |
| InvalidRelationException | Relation not found for resource type |
| InvalidPermissionException | Permission not found for resource type |

Note: These only have `(String message)` constructors — no cause wrapping. This is intentional: validation failures are deterministic, not caused by external errors.

## Proactive Coverage Checklist

**Check this list when adding new features or integrations. Any gap is a bug.**

### gRPC Layer
- [ ] Every `StatusRuntimeException` goes through `mapGrpcException()` — never caught and rethrown raw
- [ ] Streaming RPCs (Watch, LookupResources, LookupSubjects, ReadRelationships) handle `StatusRuntimeException` in their iteration loops
- [ ] Streaming RPC iterators are wrapped in `CloseableGrpcIterator` and consumed inside try-with-resources, so early break / loop exception sends `RST_STREAM` to SpiceDB instead of leaking the HTTP/2 stream
- [ ] New gRPC methods added to SpiceDB get an explicit case in the mapping, not just the default

### Resilience Layer
- [ ] New Resilience4j components (TimeLimiter, etc.) have their exceptions mapped to AuthxException subclasses
- [ ] Rate limiter and bulkhead rejections produce meaningful messages with current limits
- [ ] Circuit breaker state transitions are logged (not just exceptions)

### Transport Chain
- [ ] No `catch (Exception e) { /* swallow */ }` anywhere in the transport chain
- [ ] `CompletionException` is unwrapped before propagation (CoalescingTransport)
- [ ] `InterruptedException` re-interrupts the thread after catching

### Public API Boundary
- [ ] No `StatusRuntimeException` escapes past `GrpcTransport`
- [ ] No `CallNotPermittedException` escapes past `ResilientTransport`
- [ ] No `RequestNotPermitted` escapes past `Resilience4jInterceptor`
- [ ] User-facing methods only throw `AuthxException` or its subtypes

### Background Tasks
- [ ] `WatchCacheInvalidator` catches exceptions and logs, never crashes the watch loop
- [ ] `TelemetryReporter` catches exceptions and logs, never crashes the reporting loop
- [ ] Event bus listener exceptions are isolated — one bad listener doesn't break others

## Explore: Potential Gaps to Investigate

**Actively look for these when modifying the codebase:**

| Scenario | Expected Behavior | Where to Check |
|---|---|---|
| SpiceDB returns gRPC `INTERNAL` | AuthxException with full status message | mapGrpcException default case |
| SpiceDB returns gRPC `DATA_LOSS` | AuthxException with full status message | mapGrpcException default case |
| gRPC channel DNS failure | AuthxConnectionException | UNAVAILABLE mapping |
| TLS handshake failure | AuthxConnectionException | UNAVAILABLE mapping |
| SpiceDB schema not written yet | AuthxPreconditionException | FAILED_PRECONDITION mapping |
| Token expired mid-stream | AuthxAuthException in stream iteration | Streaming RPC error handlers |
| Server closes stream unexpectedly | AuthxConnectionException | Streaming RPC error handlers |
| Caffeine cache loader throws | Wrapped in AuthxException? | CachedTransport / CaffeineCache |
| Thread pool exhausted (ForkJoinPool) | RejectedExecutionException → ? | CoalescingTransport |
| Jackson deserialization failure | RuntimeException → ? | CaveatContext handling |
| gRPC metadata parsing failure | StatusRuntimeException → mapped | GrpcTransport |
| Concurrent schema cache invalidation | Thread-safe? | SchemaCache / WatchCacheInvalidator |
| Watch stream reconnect failure | Logged, retry with backoff | WatchDispatcher |
| OTel exporter failure | Swallowed, non-fatal | TelemetryReporter |
