# Code Beauty Phase 1 Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify code style across model/exception/policy/spi layers — @Nullable annotations, getter naming, Javadoc, import cleanup. Zero functionality changes.

**Architecture:** Purely cosmetic refactoring. Each task is a self-contained batch of same-type changes. Policy getter renames (getX→x) are the riskiest because they break callers — those go first so compile errors surface immediately. All other tasks are additive (annotations, Javadoc, imports) and can't break functionality.

**Tech Stack:** JSpecify 1.0.0 (null annotations), Java 21

---

## File Structure

### New Files

| File | Purpose |
|---|---|
| `src/main/java/com/authx/sdk/model/package-info.java` | `@NullMarked` for model package |
| `src/main/java/com/authx/sdk/model/enums/package-info.java` | `@NullMarked` for enums package |
| `src/main/java/com/authx/sdk/exception/package-info.java` | `@NullMarked` for exception package |
| `src/main/java/com/authx/sdk/policy/package-info.java` | `@NullMarked` for policy package |
| `src/main/java/com/authx/sdk/spi/package-info.java` | `@NullMarked` for spi package |
| `src/main/java/com/authx/sdk/cache/package-info.java` | `@NullMarked` for cache package |

### Modified Files (~40 files)

Policy getter renames affect: ResourcePolicy, CachePolicy, RetryPolicy, CircuitBreakerPolicy, PolicyRegistry + callers (ResilientTransport, AuthxClient)

---

### Task T001: Add JSpecify dependency

**Files:**
- Modify: `build.gradle`

**Steps:**
1. Add JSpecify:
   ```groovy
   implementation("org.jspecify:jspecify:1.0.0")
   ```
2. Run `./gradlew compileJava`
3. Commit: "build: add JSpecify 1.0.0 for @NullMarked/@Nullable"

---

### Task T002: Policy getter rename — ResourcePolicy

**Files:**
- Modify: `src/main/java/com/authx/sdk/policy/ResourcePolicy.java`

**Steps:**
1. Rename all 5 getters:
   - `getCache()` → `cache()`
   - `getReadConsistency()` → `readConsistency()`
   - `getRetry()` → `retry()`
   - `getCircuitBreaker()` → `circuitBreaker()`
   - `getTimeout()` → `timeout()`
2. Run `./gradlew compileJava` — expect errors in PolicyRegistry, ResilientTransport, AuthxClient
3. Do NOT commit yet (callers broken)

---

### Task T003: Policy getter rename — CachePolicy

**Files:**
- Modify: `src/main/java/com/authx/sdk/policy/CachePolicy.java`

**Steps:**
1. Rename:
   - `getTtl()` → `ttl()`
   - `getMaxIdleTime()` → `maxIdleTime()`
   - `isEnabled()` → `enabled()`
2. Rename factory: `ofTtl(Duration)` → `of(Duration)`

---

### Task T004: Policy getter rename — RetryPolicy

**Files:**
- Modify: `src/main/java/com/authx/sdk/policy/RetryPolicy.java`

**Steps:**
1. Rename all 5 getters:
   - `getMaxAttempts()` → `maxAttempts()`
   - `getBaseDelay()` → `baseDelay()`
   - `getMaxDelay()` → `maxDelay()`
   - `getMultiplier()` → `multiplier()`
   - `getJitterFactor()` → `jitterFactor()`

---

### Task T005: Policy getter rename — CircuitBreakerPolicy

**Files:**
- Modify: `src/main/java/com/authx/sdk/policy/CircuitBreakerPolicy.java`

**Steps:**
1. Rename all getters:
   - `isEnabled()` → `enabled()`
   - `getFailureRateThreshold()` → `failureRateThreshold()`
   - `getSlowCallRateThreshold()` → `slowCallRateThreshold()`
   - `getSlowCallDuration()` → `slowCallDuration()`
   - `getSlidingWindowType()` → `slidingWindowType()`
   - `getSlidingWindowSize()` → `slidingWindowSize()`
   - `getMinimumNumberOfCalls()` → `minimumNumberOfCalls()`
   - `getWaitInOpenState()` → `waitInOpenState()`
   - `getPermittedCallsInHalfOpen()` → `permittedCallsInHalfOpen()`
   - `getFailOpenPermissions()` → `failOpenPermissions()`
   - `getOnStateChange()` → `onStateChange()`

---

### Task T006: Policy getter rename — PolicyRegistry

**Files:**
- Modify: `src/main/java/com/authx/sdk/policy/PolicyRegistry.java`

**Steps:**
1. Rename: `getDefaultPolicy()` → `defaultPolicy()`
2. Update internal calls to use new ResourcePolicy/CachePolicy method names

---

### Task T007: Fix all callers of renamed policy methods

**Files:**
- Modify: `src/main/java/com/authx/sdk/transport/ResilientTransport.java` (~16 call sites)
- Modify: `src/main/java/com/authx/sdk/AuthxClient.java` (~3 call sites)

