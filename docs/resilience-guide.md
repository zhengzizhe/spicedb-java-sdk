# Resilience Configuration Guide

The AuthX SDK provides four layers of resilience: **retry**, **circuit breaker**, **rate limiter**, and **bulkhead**. Each can be configured independently per resource type via `PolicyRegistry`.

---

## Default Values

### RetryPolicy

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxAttempts` | `3` | Total attempts (including the initial call) |
| `baseDelay` | `50ms` | Initial delay before first retry |
| `maxDelay` | `5s` | Upper bound on exponential backoff |
| `multiplier` | `2.0` | Exponential backoff multiplier |
| `jitterFactor` | `0.2` | Random jitter of +/-20% applied to each delay |

The default policy excludes these exception types from retry:

- `CircuitBreakerOpenException`
- `AuthxAuthException`
- `AuthxResourceExhaustedException`
- `AuthxInvalidArgumentException`
- `AuthxUnimplementedException`
- `AuthxPreconditionException`
- Resilience4j `CallNotPermittedException`

### CircuitBreakerPolicy

| Parameter | Default | Description |
|-----------|---------|-------------|
| `enabled` | `true` | Whether the circuit breaker is active |
| `failureRateThreshold` | `50.0%` | Failure rate that triggers OPEN state |
| `slowCallRateThreshold` | `80.0%` | Slow-call rate that triggers OPEN state |
| `slowCallDuration` | `500ms` | Calls slower than this are counted as slow |
| `slidingWindowType` | `COUNT_BASED` | Sliding window type (COUNT_BASED or TIME_BASED) |
| `slidingWindowSize` | `100` | Number of calls in the sliding window |
| `minimumNumberOfCalls` | `10` | Minimum calls before the breaker can trip |
| `waitInOpenState` | `30s` | How long the breaker stays OPEN before trying HALF_OPEN |
| `permittedCallsInHalfOpen` | `5` | Probe calls allowed in HALF_OPEN state |
| `failOpenPermissions` | `(empty)` | Permission names that return HAS_PERMISSION when the breaker is open |

The circuit breaker ignores these exceptions (they do not count as failures):

- `AuthxInvalidArgumentException`
- `AuthxAuthException`
- `AuthxResourceExhaustedException`
- `AuthxUnimplementedException`
- `AuthxPreconditionException`

### Retry Budget

`ResilientTransport` enforces a **global retry budget** to prevent retry amplification under sustained load:

- **Window**: sliding 1-second window
- **Budget**: max 20% of requests may be retries
- **Grace period**: when fewer than 25 requests have been recorded in the current window, retries are allowed freely (small sample sizes are not throttled)

When the budget is exhausted, additional retries are suppressed and a warning is logged once per resource type.

### Rate Limiter (Resilience4jInterceptor)

The rate limiter is **not enabled by default**. You must configure it explicitly via the builder:

```java
Resilience4jInterceptor.builder()
    .rateLimiter(500)   // max 500 requests per second
    .build();
```

When configured, it uses these settings:

| Parameter | Value |
|-----------|-------|
| `limitForPeriod` | user-specified (e.g. 500) |
| `limitRefreshPeriod` | `1 second` |
| `timeoutDuration` | `0ms` (fail immediately if limit exceeded) |

Rejected requests throw `AuthxException("Rate limited: max requests/second exceeded")` and publish a `SdkTypedEvent.RateLimited` event.

### Bulkhead (Resilience4jInterceptor)

The bulkhead is also **not enabled by default**. Configure it explicitly:

```java
Resilience4jInterceptor.builder()
    .bulkhead(50)   // max 50 concurrent calls
    .build();
```

| Parameter | Value |
|-----------|-------|
| `maxConcurrentCalls` | user-specified (e.g. 50) |
| `maxWaitDuration` | `100ms` |

Rejected requests throw `AuthxException("Bulkhead rejected: max concurrent requests exceeded")` and publish a `SdkTypedEvent.BulkheadRejected` event.

---

## Configuration via PolicyRegistry

`PolicyRegistry` provides hierarchical policy resolution. Per-resource-type policies override the global default; any field left null inherits from the parent.

```java
PolicyRegistry policies = PolicyRegistry.builder()
    .defaultPolicy(ResourcePolicy.builder()
        .retry(RetryPolicy.builder()
            .maxAttempts(3)
            .baseDelay(Duration.ofMillis(100))
            .build())
        .circuitBreaker(CircuitBreakerPolicy.builder()
            .failureRateThreshold(60.0)
            .waitInOpenState(Duration.ofSeconds(15))
            .build())
        .timeout(Duration.ofSeconds(5))
        .build())
    .forResourceType("document", ResourcePolicy.builder()
        .retry(RetryPolicy.builder()
            .maxAttempts(5)
            .build())
        .circuitBreaker(CircuitBreakerPolicy.builder()
            .failOpenPermissions(Set.of("view"))
            .build())
        .build())
    .forResourceType("group", ResourcePolicy.builder()
        .retry(RetryPolicy.disabled())
        .circuitBreaker(CircuitBreakerPolicy.disabled())
        .build())
    .build();

AuthxClient client = AuthxClient.builder()
    .target("localhost:50051")
    .token("my-preshared-key")
    .policies(policies)
    .build();
```

Resolution order (most specific wins):

1. Per-resource-type policy (e.g., `"document"`)
2. Global default policy
3. SDK built-in defaults (`ResourcePolicy.defaults()`)

Policies are merged at `PolicyRegistry` construction time and cached -- there is no per-call allocation on the hot path.

---

## Fail-Open Behavior

When the circuit breaker is **OPEN**, all requests for that resource type are normally rejected with `CircuitBreakerOpenException`. However, you can configure specific permissions to **fail open** -- returning `HAS_PERMISSION` instead of throwing:

```java
CircuitBreakerPolicy.builder()
    .failOpenPermissions(Set.of("view", "list"))
    .build();
```

**When to use fail-open:**

- For read-only, low-risk permissions (e.g., `view`, `list`) where granting access during an outage is preferable to blocking users entirely
- Never for sensitive write permissions (e.g., `delete`, `admin`) where false grants could cause data loss

**How it works in `ResilientTransport`:**

1. A `check()` call enters `executeWithResilience()`
2. If the circuit breaker is OPEN, `CircuitBreakerOpenException` is caught
3. If the requested permission is in `failOpenPermissions`, the SDK returns `HAS_PERMISSION` with no caveat and no ZedToken
4. Otherwise, the exception propagates to the caller

This is a deliberate availability-over-consistency tradeoff. Monitor `SdkTypedEvent.CircuitOpened` events to detect when fail-open is active, and alert your operations team.
