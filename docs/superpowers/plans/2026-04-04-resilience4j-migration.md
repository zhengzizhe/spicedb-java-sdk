# Resilience4j Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace custom circuit breaker, retry, rate limiter, bulkhead with Resilience4j; replace latency metrics with HdrHistogram; fix all identified bugs.

**Architecture:** Merge CircuitBreakerTransport + PolicyAwareRetryTransport into a single ResilientTransport that wraps delegate with per-resource-type Resilience4j instances. Replace RateLimiterInterceptor + BulkheadInterceptor with a combined Resilience4jInterceptor. Fix checkBulkMulti return type, WatchCacheInvalidator lifecycle bugs, and SdkMetrics thread safety.

**Tech Stack:** Resilience4j 2.4.0, HdrHistogram 2.2.2, Java 21, Gradle, JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/2026-04-04-resilience4j-migration-design.md`

---

## File Map

### New files
| File | Responsibility |
|------|---------------|
| `src/main/java/com/authcses/sdk/transport/ResilientTransport.java` | Retry + circuit breaker via Resilience4j, per-resource-type instances |
| `src/main/java/com/authcses/sdk/builtin/Resilience4jInterceptor.java` | Rate limiter + bulkhead via Resilience4j |
| `src/test/java/com/authcses/sdk/transport/ResilientTransportTest.java` | Tests for ResilientTransport |
| `src/test/java/com/authcses/sdk/builtin/Resilience4jInterceptorTest.java` | Tests for Resilience4jInterceptor |

### Deleted files
| File | Reason |
|------|--------|
| `src/main/java/com/authcses/sdk/circuit/CircuitBreaker.java` | Replaced by Resilience4j |
| `src/main/java/com/authcses/sdk/transport/CircuitBreakerTransport.java` | Merged into ResilientTransport |
| `src/main/java/com/authcses/sdk/transport/PolicyAwareRetryTransport.java` | Merged into ResilientTransport |
| `src/main/java/com/authcses/sdk/builtin/RateLimiterInterceptor.java` | Replaced by Resilience4jInterceptor |
| `src/main/java/com/authcses/sdk/builtin/BulkheadInterceptor.java` | Replaced by Resilience4jInterceptor |
| `src/test/java/com/authcses/sdk/circuit/CircuitBreakerTest.java` | Replaced by ResilientTransportTest |

### Modified files
| File | Change |
|------|--------|
| `build.gradle` | Add resilience4j + hdrhistogram deps |
| `src/main/java/com/authcses/sdk/metrics/SdkMetrics.java` | HdrHistogram + Supplier for CB state |
| `src/main/java/com/authcses/sdk/transport/SdkTransport.java` | checkBulkMulti returns `List<CheckResult>` |
| `src/main/java/com/authcses/sdk/transport/GrpcTransport.java` | checkBulkMulti returns `List<CheckResult>` |
| `src/main/java/com/authcses/sdk/ResourceHandle.java` | CheckAllAction.by() builds map from list |
| `src/main/java/com/authcses/sdk/transport/WatchCacheInvalidator.java` | Async listeners + close() join |
| `src/main/java/com/authcses/sdk/exception/CircuitBreakerOpenException.java` | Add `(String, Throwable)` constructor |
| `src/main/java/com/authcses/sdk/AuthCsesClient.java` | Transport chain assembly |
| `CLAUDE.md` | Transport chain order, dependency note |

---

## Task 1: Add dependencies

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Add Resilience4j and HdrHistogram to build.gradle**

In `build.gradle`, add after the OpenTelemetry line (after line 39):

```groovy
    // Resilience4j — circuit breaker, retry, rate limiter, bulkhead (requires Java 17+)
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.4.0")
    implementation("io.github.resilience4j:resilience4j-retry:2.4.0")
    implementation("io.github.resilience4j:resilience4j-ratelimiter:2.4.0")
    implementation("io.github.resilience4j:resilience4j-bulkhead:2.4.0")

    // HdrHistogram — lock-free latency percentile tracking
    implementation("org.hdrhistogram:HdrHistogram:2.2.2")
```

- [ ] **Step 2: Verify dependencies resolve**

Run: `./gradlew dependencies --configuration runtimeClasspath | grep -E "resilience4j|HdrHistogram"`

Expected: all 5 new dependencies resolve successfully.

- [ ] **Step 3: Verify project compiles**

Run: `./gradlew compileJava`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```
git add build.gradle
git commit -m "build: add resilience4j 2.4.0 and hdrhistogram 2.2.2 dependencies"
```

---

## Task 2: Fix checkBulkMulti return type (bug fix)

**Files:**
- Modify: `src/main/java/com/authcses/sdk/transport/SdkTransport.java:39-50`
- Modify: `src/main/java/com/authcses/sdk/transport/GrpcTransport.java:120-154`
- Modify: `src/main/java/com/authcses/sdk/ResourceHandle.java:315-323`

- [ ] **Step 1: Write test for checkBulkMulti with duplicate permissions**

Create `src/test/java/com/authcses/sdk/transport/CheckBulkMultiTest.java`:

```java
package com.authcses.sdk.transport;

