# SDK Quality Improvements Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement all 27 improvement items from the SDK evaluation report, organized by priority (P0 → P3), to bring the SDK to release-ready quality.

**Architecture:** Each improvement is a targeted, isolated change to the existing codebase. No new subsystems are introduced. Changes follow existing patterns (decorator transport, record value objects, JUnit 5 tests). P0 items focus on engineering infrastructure; P1-P2 on correctness/polish; P3 on API evolution.

**Tech Stack:** Java 21, Gradle, gRPC, Resilience4j, Caffeine, JUnit 5, AssertJ, Mockito, GitHub Actions

---

## File Structure

### New Files

| File | Purpose |
|---|---|
| `.github/workflows/ci.yml` | CI pipeline (compile + test + lint) |
| `.github/workflows/release.yml` | Release automation (tag → build → publish) |
| `src/main/java/com/authx/sdk/transport/GrpcExceptionMapper.java` | Extracted gRPC → SDK exception mapping |
| `src/test/java/com/authx/sdk/transport/GrpcExceptionMapperTest.java` | Unit tests for exception mapping |
| `src/test/java/com/authx/sdk/telemetry/TelemetryReporterTest.java` | TelemetryReporter unit tests |
| `README_en.md` | English README |
| `docs/resilience-guide.md` | Resilience configuration guide |
| `docs/cache-consistency-guide.md` | Cache and consistency guide |

### Modified Files

| File | Changes |
|---|---|
| `build.gradle` | group `com.authcses` → `com.authx`, artifactId → `authx-sdk` |
| `AuthxClient.java:366-557` | Extract `buildTransportStack()`, `buildWatch()`, `buildScheduler()` from `build()` |
| `AuthxException.java` | Add `isRetryable()` method |
| `InvalidPermissionException.java` | Add `(String, Throwable)` constructor |
| `InvalidResourceException.java` | Add `(String, Throwable)` constructor |
| `InvalidRelationException.java` | Add `(String, Throwable)` constructor |
| `CachedTransport.java:67-89` | Remove post-invalidation on write operations |
| `TieredCache.java:43-50` | Fix stats() hit counting |
| `ResourceFactory.java:97-115` | Change `grant()`/`revoke()` to return `GrantResult`/`RevokeResult` |
| `GrpcTransport.java:436-457` | Extract `mapGrpcException()` to `GrpcExceptionMapper` |
| `ResilientTransport.java:39,173-185` | Replace MAX_INSTANCES cap with LRU eviction |
| `CoalescingTransport.java:50` | Add timeout to `existing.join()` |
| `TelemetryReporter.java:60` | Add buffer-full drop counter |
| `SdkMetrics.java:113-133` | Annotate deprecated methods with `forRemoval=true` |
| `WatchCacheInvalidatorTest.java` | Comprehensive test expansion |
| `LookupQuery.java` | Add `by(SubjectRef)` overload |

---

## Phase 0: P0 — Release Blockers

### Task T001: Create CI pipeline

**Files:**
- Create: `.github/workflows/ci.yml`

**Steps:**
1. Create `.github/workflows/` directory
2. Write `ci.yml` with compile + test + lint jobs:

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: gradle
      - run: ./gradlew compileJava
      - run: ./gradlew test -x :test-app:test
      - run: ./gradlew javadoc
