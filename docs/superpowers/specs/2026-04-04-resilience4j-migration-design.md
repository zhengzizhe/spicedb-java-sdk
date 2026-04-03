# Resilience4j Migration + Bug Fixes

## Goal

Replace custom circuit breaker, retry, rate limiter, and bulkhead implementations with Resilience4j. Replace the latency metrics rolling buffer with HdrHistogram. Fix all identified bugs. Zero breaking change to business API.

## Dependencies

### Added

| Dependency | Version | Size | Transitive |
|-----------|---------|------|-----------|
| `resilience4j-circuitbreaker` | 2.4.0 | ~87KB | `resilience4j-core` (96KB) + `slf4j-api` (41KB) |
| `resilience4j-retry` | 2.4.0 | ~50KB | (shared core) |
| `resilience4j-ratelimiter` | 2.4.0 | ~44KB | (shared core) |
| `resilience4j-bulkhead` | 2.4.0 | ~56KB | (shared core) |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | ~100KB | none |

Total added: ~474KB on classpath (resilience4j core shared across modules).

## Transport Chain

### Before (7 layers)

```
Interceptor → Coalescing → PolicyAwareConsistency → Cache → CircuitBreaker → Instrumented → PolicyAwareRetry → Grpc
```

### After (6 layers)

```
Interceptor → Coalescing → PolicyAwareConsistency → Cache → Instrumented → Resilient → Grpc
```

Changes:
- `CircuitBreakerTransport` + `PolicyAwareRetryTransport` merged into `ResilientTransport`
- `InstrumentedTransport` moves above `ResilientTransport` so telemetry captures retry counts and circuit breaker state

## New Files

### `transport/ResilientTransport.java`

Wraps delegate with per-resource-type Resilience4j CircuitBreaker + Retry.

```java
public class ResilientTransport implements SdkTransport {
    private final SdkTransport delegate;
    private final PolicyRegistry policyRegistry;
    private final SdkEventBus eventBus;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Retry> retries = new ConcurrentHashMap<>();
}
```

Key behaviors:

1. **Instance caching**: `breakers.computeIfAbsent(resourceType, this::createBreaker)`. Each resource type gets its own CircuitBreaker and Retry instance, configured from PolicyRegistry.

2. **Policy conversion**: `CircuitBreakerPolicy` → `CircuitBreakerConfig`, `RetryPolicy` → `RetryConfig`. All existing policy fields map 1:1 to Resilience4j config parameters.

3. **Composition**: For each call:
   ```java
   Retry retry = resolveRetry(resourceType);
   CircuitBreaker breaker = resolveBreaker(resourceType);
   Supplier<T> decorated = Decorators.ofSupplier(() -> delegate.call(...))
       .withCircuitBreaker(breaker)
       .withRetry(retry)
       .decorate();
   return decorated.get();
   ```

4. **Fail-open**: On `CallNotPermittedException`, check if permission is in `failOpenPermissions`. If yes, return `CheckResult(HAS_PERMISSION)`. Otherwise throw `CircuitBreakerOpenException`.

5. **Event bridging**: CircuitBreaker state transitions fire to SdkEventBus:
   ```java
   breaker.getEventPublisher()
       .onStateTransition(event ->
           eventBus.fire(SdkEvent.CIRCUIT_STATE_CHANGED,
               event.getStateTransition().toString()));
   ```

6. **Write operations**: `writeRelationships` / `deleteRelationships` resolve policy from the first update's resourceType (same as current behavior).

### `builtin/Resilience4jInterceptor.java`

Combined rate limiter + bulkhead interceptor using Resilience4j.

```java
public class Resilience4jInterceptor implements SdkInterceptor {
    private final RateLimiter rateLimiter;   // nullable — not enabled if not configured
    private final Bulkhead bulkhead;         // nullable — not enabled if not configured
    private final SdkEventBus eventBus;
}
```

Builder pattern:
```java
Resilience4jInterceptor.builder()
    .rateLimiter(1000)        // 1000 req/s; omit to disable
    .bulkhead(200)            // 200 concurrent; omit to disable
    .eventBus(eventBus)
    .build()
```

`before()`: acquire bulkhead permit (if enabled), then acquire rate limiter permit (if enabled). On rejection, fire event + throw `AuthCsesException`.

`after()`: release bulkhead permit via context attribute (same pattern as current `BulkheadInterceptor`).

## Deleted Files

| File | Reason |
|------|--------|
| `circuit/CircuitBreaker.java` | Replaced by `io.github.resilience4j.circuitbreaker.CircuitBreaker` |
| `transport/CircuitBreakerTransport.java` | Merged into `ResilientTransport` |
| `transport/PolicyAwareRetryTransport.java` | Merged into `ResilientTransport` |
| `builtin/RateLimiterInterceptor.java` | Replaced by `Resilience4jInterceptor` |
| `builtin/BulkheadInterceptor.java` | Replaced by `Resilience4jInterceptor` |

Also delete: `circuit/` package directory (empty after removal).

## Modified Files

### `build.gradle`

Add resilience4j and hdrhistogram dependencies.

### `AuthCsesClient.java` (Builder.build)