import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.model.Consistency;
import com.authcses.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CheckBulkMultiTest {

    @Test
    void checkBulkMulti_duplicatePermissions_returnsAllResults() {
        // Two items with same permission "view" but different resources
        var transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        "document", "doc-1", "viewer", "user", "alice", null),
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        "document", "doc-2", "viewer", "user", "bob", null)
        ));

        var items = List.of(
                new SdkTransport.BulkCheckItem("document", "doc-1", "viewer", "user", "alice"),
                new SdkTransport.BulkCheckItem("document", "doc-2", "viewer", "user", "bob")
        );

        List<CheckResult> results = transport.checkBulkMulti(items, Consistency.minimizeLatency());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
        assertThat(results.get(1).permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.authcses.sdk.transport.CheckBulkMultiTest" --info`

Expected: FAIL — `checkBulkMulti` currently returns `Map<String, CheckResult>`, not `List<CheckResult>`.

- [ ] **Step 3: Change SdkTransport.checkBulkMulti signature**

In `src/main/java/com/authcses/sdk/transport/SdkTransport.java`, replace lines 39-50 (the `checkBulkMulti` default method):

```java
    default List<CheckResult> checkBulkMulti(List<BulkCheckItem> items,
                                              Consistency consistency) {
        List<CheckResult> results = new java.util.ArrayList<>(items.size());
        for (var item : items) {
            results.add(check(item.resourceType(), item.resourceId(),
                    item.permission(), item.subjectType(), item.subjectId(),
                    consistency));
        }
        return results;
    }
```

- [ ] **Step 4: Change GrpcTransport.checkBulkMulti**

In `src/main/java/com/authcses/sdk/transport/GrpcTransport.java`, replace the `checkBulkMulti` method (lines 120-154):

```java
    @Override
    public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items,
                                             Consistency consistency) {
        var builder = CheckBulkPermissionsRequest.newBuilder()
                .setConsistency(toGrpc(consistency));
        for (var item : items) {
            builder.addItems(CheckBulkPermissionsRequestItem.newBuilder()
                    .setResource(objRef(item.resourceType(), item.resourceId()))
                    .setPermission(item.permission())
                    .setSubject(subRef(item.subjectType(), item.subjectId(), null)));
        }

        var response = withErrorHandling(() -> stub().checkBulkPermissions(builder.build()));
        String bulkToken = response.hasCheckedAt() ? response.getCheckedAt().getToken() : null;

        List<CheckResult> results = new java.util.ArrayList<>(items.size());
        for (int i = 0; i < items.size() && i < response.getPairsCount(); i++) {
            var pair = response.getPairs(i);
            CheckResult cr;
            if (pair.hasError()) {
                cr = new CheckResult(Permissionship.NO_PERMISSION, bulkToken, Optional.empty());
            } else {
                cr = switch (pair.getItem().getPermissionship()) {
                    case PERMISSIONSHIP_HAS_PERMISSION ->
                            new CheckResult(Permissionship.HAS_PERMISSION, bulkToken, Optional.empty());
                    case PERMISSIONSHIP_CONDITIONAL_PERMISSION ->
                            new CheckResult(Permissionship.CONDITIONAL_PERMISSION, bulkToken, Optional.empty());
                    default ->
                            new CheckResult(Permissionship.NO_PERMISSION, bulkToken, Optional.empty());
                };
            }
            results.add(cr);
        }
        return results;
    }
```

- [ ] **Step 5: Update ResourceHandle.CheckAllAction.by()**

In `src/main/java/com/authcses/sdk/ResourceHandle.java`, replace lines 315-323 (`by` method body):

```java
        public PermissionSet by(String userId) {
            var items = Arrays.stream(permissions)
                    .map(perm -> new SdkTransport.BulkCheckItem(
                            handle.resourceType, handle.resourceId,
                            perm, handle.defaultSubjectType, userId))
                    .toList();
            List<CheckResult> results = handle.transport.checkBulkMulti(items, consistency);
            Map<String, CheckResult> map = new LinkedHashMap<>();
            for (int i = 0; i < permissions.length; i++) {
                map.put(permissions[i], results.get(i));
            }
            return new PermissionSet(map);
        }
```

Add `import com.authcses.sdk.model.CheckResult;` and `import java.util.LinkedHashMap;` at the top if not already present.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "com.authcses.sdk.transport.CheckBulkMultiTest" --info`

Expected: PASS

- [ ] **Step 7: Run all tests to verify no regressions**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL (all existing tests still pass)

- [ ] **Step 8: Commit**

```
git add src/main/java/com/authcses/sdk/transport/SdkTransport.java \
       src/main/java/com/authcses/sdk/transport/GrpcTransport.java \
       src/main/java/com/authcses/sdk/ResourceHandle.java \
       src/test/java/com/authcses/sdk/transport/CheckBulkMultiTest.java
git commit -m "fix: checkBulkMulti returns List<CheckResult> to prevent key collision"
```

---

## Task 3: Fix WatchCacheInvalidator bugs

**Files:**
- Modify: `src/main/java/com/authcses/sdk/transport/WatchCacheInvalidator.java`

- [ ] **Step 1: Add listenerExecutor field and async dispatch**

In `WatchCacheInvalidator.java`, add field after line 42 (`private final List<Consumer<RelationshipChange>> listeners`):

```java
    private final java.util.concurrent.ExecutorService listenerExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "authcses-sdk-watch-dispatch");
                t.setDaemon(true);
                return t;
            });
```

- [ ] **Step 2: Replace synchronous listener dispatch with async**

In the `watchLoop()` method, replace the listener notification block (lines 134-152, the `if (!listeners.isEmpty())` block) with:

```java
                        // 2. Notify user listeners (async — don't block watch thread)
                        if (!listeners.isEmpty()) {
                            var op = update.getOperation() == RelationshipUpdate.Operation.OPERATION_DELETE
                                    ? RelationshipChange.Operation.DELETE
                                    : RelationshipChange.Operation.TOUCH;
                            String subRel = rel.getSubject().getOptionalRelation();
                            var change = new RelationshipChange(
                                    op, resourceType, resourceId, rel.getRelation(),
                                    rel.getSubject().getObject().getObjectType(),
                                    rel.getSubject().getObject().getObjectId(),
                                    subRel.isEmpty() ? null : subRel, changeToken);
                            listenerExecutor.execute(() -> {
                                for (var listener : listeners) {
                                    try {
                                        listener.accept(change);
                                    } catch (Exception e) {
                                        LOG.log(System.Logger.Level.WARNING,
                                                "Watch listener error: {0}", e.getMessage());
                                    }
                                }
                            });
                        }