**Steps:**
1. In ResilientTransport, update all `policy.getX()` → `policy.x()` calls
2. In AuthxClient buildTransportStack, update `policies.resolveCacheTtl()` calls (if any getter changes)
3. Run `./gradlew compileJava` — must pass now
4. Run `./gradlew test -x :test-app:test` — all tests pass
5. Commit: "refactor: policy getters getX()→x() — property-style naming"

---

### Task T008: Factory method rename — CheckRequest.from→of

**Files:**
- Modify: `src/main/java/com/authx/sdk/model/CheckRequest.java`
- Modify: all callers (grep for `CheckRequest.from`)

**Steps:**
1. Rename `CheckRequest.from(...)` → `CheckRequest.of(...)`
2. Update all callers
3. Run `./gradlew compileJava`
4. Commit: "refactor: CheckRequest.from()→of() — consistent factory naming"

---

### Task T009: @NullMarked package-info files

**Files:**
- Create 6 `package-info.java` files

**Steps:**
1. Create each package-info:
   ```java
   @NullMarked
   package com.authx.sdk.model;
   import org.jspecify.annotations.NullMarked;
   ```
2. Same for: model.enums, exception, policy, spi, cache
3. Run `./gradlew compileJava`
4. Commit: "style: @NullMarked package annotations"

---

### Task T010: @Nullable annotations — model layer

**Files:**
- Modify: SubjectRef, CheckResult, CaveatRef, Tuple, ExpandTree, CheckRequest, LookupResourcesRequest, LookupSubjectsRequest, WriteRequest

**Steps:**
1. Add `import org.jspecify.annotations.Nullable;` to each file
2. Add `@Nullable` to each known nullable position:
   - `SubjectRef.relation`
   - `CheckResult.zedToken`
   - `CaveatRef.context`
   - `Tuple.subjectRelation`
   - `CheckRequest.caveatContext`
   - `CheckRequest.consistency` (can be default null in some paths)
3. Run `./gradlew compileJava`
4. Commit: "style: @Nullable annotations on model layer"

---

### Task T011: @Nullable annotations — policy + spi layer

**Files:**
- Modify: ResourcePolicy, SdkComponents, SdkInterceptor.OperationContext, DistributedTokenStore, AttributeKey

**Steps:**
1. ResourcePolicy: all 5 fields `@Nullable` (cache, readConsistency, retry, circuitBreaker, timeout)
2. SdkComponents: `tokenStore` field `@Nullable`
3. OperationContext: `result` and `error` fields `@Nullable`
4. DistributedTokenStore: `get()` return `@Nullable`
5. AttributeKey: `defaultValue()` return `@Nullable`
6. CachePolicy: `maxIdleTime` field `@Nullable`
7. Run `./gradlew compileJava`
8. Commit: "style: @Nullable annotations on policy + spi layer"

---

### Task T012: Javadoc — model records

**Files:**
- Modify: all record files in model/ (~18 files)

**Steps:**
1. For each record, ensure:
   - Class-level Javadoc with one-sentence description
   - `@param` for each field
   - Factory method `of()` has Javadoc
2. For non-record classes (BulkCheckResult, PermissionSet, PermissionMatrix): add class Javadoc + key method Javadoc
3. Run `./gradlew javadoc` to verify no errors
4. Commit: "docs: Javadoc for all model types"

---

### Task T013: Javadoc — exception layer

**Files:**
- Modify: 12 exception files

**Steps:**
1. Each exception class gets one-line class Javadoc describing trigger and retryability:
   ```java
   /** Thrown when SpiceDB returns DEADLINE_EXCEEDED. {@link #isRetryable()} returns {@code true}. */
   ```
2. Commit: "docs: Javadoc for all exception types"

---

### Task T014: Javadoc — policy layer

**Files:**
- Modify: 6 policy files

**Steps:**
1. PolicyRegistry: class, resolve(), resolveCacheTtl(), isCacheEnabled(), resolveReadConsistency(), withDefaults(), builder()
2. ResourcePolicy: class, mergeWith(), defaults(), builder()
3. CachePolicy: class, resolveTtl(), of(), disabled(), builder()
4. RetryPolicy: class, shouldRetry(), defaults(), disabled(), builder()
5. CircuitBreakerPolicy: class, defaults(), builder()
6. ReadConsistency: verify existing Javadoc completeness
7. Commit: "docs: Javadoc for all policy types"

---

### Task T015: Import cleanup — scope files

**Files:**
- Modify: all files in model/, exception/, policy/, spi/ with wildcard imports

**Steps:**
1. Replace all `import xxx.*` with explicit imports
2. Order: com.authx → com.authzed → io.* → java.* → static
3. Run `./gradlew compileJava`
4. Commit: "style: explicit imports — no wildcards in model/exception/policy/spi"

---

### Task T016: Final verification

**Steps:**
1. Run `./gradlew compileJava`
2. Run `./gradlew test -x :test-app:test`
3. Verify: no `getX()` getters remain in policy package (except `getIfPresent` in Cache)
4. Verify: no wildcard imports in scope packages
5. Verify: all public types in scope have class-level Javadoc
6. Commit tasks.md with all items marked complete