Transport chain assembly changes:

```java
// Before:
SdkTransport t = new GrpcTransport(channel, key, timeout);
t = new PolicyAwareRetryTransport(t, policies);
if (cbPolicy.isEnabled()) {
    var breaker = new CircuitBreaker(...);
    t = new CircuitBreakerTransport(t, breaker, failOpenPerms);
}
if (telemetryEnabled) t = new InstrumentedTransport(t, reporter, metrics);

// After:
SdkTransport t = new GrpcTransport(channel, key, timeout);
t = new ResilientTransport(t, policies, bus);
if (telemetryEnabled) t = new InstrumentedTransport(t, reporter, metrics);
```

`ResilientTransport` always wraps — if circuit breaker is disabled for a resource type, it creates a disabled breaker (pass-through). Simplifies the builder.

`sdkMetrics.setCircuitBreaker(breaker)` call is removed — metrics now reads circuit breaker state from `ResilientTransport` directly (or we bridge via events).

### `metrics/SdkMetrics.java`

Replace rolling buffer with HdrHistogram:

```java
// Before:
private final long[] latencyBuffer = new long[LATENCY_WINDOW];
private final AtomicLong latencyIndex = new AtomicLong(0);

// After:
private final Recorder recorder = new Recorder(3_600_000_000L, 3);
// 3.6s max in micros, 3 significant digits
private volatile Histogram intervalHistogram;
```

Recording: `recorder.recordValue(latencyMicros)` — thread-safe, lock-free.

Reading: `recorder.getIntervalHistogram(intervalHistogram)` swaps the active histogram. Called in `snapshot()` or on a timer.

Percentile methods: `histogram.getValueAtPercentile(50.0) / 1000.0` for p50 in ms.

Remove `circuitBreaker` field and `setCircuitBreaker()` / `circuitBreakerState()` — circuit breaker state is no longer tracked here. Instead, `AuthCsesClient.health()` or event bus can be used to query circuit state.

### `transport/WatchCacheInvalidator.java`

Two fixes:

1. **close() adds join**:
   ```java
   public void close() {
       running.set(false);
       watchThread.interrupt();
       try { watchThread.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
       if (ownsChannel) { ... }
   }
   ```

2. **Listener dispatch goes async**: Wrap listener invocations in an Executor so the watch thread is never blocked by slow listeners:
   ```java
   // In constructor or field:
   private final Executor listenerExecutor = Executors.newSingleThreadExecutor(r -> {
       Thread t = new Thread(r, "authcses-sdk-watch-dispatch");
       t.setDaemon(true);
       return t;
   });

   // In watchLoop, replace direct listener.accept(change) with:
   listenerExecutor.execute(() -> {
       for (var listener : listeners) {
           try { listener.accept(change); }
           catch (Exception e) { LOG.log(...); }
       }
   });
   ```

   Close also shuts down this executor.

### `transport/GrpcTransport.java` + `transport/SdkTransport.java`

Fix `checkBulkMulti` key collision. Change result map key from `permission` to a compound key:

```java
// Before:
results.put(items.get(i).permission(), cr);

// After:
var item = items.get(i);
String key = item.resourceType() + ":" + item.resourceId() + "#" + item.permission()
    + "@" + item.subjectType() + ":" + item.subjectId();
results.put(key, cr);
```

Same fix in `SdkTransport.checkBulkMulti` default method.

## Policy Layer

**No changes.** `CircuitBreakerPolicy`, `RetryPolicy`, `ResourcePolicy`, `PolicyRegistry` all stay as-is. `ResilientTransport` reads from them and converts to Resilience4j configs internally.

The `CircuitBreakerPolicy` fields that were previously ignored (slidingWindowType, slowCallRateThreshold, minimumNumberOfCalls, etc.) now actually take effect since Resilience4j supports all of them.

## Exception Mapping

| Resilience4j Exception | SDK Exception |
|----------------------|---------------|
| `CallNotPermittedException` | `CircuitBreakerOpenException` (existing) |
| `RequestNotPermitted` (bulkhead) | `AuthCsesException` ("Bulkhead rejected") |
| `RequestNotPermitted` (rate limiter) | `AuthCsesException` ("Rate limited") |

`CircuitBreakerOpenException` stays in `com.authcses.sdk.exception` — business code may catch it.

## Test Changes

- `circuit/CircuitBreakerTest.java` → rewrite to test `ResilientTransport` with mock delegate
- Existing tests that reference `CircuitBreaker` directly → update imports
- Add test: retry exhaustion feeds into circuit breaker (the integration that was missing before)
- Add test: `checkBulkMulti` with duplicate permissions returns distinct results
- Add test: `WatchCacheInvalidator.close()` waits for thread termination

## What Does NOT Change

- `policy/` — all policy classes, PolicyRegistry
- `cache/` — all cache classes, Caffeine integration
- `event/` — SdkEventBus, events
- `model/` — all domain models
- `spi/` — all SPIs
- `telemetry/` — TelemetryReporter
- `trace/` — TraceContext
- Business API (`AuthCsesClient.on()`, `ResourceHandle`, `ResourceFactory`, etc.)