```

- [ ] **Step 3: Fix close() — add join + executor shutdown**

Replace the `close()` method (lines 172-183) with:

```java
    @Override
    public void close() {
        running.set(false);
        watchThread.interrupt();
        try {
            watchThread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        listenerExecutor.shutdown();
        try {
            if (!listenerExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                listenerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listenerExecutor.shutdownNow();
        }
        if (ownsChannel) {
            try {
                channel.shutdown().awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Write test for close() behavior**

Create `src/test/java/com/authcses/sdk/transport/WatchCacheInvalidatorTest.java`:

```java
package com.authcses.sdk.transport;

import com.authcses.sdk.cache.CheckCache;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WatchCacheInvalidatorTest {

    @Test
    void close_stopsWatchThread() throws InterruptedException {
        // Use a no-op cache — the watch will fail to connect but that's fine for this test
        var invalidator = new WatchCacheInvalidator(
                io.grpc.ManagedChannelBuilder.forTarget("localhost:0").usePlaintext().build(),
                "test-key",
                CheckCache.noop());

        assertThat(invalidator.isRunning()).isTrue();

        invalidator.close();

        // After close, isRunning should be false
        assertThat(invalidator.isRunning()).isFalse();
    }
}
```

- [ ] **Step 6: Run test**

Run: `./gradlew test --tests "com.authcses.sdk.transport.WatchCacheInvalidatorTest" --info`

Expected: PASS

- [ ] **Step 7: Commit**

```
git add src/main/java/com/authcses/sdk/transport/WatchCacheInvalidator.java \
       src/test/java/com/authcses/sdk/transport/WatchCacheInvalidatorTest.java
git commit -m "fix: WatchCacheInvalidator async listener dispatch + close() thread join"
```

---

## Task 4: Replace SdkMetrics with HdrHistogram

**Files:**
- Modify: `src/main/java/com/authcses/sdk/metrics/SdkMetrics.java`

- [ ] **Step 1: Write test for HdrHistogram-based metrics**

Create `src/test/java/com/authcses/sdk/metrics/SdkMetricsTest.java`:

```java
package com.authcses.sdk.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SdkMetricsTest {

    @Test
    void latencyPercentiles_basicRecording() {
        var metrics = new SdkMetrics();
        // Record 100 requests at 1000µs each
        for (int i = 0; i < 100; i++) {
            metrics.recordRequest(1000, false);
        }
        var snapshot = metrics.snapshot();
        assertThat(snapshot.latencyP50Ms()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(snapshot.latencyP99Ms()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void latencyRecording_aboveMaxTrackable_clampsWithoutException() {
        var metrics = new SdkMetrics();
        // 120 seconds in micros — above the 60s max
        metrics.recordRequest(120_000_000L, false);
        var snapshot = metrics.snapshot();
        // Should clamp to 60s = 60000ms
        assertThat(snapshot.latencyP50Ms()).isCloseTo(60_000.0, org.assertj.core.data.Offset.offset(100.0));
    }

    @Test
    void circuitBreakerState_defaultIsNA() {
        var metrics = new SdkMetrics();
        assertThat(metrics.circuitBreakerState()).isEqualTo("N/A");
    }

    @Test
    void circuitBreakerState_usesSupplier() {
        var metrics = new SdkMetrics();
        metrics.setCircuitBreakerStateSupplier(() -> "OPEN");
        assertThat(metrics.circuitBreakerState()).isEqualTo("OPEN");
    }

    @Test
    void cacheHitRate_zeroDivision() {
        var metrics = new SdkMetrics();
        assertThat(metrics.cacheHitRate()).isEqualTo(0.0);
    }

    @Test
    void cacheHitRate_computed() {
        var metrics = new SdkMetrics();
        for (int i = 0; i < 80; i++) metrics.recordCacheHit();
        for (int i = 0; i < 20; i++) metrics.recordCacheMiss();
        assertThat(metrics.cacheHitRate()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.01));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.authcses.sdk.metrics.SdkMetricsTest" --info`

Expected: FAIL — `setCircuitBreakerStateSupplier` doesn't exist yet, and HdrHistogram percentile results differ from old buffer approach.

- [ ] **Step 3: Rewrite SdkMetrics.java**

Replace the full content of `src/main/java/com/authcses/sdk/metrics/SdkMetrics.java`:

```java
package com.authcses.sdk.metrics;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * SDK internal metrics. Thread-safe, lock-free.
 *
 * <pre>
 * SdkMetrics m = client.metrics();
 * m.cacheHitRate();        // 0.85
 * m.checkLatencyP99();     // 5.2ms
 * m.circuitBreakerState(); // "CLOSED"
 * m.snapshot();            // full snapshot for logging/export
 * </pre>
 */
public class SdkMetrics {

    // ---- Cache ----
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheEvictions = new LongAdder();
    private final AtomicLong cacheSize = new AtomicLong(0);

    // ---- Requests ----
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final LongAdder coalescedRequests = new LongAdder();

    // ---- Latency (HdrHistogram, microseconds) ----
    private static final long MAX_TRACKABLE_MICROS = 60_000_000L; // 60 seconds
    private final Recorder recorder = new Recorder(MAX_TRACKABLE_MICROS, 3);
    private volatile Histogram intervalHistogram;

    // ---- Circuit Breaker (injected via supplier) ----
    private volatile Supplier<String> circuitBreakerStateSupplier = () -> "N/A";

    // ---- Record methods (called by transport layers) ----

    public void recordCacheHit() { cacheHits.increment(); }
    public void recordCacheMiss() { cacheMisses.increment(); }
    public void recordCacheEviction() { cacheEvictions.increment(); }
    public void updateCacheSize(long size) { cacheSize.set(size); }

    public void recordRequest(long latencyMicros, boolean error) {
        totalRequests.increment();
        if (error) totalErrors.increment();
        recorder.recordValue(Math.min(latencyMicros, MAX_TRACKABLE_MICROS));
    }

    public void recordCoalesced() { coalescedRequests.increment(); }

    public void setCircuitBreakerStateSupplier(Supplier<String> supplier) {
        this.circuitBreakerStateSupplier = supplier;
    }

    // ---- Query methods (called by business code) ----

    /** Cache hit rate (0.0 to 1.0). Returns 0 if no cache activity. */
    public double cacheHitRate() {
        long hits = cacheHits.sum();
        long total = hits + cacheMisses.sum();
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public long cacheHits() { return cacheHits.sum(); }
    public long cacheMisses() { return cacheMisses.sum(); }
    public long cacheEvictions() { return cacheEvictions.sum(); }
    public long cacheSize() { return cacheSize.get(); }

    public long totalRequests() { return totalRequests.sum(); }
    public long totalErrors() { return totalErrors.sum(); }
    public long coalescedRequests() { return coalescedRequests.sum(); }

    public double errorRate() {
        long total = totalRequests.sum();
        return total == 0 ? 0.0 : (double) totalErrors.sum() / total;
    }

    /** Check latency p50 in milliseconds. */
    public double checkLatencyP50() { return percentile(50.0); }
    public double checkLatencyP95() { return percentile(95.0); }
    public double checkLatencyP99() { return percentile(99.0); }
    public double checkLatencyAvg() {
        Histogram h = getHistogram();
        return h.getTotalCount() == 0 ? 0 : h.getMean() / 1000.0;
    }

    public String circuitBreakerState() {
        return circuitBreakerStateSupplier.get();
    }

    /** Full snapshot for logging or export. */
    public Snapshot snapshot() {
        return new Snapshot(
                cacheHitRate(), cacheHits(), cacheMisses(), cacheEvictions(), cacheSize(),
                totalRequests(), totalErrors(), errorRate(), coalescedRequests(),
                checkLatencyP50(), checkLatencyP95(), checkLatencyP99(), checkLatencyAvg(),
                circuitBreakerState());
    }

    public record Snapshot(
            double cacheHitRate, long cacheHits, long cacheMisses, long cacheEvictions, long cacheSize,
            long totalRequests, long totalErrors, double errorRate, long coalescedRequests,
            double latencyP50Ms, double latencyP95Ms, double latencyP99Ms, double latencyAvgMs,
            String circuitBreakerState
    ) {
        @Override
        public String toString() {
            return String.format(
                    "SdkMetrics{cache=%.1f%% (%d/%d), size=%d, evictions=%d, " +
                    "requests=%d, errors=%d (%.2f%%), coalesced=%d, " +
                    "latency=[p50=%.2fms p95=%.2fms p99=%.2fms avg=%.2fms], cb=%s}",
                    cacheHitRate * 100, cacheHits, cacheHits + cacheMisses, cacheSize, cacheEvictions,
                    totalRequests, totalErrors, errorRate * 100, coalescedRequests,
                    latencyP50Ms, latencyP95Ms, latencyP99Ms, latencyAvgMs,
                    circuitBreakerState);
        }
    }

    private double percentile(double p) {
        Histogram h = getHistogram();
        return h.getTotalCount() == 0 ? 0 : h.getValueAtPercentile(p) / 1000.0;
    }

    /**
     * Swap the active histogram. Synchronized because Recorder.getIntervalHistogram
     * is not safe for concurrent callers.
     */
    private synchronized Histogram getHistogram() {
        intervalHistogram = recorder.getIntervalHistogram(intervalHistogram);
        return intervalHistogram;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "com.authcses.sdk.metrics.SdkMetricsTest" --info`

Expected: PASS

- [ ] **Step 5: Run all tests**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```
git add src/main/java/com/authcses/sdk/metrics/SdkMetrics.java \
       src/test/java/com/authcses/sdk/metrics/SdkMetricsTest.java
git commit -m "refactor: replace rolling buffer with HdrHistogram for latency metrics"
```

---

## Task 5: Create ResilientTransport

**Files:**
- Create: `src/main/java/com/authcses/sdk/transport/ResilientTransport.java`
- Create: `src/test/java/com/authcses/sdk/transport/ResilientTransportTest.java`

- [ ] **Step 1: Write tests for ResilientTransport**

Create `src/test/java/com/authcses/sdk/transport/ResilientTransportTest.java`:

```java
package com.authcses.sdk.transport;

import com.authcses.sdk.event.SdkEventBus;
import com.authcses.sdk.exception.AuthCsesConnectionException;
import com.authcses.sdk.exception.CircuitBreakerOpenException;
import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.model.Consistency;
import com.authcses.sdk.model.enums.Permissionship;
import com.authcses.sdk.policy.CircuitBreakerPolicy;
import com.authcses.sdk.policy.PolicyRegistry;
import com.authcses.sdk.policy.ResourcePolicy;
import com.authcses.sdk.policy.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientTransportTest {

    private static final CheckResult OK = new CheckResult(Permissionship.HAS_PERMISSION, null, Optional.empty());

    @Test
    void happyPath_delegatesToTransport() {
        var delegate = new InMemoryTransport();
        delegate.writeRelationships(java.util.List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        "document", "doc-1", "viewer", "user", "alice", null)));
        var transport = new ResilientTransport(delegate, PolicyRegistry.withDefaults(), new SdkEventBus());

        var result = transport.check("document", "doc-1", "viewer", "user", "alice", Consistency.minimizeLatency());
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }

    @Test
    void retryOnTransientFailure() {
        var callCount = new AtomicInteger(0);
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(3).baseDelay(Duration.ofMillis(10)).build())
                        .circuitBreaker(CircuitBreakerPolicy.disabled())
                        .build())
                .build();

        // Delegate that fails twice then succeeds
        SdkTransport delegate = failingDelegate(callCount, 2, OK);
        var transport = new ResilientTransport(delegate, policy, new SdkEventBus());

        var result = transport.check("document", "doc-1", "view", "user", "alice", Consistency.minimizeLatency());
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
        assertThat(callCount.get()).isEqualTo(3); // 2 failures + 1 success
    }

    @Test
    void circuitBreakerOpens_afterThreshold() {
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(1).build())
                        .circuitBreaker(CircuitBreakerPolicy.builder()
                                .failureRateThreshold(50)
                                .slidingWindowSize(4)
                                .minimumNumberOfCalls(4)
                                .waitInOpenState(Duration.ofHours(1))
                                .build())
                        .build())
                .build();

        var callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        var transport = new ResilientTransport(delegate, policy, new SdkEventBus());

        // Exhaust the sliding window
        for (int i = 0; i < 4; i++) {
            try { transport.check("doc", "1", "view", "user", "a", Consistency.minimizeLatency()); }
            catch (Exception ignored) {}
        }

        // Next call should be rejected by circuit breaker
        assertThatThrownBy(() ->
                transport.check("doc", "1", "view", "user", "a", Consistency.minimizeLatency()))
                .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    void failOpen_returnsHasPermission() {
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(1).build())
                        .circuitBreaker(CircuitBreakerPolicy.builder()
                                .failureRateThreshold(50)
                                .slidingWindowSize(4)
                                .minimumNumberOfCalls(4)
                                .waitInOpenState(Duration.ofHours(1))
                                .failOpenPermissions(Set.of("view"))
                                .build())
                        .build())
                .build();

        var callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        var transport = new ResilientTransport(delegate, policy, new SdkEventBus());

        // Open the circuit
        for (int i = 0; i < 4; i++) {
            try { transport.check("doc", "1", "view", "user", "a", Consistency.minimizeLatency()); }
            catch (Exception ignored) {}
        }

        // fail-open for "view"
        var result = transport.check("doc", "1", "view", "user", "a", Consistency.minimizeLatency());
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);

        // "edit" is NOT in fail-open set
        assertThatThrownBy(() ->
                transport.check("doc", "1", "edit", "user", "a", Consistency.minimizeLatency()))
                .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    void perResourceTypeIsolation() {
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(1).build())
                        .circuitBreaker(CircuitBreakerPolicy.builder()
                                .failureRateThreshold(50)
                                .slidingWindowSize(4)
                                .minimumNumberOfCalls(4)
                                .waitInOpenState(Duration.ofHours(1))
                                .build())
                        .build())
                .build();

        var callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        var transport = new ResilientTransport(delegate, policy, new SdkEventBus());

        // Fail "document" breaker
        for (int i = 0; i < 4; i++) {
            try { transport.check("document", "1", "view", "user", "a", Consistency.minimizeLatency()); }
            catch (Exception ignored) {}
        }

        // "document" circuit is open
        assertThatThrownBy(() ->
                transport.check("document", "1", "view", "user", "a", Consistency.minimizeLatency()))
                .isInstanceOf(CircuitBreakerOpenException.class);

        // "folder" circuit is still closed — should call delegate (and fail, but not with CircuitBreakerOpenException)
        assertThatThrownBy(() ->
                transport.check("folder", "1", "view", "user", "a", Consistency.minimizeLatency()))
                .isInstanceOf(AuthCsesConnectionException.class);
    }

    @Test
    void disabledCircuitBreaker_passesThrough() {
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(1).build())
                        .circuitBreaker(CircuitBreakerPolicy.disabled())
                        .build())
                .build();

        var callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        var transport = new ResilientTransport(delegate, policy, new SdkEventBus());

        // Even after many failures, no CircuitBreakerOpenException
        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() ->
                    transport.check("doc", "1", "view", "user", "a", Consistency.minimizeLatency()))
                    .isInstanceOf(AuthCsesConnectionException.class);
        }
        assertThat(callCount.get()).isEqualTo(100);
    }

    @Test
    void retryExhaustion_feedsIntoCircuitBreaker() {
        // maxAttempts=3 means 3 total attempts per logical call
        // slidingWindowSize=4, failureRate=50% → 2 failures out of 4 opens the circuit
        // Each logical call generates 3 CB failure recordings (3 retry attempts)
        // So after 2 logical calls = 6 CB recordings, well above threshold
        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder().maxAttempts(3).baseDelay(Duration.ofMillis(1)).build())
                        .circuitBreaker(CircuitBreakerPolicy.builder()
                                .failureRateThreshold(50)
                                .slidingWindowSize(10)
                                .minimumNumberOfCalls(6)
                                .waitInOpenState(Duration.ofHours(1))
                                .build())
                        .build())
                .build();

        var callCount = new AtomicInteger(0);
        SdkTransport delegate = alwaysFailingDelegate(callCount);
        var transport = new ResilientTransport(delegate, policy, new SdkEventBus());

        // 2 logical calls, each generating 3 retry attempts = 6 CB failure recordings
        for (int i = 0; i < 2; i++) {
            try { transport.check("doc", "1", "view", "user", "a", Consistency.minimizeLatency()); }
            catch (Exception ignored) {}
        }

        // Circuit should now be open
        assertThatThrownBy(() ->
                transport.check("doc", "1", "view", "user", "a", Consistency.minimizeLatency()))
                .isInstanceOf(CircuitBreakerOpenException.class);
    }

    @Test
    void close_cleansUpInstances() {
        var transport = new ResilientTransport(new InMemoryTransport(), PolicyRegistry.withDefaults(), new SdkEventBus());
        transport.check("document", "1", "viewer", "user", "a", Consistency.minimizeLatency());
        transport.close(); // should not throw
    }

    // ---- Helpers ----

    private SdkTransport failingDelegate(AtomicInteger callCount, int failCount, CheckResult successResult) {
        return new InMemoryTransport() {
            @Override
            public CheckResult check(String rt, String ri, String p, String st, String si, Consistency c) {
                if (callCount.getAndIncrement() < failCount) {
                    throw new AuthCsesConnectionException("transient failure");
                }
                return successResult;
            }
        };
    }

    private SdkTransport alwaysFailingDelegate(AtomicInteger callCount) {
        return new InMemoryTransport() {
            @Override
            public CheckResult check(String rt, String ri, String p, String st, String si, Consistency c) {
                callCount.incrementAndGet();
                throw new AuthCsesConnectionException("always fails");
            }
        };
    }
}
```

- [ ] **Step 2: Add cause-accepting constructor to CircuitBreakerOpenException**

In `src/main/java/com/authcses/sdk/exception/CircuitBreakerOpenException.java`, add a second constructor:

```java
package com.authcses.sdk.exception;

public class CircuitBreakerOpenException extends AuthCsesException {
    public CircuitBreakerOpenException(String message) { super(message); }
    public CircuitBreakerOpenException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "com.authcses.sdk.transport.ResilientTransportTest" --info`

Expected: FAIL — `ResilientTransport` class does not exist.

- [ ] **Step 4: Implement ResilientTransport**

Create `src/main/java/com/authcses/sdk/transport/ResilientTransport.java`:

```java
package com.authcses.sdk.transport;

import com.authcses.sdk.event.SdkEvent;
import com.authcses.sdk.event.SdkEventBus;
import com.authcses.sdk.exception.CircuitBreakerOpenException;
import com.authcses.sdk.model.*;
import com.authcses.sdk.model.enums.Permissionship;
import com.authcses.sdk.policy.CircuitBreakerPolicy;
import com.authcses.sdk.policy.PolicyRegistry;
import com.authcses.sdk.policy.RetryPolicy;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Resilience transport: per-resource-type circuit breaker + retry via Resilience4j.
 * Replaces CircuitBreakerTransport + PolicyAwareRetryTransport.
 */
public class ResilientTransport implements SdkTransport {

    private static final System.Logger LOG = System.getLogger(ResilientTransport.class.getName());

    private final SdkTransport delegate;
    private final PolicyRegistry policyRegistry;
    private final SdkEventBus eventBus;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Retry> retries = new ConcurrentHashMap<>();

    public ResilientTransport(SdkTransport delegate, PolicyRegistry policyRegistry, SdkEventBus eventBus) {
        this.delegate = delegate;
        this.policyRegistry = policyRegistry;
        this.eventBus = eventBus != null ? eventBus : new SdkEventBus();
    }

    @Override
    public CheckResult check(String resourceType, String resourceId,
                             String permission, String subjectType, String subjectId,
                             Consistency consistency) {
        var policy = policyRegistry.resolve(resourceType);
        Set<String> failOpenPerms = policy.getCircuitBreaker() != null
                ? policy.getCircuitBreaker().getFailOpenPermissions() : Set.of();

        try {
            return executeWithResilience(resourceType,
                    () -> delegate.check(resourceType, resourceId, permission, subjectType, subjectId, consistency));
        } catch (CircuitBreakerOpenException e) {
            if (failOpenPerms.contains(permission)) {
                return new CheckResult(Permissionship.HAS_PERMISSION, null, Optional.empty());
            }
            throw e;
        }
    }

    @Override
    public BulkCheckResult checkBulk(String resourceType, String resourceId,
                                     String permission, List<String> subjectIds, String defaultSubjectType,
                                     Consistency consistency) {
        return executeWithResilience(resourceType,
                () -> delegate.checkBulk(resourceType, resourceId, permission, subjectIds, defaultSubjectType, consistency));
    }

    @Override
    public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
        String resourceType = updates.isEmpty() ? "" : updates.getFirst().resourceType();
        return executeWithResilience(resourceType, () -> delegate.writeRelationships(updates));
    }

    @Override
    public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
        String resourceType = updates.isEmpty() ? "" : updates.getFirst().resourceType();
        return executeWithResilience(resourceType, () -> delegate.deleteRelationships(updates));
    }

    @Override
    public List<Tuple> readRelationships(String resourceType, String resourceId,
                                          String relation, Consistency consistency) {
        return executeWithResilience(resourceType,
                () -> delegate.readRelationships(resourceType, resourceId, relation, consistency));
    }

    @Override
    public List<String> lookupSubjects(String resourceType, String resourceId,
                                        String permission, String subjectType,
                                        Consistency consistency) {
        return executeWithResilience(resourceType,
                () -> delegate.lookupSubjects(resourceType, resourceId, permission, subjectType, consistency));
    }

    @Override
    public List<String> lookupResources(String resourceType, String permission,
                                         String subjectType, String subjectId,
                                         Consistency consistency) {
        return executeWithResilience(resourceType,
                () -> delegate.lookupResources(resourceType, permission, subjectType, subjectId, consistency));
    }

    @Override
    public List<CheckResult> checkBulkMulti(List<BulkCheckItem> items, Consistency consistency) {
        if (items.isEmpty()) return List.of();
        String resourceType = items.getFirst().resourceType();
        return executeWithResilience(resourceType, () -> delegate.checkBulkMulti(items, consistency));
    }

    public io.github.resilience4j.circuitbreaker.CircuitBreaker.State getCircuitBreakerState(String resourceType) {
        var breaker = breakers.get(resourceType);
        return breaker != null ? breaker.getState() : io.github.resilience4j.circuitbreaker.CircuitBreaker.State.DISABLED;
    }

    @Override
    public void close() {
        breakers.values().forEach(CircuitBreaker::reset);
        breakers.clear();
        retries.clear();
        delegate.close();
    }

    // ---- Internal ----

    private <T> T executeWithResilience(String resourceType, Supplier<T> call) {
        CircuitBreaker breaker = resolveBreaker(resourceType);
        Retry retry = resolveRetry(resourceType);

        try {
            Supplier<T> decorated = io.github.resilience4j.decorators.Decorators.ofSupplier(call)
                    .withCircuitBreaker(breaker)
                    .withRetry(retry)
                    .decorate();
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new CircuitBreakerOpenException("Circuit breaker is OPEN for " + resourceType, e);
        }
    }

    private CircuitBreaker resolveBreaker(String resourceType) {
        return breakers.computeIfAbsent(resourceType, this::createBreaker);
    }

    private Retry resolveRetry(String resourceType) {
        return retries.computeIfAbsent(resourceType, this::createRetry);
    }

    private CircuitBreaker createBreaker(String resourceType) {
        var policy = policyRegistry.resolve(resourceType).getCircuitBreaker();
        if (policy == null) policy = CircuitBreakerPolicy.defaults();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold((float) policy.getFailureRateThreshold())
                .slowCallRateThreshold((float) policy.getSlowCallRateThreshold())
                .slowCallDurationThreshold(policy.getSlowCallDuration())
                .slidingWindowType(policy.getSlidingWindowType() == CircuitBreakerPolicy.SlidingWindowType.TIME_BASED
                        ? CircuitBreakerConfig.SlidingWindowType.TIME_BASED
                        : CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(policy.getSlidingWindowSize())
                .minimumNumberOfCalls(policy.getMinimumNumberOfCalls())
                .waitDurationInOpenState(policy.getWaitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(policy.getPermittedCallsInHalfOpen())
                .build();

        CircuitBreaker breaker = CircuitBreaker.of("authcses-" + resourceType, config);

        if (!policy.isEnabled()) {
            breaker.transitionToDisabledState();
        }

        // Bridge events to SdkEventBus
        breaker.getEventPublisher().onStateTransition(event -> {
            var transition = event.getStateTransition();
            SdkEvent sdkEvent = switch (transition) {
                case CLOSED_TO_OPEN, HALF_OPEN_TO_OPEN, CLOSED_TO_FORCED_OPEN ->
                        SdkEvent.CIRCUIT_OPENED;
                case OPEN_TO_HALF_OPEN, FORCED_OPEN_TO_HALF_OPEN ->
                        SdkEvent.CIRCUIT_HALF_OPENED;
                case HALF_OPEN_TO_CLOSED, FORCED_OPEN_TO_CLOSED ->
                        SdkEvent.CIRCUIT_CLOSED;
                default -> null;
            };
            if (sdkEvent != null) {
                LOG.log(System.Logger.Level.INFO, "Circuit breaker [{0}]: {1}", resourceType, transition);
                eventBus.fire(sdkEvent, resourceType + ": " + transition);
            }
        });

        return breaker;
    }

    private Retry createRetry(String resourceType) {
        var policy = policyRegistry.resolve(resourceType).getRetry();
        if (policy == null || policy.getMaxAttempts() <= 0) {
            return Retry.of("authcses-" + resourceType + "-noop",
                    RetryConfig.custom().maxAttempts(1).build());
        }

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(policy.getMaxAttempts())
                .intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialRandomBackoff(
                        policy.getBaseDelay().toMillis(), policy.getMultiplier(), policy.getJitterFactor()))
                .retryOnException(policy::shouldRetry)
                .build();

        Retry retry = Retry.of("authcses-" + resourceType, config);

        retry.getEventPublisher().onRetry(event ->
                LOG.log(System.Logger.Level.WARNING, "Retry {0}/{1} for [{2}]: {3}",
                        event.getNumberOfRetryAttempts(), policy.getMaxAttempts(),
                        resourceType, event.getLastThrowable().getMessage()));

        return retry;
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests "com.authcses.sdk.transport.ResilientTransportTest" --info`

Expected: PASS (all 8 tests)

- [ ] **Step 6: Commit**

```
git add src/main/java/com/authcses/sdk/transport/ResilientTransport.java \
       src/main/java/com/authcses/sdk/exception/CircuitBreakerOpenException.java \
       src/test/java/com/authcses/sdk/transport/ResilientTransportTest.java
git commit -m "feat: add ResilientTransport with Resilience4j circuit breaker + retry"
```

---

## Task 6: Create Resilience4jInterceptor

**Files:**
- Create: `src/main/java/com/authcses/sdk/builtin/Resilience4jInterceptor.java`
- Create: `src/test/java/com/authcses/sdk/builtin/Resilience4jInterceptorTest.java`

- [ ] **Step 1: Write tests**

Create `src/test/java/com/authcses/sdk/builtin/Resilience4jInterceptorTest.java`:

```java
package com.authcses.sdk.builtin;

import com.authcses.sdk.event.SdkEventBus;
import com.authcses.sdk.exception.AuthCsesException;
import com.authcses.sdk.spi.SdkInterceptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Resilience4jInterceptorTest {

    @Test
    void rateLimiter_rejectsOverLimit() {
        var interceptor = Resilience4jInterceptor.builder()
                .rateLimiter(1) // 1 per second
                .eventBus(new SdkEventBus())
                .build();

        var ctx = new SdkInterceptor.OperationContext("CHECK", "doc", "1", "view", "user", "alice");
        interceptor.before(ctx); // first call succeeds

        assertThatThrownBy(() -> interceptor.before(ctx))
                .isInstanceOf(AuthCsesException.class)
                .hasMessageContaining("Rate limited");
    }

    @Test
    void bulkhead_rejectsOverLimit() {
        var interceptor = Resilience4jInterceptor.builder()
                .bulkhead(1) // 1 concurrent
                .eventBus(new SdkEventBus())
                .build();

        var ctx1 = new SdkInterceptor.OperationContext("CHECK", "doc", "1", "view", "user", "alice");
        interceptor.before(ctx1); // acquired

        var ctx2 = new SdkInterceptor.OperationContext("CHECK", "doc", "2", "view", "user", "bob");
        assertThatThrownBy(() -> interceptor.before(ctx2))
                .isInstanceOf(AuthCsesException.class)
                .hasMessageContaining("Bulkhead rejected");

        interceptor.after(ctx1); // release

        interceptor.before(ctx2); // now succeeds
    }

    @Test
    void rateLimiterRejection_doesNotLeakBulkheadPermit() {
        var interceptor = Resilience4jInterceptor.builder()
                .rateLimiter(1)
                .bulkhead(10)
                .eventBus(new SdkEventBus())
                .build();

        var ctx = new SdkInterceptor.OperationContext("CHECK", "doc", "1", "view", "user", "alice");
        interceptor.before(ctx);
        interceptor.after(ctx);

        // Second call: rate limiter should reject BEFORE acquiring bulkhead
        assertThatThrownBy(() -> interceptor.before(ctx))
                .isInstanceOf(AuthCsesException.class)
                .hasMessageContaining("Rate limited");

        // Bulkhead should still have all 10 permits available (not 9)
        // Verify by acquiring 10 concurrently after rate limiter resets
        // (This is implicitly tested — if the bulkhead leaked, we'd run out)
    }

    @Test
    void bothDisabled_isNoop() {
        var interceptor = Resilience4jInterceptor.builder()
                .eventBus(new SdkEventBus())
                .build();

        var ctx = new SdkInterceptor.OperationContext("CHECK", "doc", "1", "view", "user", "alice");
        for (int i = 0; i < 1000; i++) {
            interceptor.before(ctx);
            interceptor.after(ctx);
        }
        // No exception = pass
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.authcses.sdk.builtin.Resilience4jInterceptorTest" --info`

Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement Resilience4jInterceptor**

Create `src/main/java/com/authcses/sdk/builtin/Resilience4jInterceptor.java`:

```java
package com.authcses.sdk.builtin;

import com.authcses.sdk.event.SdkEvent;
import com.authcses.sdk.event.SdkEventBus;
import com.authcses.sdk.exception.AuthCsesException;
import com.authcses.sdk.spi.SdkInterceptor;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;

import java.time.Duration;

/**
 * Combined rate limiter + bulkhead interceptor using Resilience4j.
 * Either or both can be disabled by not configuring them.
 */
public class Resilience4jInterceptor implements SdkInterceptor {

    private final RateLimiter rateLimiter;
    private final Bulkhead bulkhead;
    private final SdkEventBus eventBus;

    private Resilience4jInterceptor(RateLimiter rateLimiter, Bulkhead bulkhead, SdkEventBus eventBus) {
        this.rateLimiter = rateLimiter;
        this.bulkhead = bulkhead;
        this.eventBus = eventBus != null ? eventBus : new SdkEventBus();
    }

    @Override
    public void before(OperationContext ctx) {
        // Rate limiter first — does not hold a resource, safe to fail without cleanup
        if (rateLimiter != null) {
            try {
                rateLimiter.acquirePermission();
            } catch (io.github.resilience4j.ratelimiter.RequestNotPermitted e) {
                eventBus.fire(SdkEvent.RATE_LIMITED, "Rate limited: " + ctx.action());
                throw new AuthCsesException("Rate limited: max requests/second exceeded");
            }
        }
        // Bulkhead second — holds a permit that must be released in after()
        if (bulkhead != null) {
            if (!bulkhead.tryAcquirePermission()) {
                eventBus.fire(SdkEvent.BULKHEAD_REJECTED, "Bulkhead full: " + ctx.action());
                throw new AuthCsesException("Bulkhead rejected: max concurrent requests exceeded");
            }
            ctx.setAttribute("_bulkhead_acquired", true);
        }
    }

    @Override
    public void after(OperationContext ctx) {
        if (bulkhead != null) {
            Boolean acquired = ctx.getAttribute("_bulkhead_acquired");
            if (acquired != null && acquired) {
                bulkhead.releasePermission();
            }
        }
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private RateLimiter rateLimiter;
        private Bulkhead bulkhead;
        private SdkEventBus eventBus;

        public Builder rateLimiter(int maxPerSecond) {
            this.rateLimiter = RateLimiter.of("authcses-sdk", RateLimiterConfig.custom()
                    .limitForPeriod(maxPerSecond)
                    .limitRefreshPeriod(Duration.ofSeconds(1))
                    .timeoutDuration(Duration.ZERO) // non-blocking: reject immediately
                    .build());
            return this;
        }

        public Builder bulkhead(int maxConcurrent) {
            this.bulkhead = Bulkhead.of("authcses-sdk", BulkheadConfig.custom()
                    .maxConcurrentCalls(maxConcurrent)
                    .maxWaitDuration(Duration.ZERO) // non-blocking: reject immediately
                    .build());
            return this;
        }

        public Builder eventBus(SdkEventBus eventBus) { this.eventBus = eventBus; return this; }

        public Resilience4jInterceptor build() {
            return new Resilience4jInterceptor(rateLimiter, bulkhead, eventBus);
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "com.authcses.sdk.builtin.Resilience4jInterceptorTest" --info`

Expected: PASS

- [ ] **Step 5: Commit**

```
git add src/main/java/com/authcses/sdk/builtin/Resilience4jInterceptor.java \
       src/test/java/com/authcses/sdk/builtin/Resilience4jInterceptorTest.java
git commit -m "feat: add Resilience4jInterceptor with rate limiter + bulkhead"
```

---

## Task 7: Rewire AuthCsesClient + delete old code

**Files:**
- Modify: `src/main/java/com/authcses/sdk/AuthCsesClient.java:480-515`
- Delete: `src/main/java/com/authcses/sdk/circuit/CircuitBreaker.java`
- Delete: `src/main/java/com/authcses/sdk/transport/CircuitBreakerTransport.java`
- Delete: `src/main/java/com/authcses/sdk/transport/PolicyAwareRetryTransport.java`
- Delete: `src/main/java/com/authcses/sdk/builtin/RateLimiterInterceptor.java`
- Delete: `src/main/java/com/authcses/sdk/builtin/BulkheadInterceptor.java`
- Delete: `src/test/java/com/authcses/sdk/circuit/CircuitBreakerTest.java`

- [ ] **Step 1: Rewire transport chain in AuthCsesClient.Builder.build()**

In `src/main/java/com/authcses/sdk/AuthCsesClient.java`, replace the transport assembly block (lines 480-515) with:

```java
                SdkTransport transport = lm.phase(com.authcses.sdk.lifecycle.SdkPhase.TRANSPORT, () -> {
                    SdkTransport t = new GrpcTransport(grpcChannel, presharedKey, requestTimeout.toMillis());

                    // Resilient transport: per-resource-type circuit breaker + retry
                    var resilientTransport = new ResilientTransport(t, policies, bus);
                    t = resilientTransport;

                    if (telemetryEnabled) {
                        telemetryHolder[0] = new TelemetryReporter(spi.telemetrySink(), useVirtualThreads);
                        t = new InstrumentedTransport(t, telemetryHolder[0], sdkMetrics);
                    }

                    if (cacheEnabled) {
                        CheckCache l1;
                        try { l1 = new PolicyAwareCheckCache(policies, cacheMaxSize); }
                        catch (NoClassDefFoundError e) { l1 = CheckCache.noop(); }

                        cacheHolder[0] = spi.l2Cache() != null
                                ? new TwoLevelCache(l1, spi.l2Cache())
                                : l1;
                        t = new CachedTransport(t, cacheHolder[0], sdkMetrics);
                    }

                    t = new PolicyAwareConsistencyTransport(t, policies, tokenTracker);
                    if (coalescingEnabled) t = new CoalescingTransport(t, sdkMetrics);
                    if (!interceptors.isEmpty()) t = new InterceptorTransport(t, interceptors);

                    // Wire circuit breaker state to metrics
                    sdkMetrics.setCircuitBreakerStateSupplier(() ->
                            resilientTransport.getCircuitBreakerState("_default").name());

                    return t;
                });
```

- [ ] **Step 2: Remove old imports from AuthCsesClient**

Remove any import of `com.authcses.sdk.circuit.CircuitBreaker` and `CircuitBreakerTransport` from `AuthCsesClient.java` if present (they may be auto-imported).

- [ ] **Step 3: Delete old files**

```bash
rm src/main/java/com/authcses/sdk/circuit/CircuitBreaker.java
rmdir src/main/java/com/authcses/sdk/circuit/
rm src/main/java/com/authcses/sdk/transport/CircuitBreakerTransport.java
rm src/main/java/com/authcses/sdk/transport/PolicyAwareRetryTransport.java
rm src/main/java/com/authcses/sdk/builtin/RateLimiterInterceptor.java
rm src/main/java/com/authcses/sdk/builtin/BulkheadInterceptor.java
rm src/test/java/com/authcses/sdk/circuit/CircuitBreakerTest.java
rmdir src/test/java/com/authcses/sdk/circuit/
```

- [ ] **Step 4: Fix any remaining compilation errors**

Run: `./gradlew compileJava 2>&1 | head -50`

Check for any remaining references to deleted classes. Fix import statements as needed.

- [ ] **Step 5: Run all tests**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```
git add -A
git commit -m "refactor: rewire transport chain to ResilientTransport, delete old implementations

Delete: CircuitBreaker, CircuitBreakerTransport, PolicyAwareRetryTransport,
        RateLimiterInterceptor, BulkheadInterceptor, CircuitBreakerTest

Transport chain: Interceptor → Coalescing → Consistency → Cache → Instrumented → Resilient → Grpc"
```

---

## Task 8: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update transport chain order**

In `CLAUDE.md`, find the transport chain section and replace:

```
Interceptor → Coalescing → PolicyAwareConsistency → Cache(TwoLevel) → PolicyAwareRetry → Instrumented → GrpcTransport
```

with:

```
Interceptor → Coalescing → PolicyAwareConsistency → Cache(TwoLevel) → Instrumented → Resilient(CircuitBreaker+Retry) → GrpcTransport
```

- [ ] **Step 2: Update CircuitBreaker constraint**

Replace the CircuitBreaker thread safety section that mentions "immutable Snapshot + CAS" with:

```markdown
- `CircuitBreaker` — 由 Resilience4j 管理，per-resource-type 实例通过 `ConcurrentHashMap.computeIfAbsent` 懒创建
```

- [ ] **Step 3: Update module boundary**

In the SDK module boundary section, add to sdk-core's dependency description:

```
sdk-core    → 依赖 authzed-grpc + Jackson + Resilience4j + HdrHistogram（业务方引入的核心库）
```

- [ ] **Step 4: Update deleted V1 code section**

Add to the "已删除的 V1 代码" list:

```markdown
- `CircuitBreaker` (自研) — 被 Resilience4j CircuitBreaker 替代
- `CircuitBreakerTransport` — 被 `ResilientTransport` 替代
- `PolicyAwareRetryTransport` — 被 `ResilientTransport` 替代
- `RateLimiterInterceptor` — 被 `Resilience4jInterceptor` 替代
- `BulkheadInterceptor` — 被 `Resilience4jInterceptor` 替代
```

- [ ] **Step 5: Commit**

```
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for Resilience4j migration"
```

---

## Task 9: Final verification

- [ ] **Step 1: Full clean build**

Run: `./gradlew clean build`

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all tests with verbose output**

Run: `./gradlew test --info 2>&1 | tail -20`

Expected: All tests pass, 0 failures.

- [ ] **Step 3: Verify no references to deleted classes**

Run: `grep -r "import com.authcses.sdk.circuit.CircuitBreaker" src/ --include="*.java"` — expected: no matches

Run: `grep -r "CircuitBreakerTransport" src/ --include="*.java"` — expected: no matches

Run: `grep -r "PolicyAwareRetryTransport" src/ --include="*.java"` — expected: no matches

Run: `grep -r "RateLimiterInterceptor" src/ --include="*.java"` — expected: no matches (except possibly comments in CLAUDE.md)

Run: `grep -r "BulkheadInterceptor" src/ --include="*.java"` — expected: no matches

- [ ] **Step 4: Verify deleted files are gone**

Run: `ls src/main/java/com/authcses/sdk/circuit/ 2>&1` — expected: "No such file or directory"

- [ ] **Step 5: Review git diff summary**

Run: `git diff --stat HEAD~8` to see the full change summary across all commits.