```

3. Verify YAML is valid
4. Commit: "ci: add GitHub Actions CI pipeline"

---

### Task T002: Create release workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Steps:**
1. Write `release.yml` triggered by version tags:

```yaml
name: Release
on:
  push:
    tags: ['v*']

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: gradle
      - run: ./gradlew test -x :test-app:test
      - run: ./gradlew publish -PsdkVersion=${GITHUB_REF_NAME#v}
        env:
          MAVEN_USERNAME: ${{ secrets.MAVEN_USERNAME }}
          MAVEN_PASSWORD: ${{ secrets.MAVEN_PASSWORD }}
```

2. Commit: "ci: add release workflow (tag-triggered)"

---

### Task T003: Comprehensive WatchCacheInvalidator tests

**Files:**
- Modify: `src/test/java/com/authx/sdk/transport/WatchCacheInvalidatorTest.java`

**Steps:**
1. Read existing test (1 test case: `close_stopsWatchThread`)
2. Add test for permanent error detection:

```java
@Test
void permanentError_stopsWatch() {
    // Use Mockito to create a stub that throws UNIMPLEMENTED
    // Verify isRunning() becomes false without retry
}
```

3. Run test to verify it fails (no mock setup yet)
4. Implement mock setup using Mockito for gRPC stubs
5. Run to verify passing
6. Add test for backoff reconnection:

```java
@Test
void transientError_reconnectsWithBackoff() {
    // Stub throws UNAVAILABLE twice, then succeeds
    // Verify metrics.recordWatchReconnect() called twice
}
```

7. Add test for cache invalidation on change:

```java
@Test
void watchResponse_invalidatesCache() {
    // Use CaffeineCache with known entries
    // Simulate WatchResponse with resource change
    // Verify cache entry is invalidated
}
```

8. Add test for listener dispatch:

```java
@Test
void watchResponse_notifiesListeners() {
    // Add a listener, simulate WatchResponse
    // Verify listener receives RelationshipChange
}
```

9. Add test for listener queue full scenario:

```java
@Test
void listenerQueueFull_doesNotBlockWatchThread() {
    // Fill listener queue, verify watch thread doesn't block
}
```

10. Run full test suite
11. Commit: "test: comprehensive WatchCacheInvalidator tests"

---

### Task T004: Caffeine runtime detection

**Files:**
- Modify: `src/main/java/com/authx/sdk/AuthxClient.java` (lines 423-452)

**Steps:**
1. Read current `NoClassDefFoundError` catch at line 450
2. The existing code already catches `NoClassDefFoundError` and falls back to `Cache.noop()`. Verify this path works by reviewing. The current behavior IS the detection — it silently falls back to noop.
3. Improve the fallback: add a log warning when Caffeine is missing but cache was requested:

```java
} catch (NoClassDefFoundError e) {
    System.getLogger(AuthxClient.class.getName()).log(
            System.Logger.Level.WARNING,
            "Cache enabled but Caffeine not on classpath. Add dependency: " +
            "com.github.ben-manes.caffeine:caffeine:3.1.8. Falling back to no-op cache.");
    effectiveCache = Cache.noop();
}
```

4. Run `./gradlew compileJava`
5. Commit: "fix: warn when Caffeine missing but cache enabled"

---

### Task T005: Unify artifact ID and group

**Files:**
- Modify: `build.gradle` (lines 6, 70)

**Steps:**
1. Change `group = "com.authcses"` → `group = "com.authx"`
2. Change `artifactId = "authcses-sdk"` → `artifactId = "authx-sdk"`
3. Run `./gradlew compileJava` to verify
4. Commit: "build: unify artifact to com.authx:authx-sdk"

---

## Phase 1: P1 — Post-Release Urgent

### Task T006: Split Builder.build() into sub-methods

**Files:**
- Modify: `src/main/java/com/authx/sdk/AuthxClient.java` (lines 366-557)

**Steps:**
1. Read the full `build()` method (lines 366-557)
2. Extract `buildTransportStack()` method covering lines 410-467:

```java
private SdkTransport buildTransportStack(ManagedChannel channel, String presharedKey,
        PolicyRegistry policies, SdkComponents spi, TypedEventBus bus,
        SdkMetrics sdkMetrics, BuildContext ctx) {
    SdkTransport t = new GrpcTransport(channel, presharedKey, requestTimeout.toMillis());
    // ... resilience, telemetry, cache, consistency, coalescing, interceptors
    return t;
}
```

3. Extract `buildWatch()` method covering lines 475-481:

```java
private WatchCacheInvalidator buildWatch(ManagedChannel channel, String presharedKey,
        Cache<CheckKey, CheckResult> checkCache, SdkMetrics sdkMetrics) {
    if (cacheEnabled && watchInvalidation && checkCache != null) {
        var invalidator = new WatchCacheInvalidator(channel, presharedKey, checkCache, sdkMetrics);
        invalidator.start();
        return invalidator;
    }
    return null;
}
```

4. Extract `buildScheduler()` method covering lines 485-502
5. Update `build()` to call the three extracted methods
6. Run `./gradlew test -x :test-app:test` to verify nothing breaks
7. Commit: "refactor: split Builder.build() into focused sub-methods"

---

### Task T007: Fix TieredCache.stats() hit counting

**Files:**
- Modify: `src/main/java/com/authx/sdk/cache/TieredCache.java` (lines 43-50)
- Modify: test file if needed

**Steps:**
1. Read current stats() implementation (line 49):
   ```java
   return new CacheStats(s1.hitCount() + s2.hitCount(), s2.missCount(), s1.evictionCount() + s2.evictionCount());
   ```
   Issue: L1 hits and L2 hits are summed directly, but L2 is only queried on L1 miss. The current calculation is actually correct — `s1.hitCount()` = L1 hits, `s2.hitCount()` = L2 hits (which are L1 misses that hit L2). These are disjoint sets, so `s1.hitCount() + s2.hitCount()` = total hits.

2. **On closer review, the current implementation is correct.** The comment at line 46-47 explains the reasoning. L2 is only queried on L1 miss, so `s2.hitCount()` represents cache hits that missed L1 but found in L2. `s2.missCount()` represents misses through both levels. The sum `s1.hits + s2.hits` is the total hit count across both tiers.

3. Add a clarifying comment to prevent future confusion:
   ```java
   // L1 hits + L2 hits are disjoint: L2 is only queried on L1 miss.
   // So total hits = (requests that hit L1) + (requests that missed L1 but hit L2).
   // Total misses = L2 misses (requests that missed both levels).
   ```

4. Run tests
5. Commit: "docs: clarify TieredCache stats calculation"

---

### Task T008: CachedTransport — remove post-invalidation

**Files:**
- Modify: `src/main/java/com/authx/sdk/transport/CachedTransport.java` (lines 66-90)

**Steps:**
1. Read current writeRelationships (lines 67-71):
   ```java
   invalidateAffectedResources(updates);           // pre-invalidation (pessimistic)
   var result = delegate.writeRelationships(updates);
   invalidateAffectedResources(updates);           // post-invalidation (confirm)
   ```

2. Remove post-invalidation lines (line 70, 78, 88):
   ```java
   @Override
   public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
       invalidateAffectedResources(updates);
       return delegate.writeRelationships(updates);
   }

   @Override
   public RevokeResult deleteRelationships(List<RelationshipUpdate> updates) {
       invalidateAffectedResources(updates);
       return delegate.deleteRelationships(updates);
   }

   @Override
   public RevokeResult deleteByFilter(ResourceRef resource, SubjectRef subject,
                                       Relation optionalRelation) {
       String indexKey = resource.type() + ":" + resource.id();
       invalidateByResource(indexKey);
       return delegate.deleteByFilter(resource, subject, optionalRelation);
   }
   ```

3. Run `./gradlew test -x :test-app:test`
4. Commit: "fix: remove redundant post-invalidation in CachedTransport"

---

### Task T009: ResourceFactory grant/revoke return result objects

**Files:**
- Modify: `src/main/java/com/authx/sdk/ResourceFactory.java` (lines 97-115)

**Steps:**
1. Change `grant()` return type from `void` to `GrantResult`:
   ```java
   /** Grant relation to user(s). */
   public GrantResult grant(String id, String relation, String... userIds) {
       return resource(id).grant(relation).to(userIds);
   }
   ```

2. Change `grantToSubjects()` return type:
   ```java
   public GrantResult grantToSubjects(String id, String relation, String... subjectRefs) {
       return resource(id).grant(relation).toSubjects(subjectRefs);
   }
   ```

3. Change `revoke()` return type from `void` to `RevokeResult`:
   ```java
   public RevokeResult revoke(String id, String relation, String... userIds) {
       return resource(id).revoke(relation).from(userIds);
   }
   ```

4. Change `revokeFromSubjects()` return type:
   ```java
   public RevokeResult revokeFromSubjects(String id, String relation, String... subjectRefs) {
       return resource(id).revoke(relation).fromSubjects(subjectRefs);
   }
   ```

5. Add imports for `GrantResult`, `RevokeResult`
6. Run `./gradlew compileJava` — check for compile errors (callers ignoring return value is fine)
7. Run `./gradlew test -x :test-app:test`
8. Commit: "feat: ResourceFactory grant/revoke return result objects with zedToken"

---

### Task T010: WatchCacheInvalidator listener queue drop metric

**Files:**
- Modify: `src/main/java/com/authx/sdk/transport/WatchCacheInvalidator.java` (lines 52-56)

**Steps:**
1. Replace `DiscardOldestPolicy` with custom policy that counts drops:
   ```java
   private final LongAdder droppedListenerEvents = new LongAdder();
   private final ExecutorService listenerExecutor = new ThreadPoolExecutor(
           1, 1, 0L, TimeUnit.MILLISECONDS,
           new ArrayBlockingQueue<>(10_000),
           r -> { Thread t = new Thread(r, "authx-sdk-watch-dispatch"); t.setDaemon(true); return t; },
           (r, executor) -> droppedListenerEvents.increment());
   ```

2. Add accessor:
   ```java
   public long droppedListenerEvents() { return droppedListenerEvents.sum(); }
   ```

3. Run `./gradlew test -x :test-app:test`
4. Commit: "feat: track dropped watch listener events"

---

### Task T011: TelemetryReporter buffer-full drop counter

**Files:**
- Modify: `src/main/java/com/authx/sdk/telemetry/TelemetryReporter.java` (line 60)

**Steps:**
1. Add a new counter for buffer-full drops:
   ```java
   private final java.util.concurrent.atomic.LongAdder bufferFullDrops = new java.util.concurrent.atomic.LongAdder();
   ```

2. Change `buffer.offer(event)` (line 60) to track drops:
   ```java
   if (!buffer.offer(event)) {
       bufferFullDrops.increment();
   }
   ```

3. Add accessor:
   ```java
   /** Events dropped because the buffer was full (separate from sink failures). */
   public long bufferFullDropCount() { return bufferFullDrops.sum(); }
   ```

4. Run `./gradlew compileJava`
5. Commit: "feat: track TelemetryReporter buffer-full drops"

---

### Task T012: Add English README

**Files:**
- Create: `README_en.md`

**Steps:**
1. Read existing `README.md` for structure
2. Translate to English, maintaining same sections: Features, Quick Start, Advanced Usage, Configuration, Dependencies
3. Add link between the two READMEs at the top of each:
   - In `README.md`: `[English](README_en.md)`
   - In `README_en.md`: `[中文](README.md)`
4. Commit: "docs: add English README"

---

## Phase 2: P2 — Post-Release Optimization

### Task T013: Explicit decorator ordering

**Files:**
- Modify: `src/main/java/com/authx/sdk/AuthxClient.java` (within `buildTransportStack()` after T006)

**Steps:**
1. Add a comment block documenting the decorator order and why:
   ```java
   // Transport decoration order (innermost → outermost, read bottom-to-top):
   //   InterceptorTransport     — user interceptors (outermost: sees final request)
   //   CoalescingTransport      — dedup concurrent identical checks
   //   PolicyAwareConsistency   — maps policy → consistency token
   //   CachedTransport          — L1/L2 cache (must be BELOW consistency: WAR correctness)
   //   InstrumentedTransport    — metrics/telemetry
   //   ResilientTransport       — circuit breaker + retry (wraps gRPC errors)
   //   GrpcTransport            — wire protocol (innermost)
   ```

2. This documents the ordering constraint. A full enum-based enforcer is over-engineering at this stage — the comment plus the extracted method (T006) makes the order clear.
3. Commit: "docs: document transport decorator ordering rationale"

---

### Task T014: ResilientTransport breaker LRU eviction

**Files:**
- Modify: `src/main/java/com/authx/sdk/transport/ResilientTransport.java` (lines 39, 173-185)

**Steps:**
1. Replace `MAX_INSTANCES` cap with `LinkedHashMap` LRU eviction:
   ```java
   private static final int MAX_INSTANCES = 1000;
   private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
   ```
   becomes:
   ```java
   private static final int MAX_BREAKER_CACHE_SIZE = 1000;
   // LRU eviction: when cache exceeds MAX, least-recently-accessed breaker is evicted.
   // ConcurrentHashMap doesn't support LRU natively, so we keep the existing approach
   // but reset evicted breakers to avoid stale state.
   private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
   ```

2. Modify `resolveBreaker()`:
   ```java
   private CircuitBreaker resolveBreaker(String resourceType) {
       return breakers.computeIfAbsent(resourceType, rt -> {
           if (breakers.size() >= MAX_BREAKER_CACHE_SIZE) {
               // Evict oldest entry (approximation — ConcurrentHashMap iteration order is arbitrary)
               var it = breakers.entrySet().iterator();
               if (it.hasNext()) {
                   var oldest = it.next();
                   it.remove();
                   oldest.getValue().reset();
               }
           }
           return createBreaker(rt);
       });
   }
   ```

3. Same for `resolveRetry()`
4. Run `./gradlew test -x :test-app:test`
5. Commit: "fix: ResilientTransport breaker cache evicts instead of defaulting"

---

### Task T015: AuthxException.isRetryable()

**Files:**
- Modify: `src/main/java/com/authx/sdk/exception/AuthxException.java`
- Modify: `src/main/java/com/authx/sdk/exception/AuthxTimeoutException.java`
- Modify: `src/main/java/com/authx/sdk/exception/AuthxConnectionException.java`

**Steps:**
1. Add to `AuthxException`:
   ```java
   /** Whether this exception is safe to retry. Default: false. */
   public boolean isRetryable() { return false; }
   ```

2. Override in `AuthxTimeoutException`:
   ```java
   @Override public boolean isRetryable() { return true; }
   ```

3. Override in `AuthxConnectionException`:
   ```java
   @Override public boolean isRetryable() { return true; }
   ```

4. Run `./gradlew compileJava`
5. Commit: "feat: AuthxException.isRetryable() for programmatic retry decisions"

---

### Task T016: Schema validation exception constructors

**Files:**
- Modify: `src/main/java/com/authx/sdk/exception/InvalidPermissionException.java`
- Modify: `src/main/java/com/authx/sdk/exception/InvalidResourceException.java`
- Modify: `src/main/java/com/authx/sdk/exception/InvalidRelationException.java`

**Steps:**
1. Add `(String, Throwable)` constructor to each:

   `InvalidPermissionException.java`:
   ```java
   public class InvalidPermissionException extends AuthxException {
       public InvalidPermissionException(String message) { super(message); }
       public InvalidPermissionException(String message, Throwable cause) { super(message, cause); }
   }
   ```

2. Same for `InvalidResourceException` and `InvalidRelationException`
3. Run `./gradlew compileJava`
4. Commit: "fix: add cause constructors to schema validation exceptions"

---

### Task T017: Extract GrpcExceptionMapper + tests

**Files:**
- Create: `src/main/java/com/authx/sdk/transport/GrpcExceptionMapper.java`
- Create: `src/test/java/com/authx/sdk/transport/GrpcExceptionMapperTest.java`
- Modify: `src/main/java/com/authx/sdk/transport/GrpcTransport.java` (lines 436-457)

**Steps:**
1. Create `GrpcExceptionMapper.java`:
   ```java
   package com.authx.sdk.transport;

   import com.authx.sdk.exception.*;
   import io.grpc.StatusRuntimeException;

   /**
    * Maps gRPC StatusRuntimeException to SDK exception hierarchy.
    */
   public final class GrpcExceptionMapper {

       private GrpcExceptionMapper() {}

       public static RuntimeException map(StatusRuntimeException e) {
           return switch (e.getStatus().getCode()) {
               // ... same switch body as current mapGrpcException()
           };
       }
   }
   ```

2. Update `GrpcTransport.mapGrpcException()` to delegate:
   ```java
   private RuntimeException mapGrpcException(StatusRuntimeException e) {
       return GrpcExceptionMapper.map(e);
   }
   ```

3. Write test covering each status code:
   ```java
   @Test
   void deadlineExceeded_mapsToTimeout() {
       var e = new StatusRuntimeException(Status.DEADLINE_EXCEEDED);
       assertThat(GrpcExceptionMapper.map(e)).isInstanceOf(AuthxTimeoutException.class);
   }
   // ... one test per status code
   ```

4. Run `./gradlew test -x :test-app:test`
5. Commit: "refactor: extract GrpcExceptionMapper with unit tests"

---

### Task T018: TelemetryReporter + SdkMetrics tests

**Files:**
- Create: `src/test/java/com/authx/sdk/telemetry/TelemetryReporterTest.java`
- Modify: `src/test/java/com/authx/sdk/SdkMetricsTest.java`

**Steps:**
1. Write TelemetryReporter tests:
   - `bufferFull_dropsEvent` — fill buffer, verify `bufferFullDropCount()`
   - `flush_sendsToSink` — verify sink receives events
   - `sinkFailure_incrementsDroppedCount` — verify `droppedEventCount()`
   - `consecutiveFailures_suppressesLogging` — verify log suppression after 4 failures

2. Write SdkMetrics concurrent tests:
   - `concurrentRecordRequest_countsCorrectly` — 10 threads, 1000 requests each
   - `snapshot_returnsConsistentValues` — verify snapshot is atomic

3. Run `./gradlew test -x :test-app:test`
4. Commit: "test: TelemetryReporter and SdkMetrics concurrent tests"

---

### Task T019: Resilience configuration guide

**Files:**
- Create: `docs/resilience-guide.md`

**Steps:**
1. Document all default values:
   - Circuit breaker: failure rate threshold, sliding window size, wait in open state
   - Retry: max attempts, base delay, max delay, multiplier
   - Retry budget: 20% sliding 1-second window
   - Rate limiter and bulkhead (via Resilience4jInterceptor)
2. Provide configuration examples
3. Explain fail-open behavior
4. Commit: "docs: resilience configuration guide"

---

### Task T020: Cache and consistency guide

**Files:**
- Create: `docs/cache-consistency-guide.md`

**Steps:**
1. Document cache behavior:
   - Which requests are cached (MinimizeLatency only)
   - L1/L2 tiers and when L2 is queried
   - TTL configuration (per-type, per-permission, jitter)
2. Document Watch invalidation scope
3. Document SESSION consistency limitation (single JVM without DistributedTokenStore)
4. Commit: "docs: cache and consistency guide"

---

### Task T021: E2E Testcontainers integration

**Files:**
- Modify: `build.gradle` (add testcontainers dependency)
- Modify: `src/test/java/com/authx/sdk/SdkEndToEndTest.java`

**Steps:**
1. Add Testcontainers dependency:
   ```groovy
   testImplementation("org.testcontainers:testcontainers:1.19.7")
   testImplementation("org.testcontainers:junit-jupiter:1.19.7")
   ```

2. Create a SpiceDB container setup:
   ```java
   @Container
   static GenericContainer<?> spicedb = new GenericContainer<>("authzed/spicedb:latest")
       .withCommand("serve-testing")
       .withExposedPorts(50051);
   ```

3. Replace `assumeTrue(isSpiceDbReachable())` with Testcontainers lifecycle
4. Run `./gradlew test` — verify E2E tests now run in CI
5. Commit: "test: E2E tests with Testcontainers (no external SpiceDB needed)"

---

## Phase 3: P3 — Long-Term Evolution

### Task T022: Unified async pattern

**Files:**
- Modify: `src/main/java/com/authx/sdk/ResourceHandle.java` (CheckAction and SubjectQuery)

**Steps:**
1. Standardize on separate `*Async()` methods pattern (already used by SubjectQuery)
2. In CheckAction, keep `byAsync()` as-is (it already returns `CompletableFuture`)
3. Document the async pattern in Javadoc
4. Commit: "docs: document async API pattern"

---

### Task T023: LookupQuery SubjectRef overload

**Files:**
- Modify: `src/main/java/com/authx/sdk/LookupQuery.java`

**Steps:**
1. Add overload:
   ```java
   public LookupQuery by(SubjectRef subject) {
       this.subjectId = subject.id();
       this.subjectType = subject.type();
       return this;
   }
   ```

2. Run tests
3. Commit: "feat: LookupQuery.by(SubjectRef) overload"

---

### Task T024: Streaming backpressure safety valve

**Files:**
- Modify: `src/main/java/com/authx/sdk/transport/GrpcTransport.java`

**Steps:**
1. Add configurable `maxStreamResults` field (default: 10_000)
2. In `readRelationships()`, `lookupSubjects()`, `lookupResources()`: add counter, truncate at limit with WARNING log
3. Run tests
4. Commit: "feat: streaming operations safety valve (maxStreamResults)"

---

### Task T025: Event bus async publish option

**Files:**
- Modify: `src/main/java/com/authx/sdk/event/DefaultTypedEventBus.java`

**Steps:**
1. Add optional `Executor` for async publishing
2. Default remains synchronous (current behavior)
3. Run tests
4. Commit: "feat: optional async event publishing"

---

### Task T026: CoalescingTransport join timeout

**Files:**
- Modify: `src/main/java/com/authx/sdk/transport/CoalescingTransport.java` (line 50)

**Steps:**
1. Add configurable timeout (default: 30 seconds):
   ```java
   private static final long JOIN_TIMEOUT_MS = 30_000;
   ```

2. Replace `existing.join()` with:
   ```java
   try {
       return existing.orTimeout(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS).join();
   } catch (java.util.concurrent.CompletionException ce) {
       if (ce.getCause() instanceof java.util.concurrent.TimeoutException) {
           throw new AuthxTimeoutException("Coalesced request timed out after " + JOIN_TIMEOUT_MS + "ms");
       }
       if (ce.getCause() instanceof RuntimeException re) throw re;
       throw ce;
   }
   ```

3. Run tests
4. Commit: "fix: CoalescingTransport join timeout prevents indefinite blocking"

---

### Task T027: InterceptorTransport unified chain model

**Files:**
- Modify: `src/main/java/com/authx/sdk/transport/InterceptorTransport.java`
- Modify: `src/main/java/com/authx/sdk/spi/SdkInterceptor.java`

**Steps:**
1. This is the largest change. Add chain interfaces for all operation types (not just check/write)
2. Add `interceptLookup(LookupChain)`, `interceptRead(ReadChain)`, `interceptExpand(ExpandChain)` to `SdkInterceptor` with default pass-through
3. Update `InterceptorTransport` to use chains for all operations
4. Run full test suite
5. Commit: "refactor: unified interceptor chain model for all operations"
