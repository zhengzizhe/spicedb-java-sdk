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

Note: Resilience4j 2.4.0 requires Java 17+. This SDK targets Java 21, so no conflict. Pin version with comment.

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

### Behavioral Change: Retry/CircuitBreaker Interaction

**Before**: Retry sits BELOW CircuitBreaker in the chain. If all 3 retries fail, the circuit breaker sees ONE failure (the final exception).

**After**: Inside `ResilientTransport`, Retry wraps CircuitBreaker (`Retry(CircuitBreaker(call))`). Each individual retry attempt that fails counts as a separate circuit breaker failure.

This means the circuit breaker will open faster under sustained failures — **this is the correct behavior**. The old design under-counted failures and let the circuit breaker stay closed too long. With a default `failureRateThreshold=50%` over a `slidingWindowSize=100`, 50 failed attempts (including retries) will open the circuit. Previously, those 50 failures could represent 150+ actual failed gRPC calls (each "failure" was 3 retried attempts).

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

2. **Policy conversion**:
   - `CircuitBreakerPolicy` → `CircuitBreakerConfig`: all fields map 1:1 (slidingWindowType, failureRateThreshold, slowCallRateThreshold, waitInOpenState, etc.)
   - `RetryPolicy` → `RetryConfig`: **`maxAttempts` requires +1** — current `RetryPolicy.maxAttempts=3` means "3 retries" (4 total calls due to off-by-one bug), Resilience4j `maxAttempts=3` means "3 total calls". To preserve the same behavior while fixing the off-by-one, use `retryConfig.maxAttempts(policy.getMaxAttempts())` which gives 3 total calls (1 initial + 2 retries). This is the correct semantic for "max attempts".
   - `RetryPolicy.jitterFactor` → `IntervalFunction.ofExponentialRandomBackoff(baseDelay, multiplier, randomizationFactor)`

3. **Composition**: For each call (Resilience4j `Decorators` applies in reverse — last added is outermost):
   ```java
   Retry retry = resolveRetry(resourceType);
   CircuitBreaker breaker = resolveBreaker(resourceType);
   // Execution order: Retry → CircuitBreaker → delegate
   Supplier<T> decorated = Decorators.ofSupplier(() -> delegate.call(...))
       .withCircuitBreaker(breaker)  // inner
       .withRetry(retry)             // outer — retries wrap the breaker
       .decorate();
   return decorated.get();
   ```

4. **Fail-open**: On `CallNotPermittedException`, check if permission is in `failOpenPermissions` (resolved per resource type from `CircuitBreakerPolicy`). If yes, return `CheckResult(HAS_PERMISSION)`. Otherwise throw `CircuitBreakerOpenException`.

5. **Disabled circuit breaker**: When `CircuitBreakerPolicy.isEnabled() == false`, create a normal CircuitBreaker then call `breaker.transitionToDisabledState()`. This makes it a pass-through — all calls go through, no state tracking.

6. **Event bridging**: Map Resilience4j state transitions to existing `SdkEvent` enum values:
   ```java
   breaker.getEventPublisher().onStateTransition(event -> {
       SdkEvent sdkEvent = switch (event.getStateTransition()) {
           case CLOSED_TO_OPEN, HALF_OPEN_TO_OPEN -> SdkEvent.CIRCUIT_OPENED;
           case OPEN_TO_HALF_OPEN -> SdkEvent.CIRCUIT_HALF_OPENED;
           case HALF_OPEN_TO_CLOSED -> SdkEvent.CIRCUIT_CLOSED;
           default -> null;
       };
       if (sdkEvent != null) eventBus.fire(sdkEvent, event.getStateTransition().toString());
   });
   ```

7. **Write operations**: `writeRelationships` / `deleteRelationships` resolve policy from the first update's resourceType (same as current behavior).

8. **close()**: Iterate `breakers` and `retries` maps, call `breaker.reset()` on each, then clear both maps. Prevents state leaks in test/hot-reload scenarios.

9. **Breaker state query**: Expose `CircuitBreaker.State getCircuitBreakerState(String resourceType)` for health checks and metrics. Returns `DISABLED` if no breaker exists for that type.

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

**Acquisition order in `before()`**: Rate limiter first, then bulkhead. Rationale: rate limiter does not hold a resource (it's a permit check), so if it rejects, there's nothing to release. If bulkhead were acquired first and rate limiter rejects, the bulkhead permit would leak.

```java
public void before(OperationContext ctx) {
    if (rateLimiter != null && !rateLimiter.acquirePermission()) {
        eventBus.fire(SdkEvent.RATE_LIMITED, "Rate limited: " + ctx.action());
        throw new AuthCsesException("Rate limited");
    }
    if (bulkhead != null) {
        if (!bulkhead.tryAcquirePermission()) {
            eventBus.fire(SdkEvent.BULKHEAD_REJECTED, "Bulkhead full: " + ctx.action());
            throw new AuthCsesException("Bulkhead rejected");
        }
        ctx.setAttribute("_bulkhead_acquired", true);
    }
}
```

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
    sdkMetrics.setCircuitBreaker(breaker);
    t = new CircuitBreakerTransport(t, breaker, failOpenPerms);
}
if (telemetryEnabled) t = new InstrumentedTransport(t, reporter, metrics);

// After:
SdkTransport t = new GrpcTransport(channel, key, timeout);
var resilientTransport = new ResilientTransport(t, policies, bus);
t = resilientTransport;
if (telemetryEnabled) t = new InstrumentedTransport(t, reporter, metrics);
```

`ResilientTransport` always wraps — disabled breakers become pass-through via `transitionToDisabledState()`. Simplifies the builder.

Remove `sdkMetrics.setCircuitBreaker(breaker)` — replaced by `resilientTransport.getCircuitBreakerState(resourceType)`.

`inMemory()` factory: no change — raw `InMemoryTransport` is not wrapped with `ResilientTransport` (testing only).

### `metrics/SdkMetrics.java`

Replace rolling buffer with HdrHistogram:

```java
// Before:
private static final int LATENCY_WINDOW = 10_000;
private final long[] latencyBuffer = new long[LATENCY_WINDOW];
private final AtomicLong latencyIndex = new AtomicLong(0);

// After:
private final Recorder recorder = new Recorder(60_000_000L, 3);
// 60 seconds max in micros (60_000_000), 3 significant digits
// High enough to capture any realistic request latency
private volatile Histogram intervalHistogram;
```

Recording: `recorder.recordValue(Math.min(latencyMicros, 60_000_000L))` — thread-safe, lock-free. Clamp to max to prevent `ArrayIndexOutOfBoundsException`.

Reading: `recorder.getIntervalHistogram(intervalHistogram)` swaps the active histogram. Called in `snapshot()`.

Percentile methods: `histogram.getValueAtPercentile(50.0) / 1000.0` for p50 in ms.

**Keep `circuitBreakerState` in Snapshot**: Instead of removing it (which would break `client.metrics().snapshot()`), populate it via a `Supplier<String>`:

```java
private volatile Supplier<String> circuitBreakerStateSupplier = () -> "N/A";

public void setCircuitBreakerStateSupplier(Supplier<String> supplier) {
    this.circuitBreakerStateSupplier = supplier;
}

public String circuitBreakerState() {
    return circuitBreakerStateSupplier.get();
}
```

In `AuthCsesClient.Builder.build()`, wire it:
```java
sdkMetrics.setCircuitBreakerStateSupplier(() ->
    resilientTransport.getCircuitBreakerState("_default").name());
```

### `transport/WatchCacheInvalidator.java`

Two fixes:

1. **close() adds join + proper shutdown order**:
   ```java
   public void close() {
       running.set(false);
       watchThread.interrupt();
       try { watchThread.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
       // Watch thread is stopped — safe to shut down listener executor
       if (listenerExecutor instanceof ExecutorService es) {
           es.shutdown();
           try { es.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
       }
       if (ownsChannel) {
           try { channel.shutdown().awaitTermination(3, TimeUnit.SECONDS); }
           catch (InterruptedException e) { Thread.currentThread().interrupt(); channel.shutdownNow(); }
       }
   }
   ```

2. **Listener dispatch goes async**:
   ```java
   private final Executor listenerExecutor = Executors.newSingleThreadExecutor(r -> {
       Thread t = new Thread(r, "authcses-sdk-watch-dispatch");
       t.setDaemon(true);
       return t;
   });

   // In watchLoop, replace direct listener.accept(change) with:
   listenerExecutor.execute(() -> {
       for (var listener : listeners) {
           try { listener.accept(change); }
           catch (Exception e) { LOG.log(WARNING, "Watch listener error: {0}", e.getMessage()); }
       }
   });
   ```

### `transport/SdkTransport.java` + `transport/GrpcTransport.java`

Fix `checkBulkMulti` key collision.

**Problem**: Return map keyed by `permission` — duplicate permissions overwrite results.

**Analysis**: The only caller is `ResourceHandle.CheckAllAction.by()`, which checks multiple *permissions* on a *single* resource. In that use case, permission is always unique. But the method signature accepts `List<BulkCheckItem>` with potentially different resources, so the contract is broken.

**Fix**: Change return type from `Map<String, CheckResult>` to `List<CheckResult>` (ordered same as input). Callers build their own keyed maps:

```java
// SdkTransport.java — change signature:
default List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
    List<CheckResult> results = new ArrayList<>(items.size());
    for (var item : items) {
        results.add(check(item.resourceType(), item.resourceId(),
                item.permission(), item.subjectType(), item.subjectId(), consistency));
    }
    return results;
}

// GrpcTransport.java — same: return List<CheckResult> in order

// ResourceHandle.CheckAllAction.by() — build permission map from list:
List<CheckResult> results = handle.transport.checkBulkMulti(items, consistency);
Map<String, CheckResult> map = new LinkedHashMap<>();
for (int i = 0; i < permissions.length; i++) {
    map.put(permissions[i], results.get(i));
}
return new PermissionSet(map);
```

`PermissionSet` and `PermissionMatrix` unchanged.

### `CLAUDE.md`

Update to reflect post-migration state:
- Transport chain order: update to 6-layer chain
- Remove "CircuitBreaker — immutable Snapshot + CAS" constraint (delegated to Resilience4j)
- Add note: sdk-core now depends on `resilience4j-*` and `HdrHistogram`

## Policy Layer

**No changes.** `CircuitBreakerPolicy`, `RetryPolicy`, `ResourcePolicy`, `PolicyRegistry` all stay as-is. `ResilientTransport` reads from them and converts to Resilience4j configs internally.

The `CircuitBreakerPolicy` fields that were previously ignored (slidingWindowType, slowCallRateThreshold, minimumNumberOfCalls, etc.) now actually take effect since Resilience4j supports all of them.

**Note on default behavior change**: `CircuitBreakerPolicy.defaults()` has `failureRateThreshold=50%`, `slidingWindowSize=100`. Previously, the custom CircuitBreaker only tracked consecutive failures (casting threshold to int=50 consecutive). Now Resilience4j applies it as a failure *rate* over a sliding window — 50 failures out of 100 calls opens the circuit. This is more sensitive but also more correct. The default policy values are reasonable for production use.

## Exception Mapping

| Resilience4j Exception | SDK Exception |
|----------------------|---------------|
| `CallNotPermittedException` | `CircuitBreakerOpenException` (existing) |
| `RequestNotPermitted` (bulkhead) | `AuthCsesException` ("Bulkhead rejected") |
| `RequestNotPermitted` (rate limiter) | `AuthCsesException` ("Rate limited") |

`CircuitBreakerOpenException` stays in `com.authcses.sdk.exception` — business code may catch it.

## Test Changes

### Rewrite
- `circuit/CircuitBreakerTest.java` → move to `transport/ResilientTransportTest.java`, test with mock delegate

### New tests
- Retry exhaustion feeds into circuit breaker (integration that was missing before)
- `checkBulkMulti` returns correctly ordered `List<CheckResult>` with duplicate permissions
- `WatchCacheInvalidator.close()` waits for thread termination
- Disabled circuit breaker pass-through (no state tracking when disabled)
- Per-resource-type isolation (failures on "document" don't affect "folder" breaker)
- `Resilience4jInterceptor`: rate limiter rejection does not leak bulkhead permit
- `Resilience4jInterceptor`: both features disabled = no-op
- `ResilientTransport.close()` cleans up all breaker/retry instances
- HdrHistogram recording with latency > max trackable value (clamp, no exception)

## What Does NOT Change

- `policy/` — all policy classes, PolicyRegistry
- `cache/` — all cache classes, Caffeine integration
- `event/` — SdkEventBus, SdkEvent enum (uses existing CIRCUIT_OPENED/HALF_OPENED/CLOSED)
- `model/` — all domain models (PermissionSet, CheckResult, etc.)
- `spi/` — all SPIs
- `telemetry/` — TelemetryReporter
- `trace/` — TraceContext
- Business API (`AuthCsesClient.on()`, `ResourceHandle`, `ResourceFactory`, etc.)
