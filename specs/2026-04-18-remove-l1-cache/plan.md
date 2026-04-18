# Remove L1 Cache + Watch Infrastructure — Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the entire L1 Caffeine decision-cache subsystem (including SchemaCache) and the Watch stream infrastructure that existed solely to invalidate it.

**Architecture:** Bottom-up deletion — fix downstream consumers first (so they stop referring to soon-to-be-deleted SDK APIs), then remove SDK-internal references, then delete the actual files. Each phase leaves the repo in a compiling state.

**Tech Stack:** Java 21, Gradle, JUnit 5 + AssertJ. Uses `git rm` / `git mv` for file deletions so history is preserved.

---

## File Structure (after deletion)

```
src/main/java/com/authx/sdk/
├── AuthxClient.java                  # modified — drop cache() + Watch subscriptions
├── AuthxClientBuilder.java           # modified — delete CacheConfig + watch fields
├── (deleted) CacheHandle.java
├── internal/
│   └── (deleted) SdkCaching.java
├── action/                           # unchanged
├── transport/
│   ├── (deleted) CachedTransport.java
│   ├── (deleted) WatchCacheInvalidator.java
│   ├── (deleted) WatchConnectionState.java
│   ├── (deleted) SchemaLoader.java
│   └── (rest unchanged)
├── (deleted) cache/                   # entire package removed
├── (deleted) watch/                   # entire package removed
├── (deleted) dedup/                   # entire package removed
├── spi/
│   ├── (deleted) DuplicateDetector.java
│   ├── (deleted) DroppedListenerEvent.java
│   ├── (deleted) QueueFullPolicy.java
│   └── (rest unchanged)
├── policy/
│   ├── (deleted) CachePolicy.java
│   └── (ResourcePolicy/PolicyRegistry modified)
├── model/                            # unchanged
├── event/SdkTypedEvent.java          # modified — drop cache/watch event types
├── metrics/SdkMetrics.java           # modified — drop cache/watch counters
└── Typed*.java                       # modified — drop schema validation
```

**New test files:**
- `src/test/java/com/authx/sdk/BuilderCacheMethodRemovedTest.java`
- `src/test/java/com/authx/sdk/NoWatchStreamStartsTest.java`
- `src/test/java/com/authx/sdk/transport/TransportChainTest.java`

---

## Execution strategy

1. **Phase 0** — verify baseline green on branch `remove-l1-cache`.
2. **Phase 1** — clean downstream consumers (test-app, cluster-test). After this phase, those modules compile against the *current* SDK but no longer use cache/Watch APIs.
3. **Phase 2** — remove cache/Watch references from within SDK code (keeping files alive but gutted).
4. **Phase 3–5** — delete transport layers, packages, SPIs.
5. **Phase 6** — delete cache/Watch tests (they would fail against deleted types).
6. **Phase 7** — add three regression tests to prevent re-introduction.
7. **Phase 8** — documentation sweep.
8. **Phase 9** — full suite + downstream verification.

Each task ends with a commit so the change is bisectable.

---

## Tasks

### Task T001: Baseline verify

**Files:** none modified (validation only).

**Steps:**

1. Confirm branch:
   ```bash
   git rev-parse --abbrev-ref HEAD
   ```
   Expected: `remove-l1-cache`.
2. Verify spec + ADR committed:
   ```bash
   git log --oneline -1
   ```
   Expected: the spec+ADR commit from brainstorming.
3. Run the SDK suite:
   ```bash
   ./gradlew :test -x :test-app:test -x :cluster-test:test
   ```
   Expected: BUILD SUCCESSFUL, all tests pass.

---

### Task T002 [P]: Clean `test-app/SpiceDbConfig.java` [SR:req-22]

**Files:** `test-app/src/main/java/com/authx/testapp/SpiceDbConfig.java`

**Steps:**

1. Open the file. Locate the `@Value("${authx.watch-invalidation:false}") private boolean watchInvalidation;` field and the `.cache(...)` block in the builder.
2. Remove the `watchInvalidation` field entirely (field + any `@Value` binding).
3. Remove the `.cache(c -> c .maxSize(...) .watchInvalidation(...))` chain from the builder invocation.
4. Remove the import for `com.authx.sdk.policy.CachePolicy` if present, and any `.cache(CachePolicy.of(...))` lines in policy registration.
5. Remove the `cache-max-size` and related `@Value` properties at the top of the class if no longer used.
6. Compile-check: `./gradlew :test-app:compileJava`. Expected: **FAILS with "cache method not found"** — this is fine, the SDK still exports it but test-app no longer calls it. If it fails for another reason, investigate. If it compiles cleanly, even better.
7. Do NOT commit yet — batch with T003.

---

### Task T003 [P]: Clean `test-app/PermissionController.java` [SR:req-22]

**Files:** `test-app/src/main/java/com/authx/testapp/PermissionController.java`

**Steps:**

1. Locate line 275: `var cache = client.cache();`
2. Delete the line and any endpoint that exposed cache stats/invalidation (scan around line 275 for `@GetMapping("/cache")` or `@DeleteMapping("/cache")` handler methods — delete the whole method and its imports).
3. Remove unused imports (`com.authx.sdk.CacheHandle`, `com.authx.sdk.cache.CacheStats` if present).
4. `./gradlew :test-app:compileJava` — expect green.
5. Commit T002 + T003 together:
   ```bash
   git add test-app/
   git commit -m "refactor(test-app): remove cache/watch API usage (SR:req-22)"
   ```

---

### Task T004 [P]: Clean `cluster-test/config/SdkConfig.java` [SR:req-23]

**Files:** `cluster-test/src/main/java/com/authx/clustertest/config/SdkConfig.java`

**Steps:**

1. Locate the `.cache(c -> c ... .watchInvalidation(true))` block at line ~24 and remove the entire `.cache(...)` invocation.
2. Locate `.cache(CachePolicy.of(Duration.ofSeconds(30)))` at line ~34 and remove that line from the policy builder chain.
3. Remove unused import `com.authx.sdk.policy.CachePolicy`.
4. `./gradlew :cluster-test:compileJava` — expect green.
5. Do NOT commit yet — batch with T005–T009.

---

### Task T005 [P]: Clean `cluster-test/matrix/RealMatrixClient.java` [SR:req-23]

**Files:** `cluster-test/src/main/java/com/authx/clustertest/matrix/RealMatrixClient.java`

**Steps:**

1. Remove `.cache(c -> c ... .watchInvalidation(cacheEnabled))` block at line ~36.
2. Remove `.cache(cacheEnabled ? CachePolicy.of(ttl) : CachePolicy.disabled())` at line ~46 from the policy chain.
3. Remove both `client.cache()` usages at lines ~76 and ~81 — and the entire methods they're in if those methods only expose cache stats (check with `grep "cache"` in the file).
4. Remove the `cacheEnabled`, `ttl` field parameters if they're no longer used anywhere in the class.
5. Remove imports: `com.authx.sdk.CacheHandle`, `com.authx.sdk.cache.CacheStats`, `com.authx.sdk.policy.CachePolicy`.
6. `./gradlew :cluster-test:compileJava` — expect green.

---

### Task T006 [P]: Clean `cluster-test/soak/ResourceSampler.java` [SR:req-23]

**Files:** `cluster-test/src/main/java/com/authx/clustertest/soak/ResourceSampler.java`

**Steps:**

1. Locate lines ~31–33:
   ```java
   if (client.cache() != null) {
       cacheSize = client.cache().size();
       CacheStats stats = client.cache().stats();
   }
   ```
2. Delete the entire `if` block. Replace any `cacheSize` / `stats` references downstream with constants or remove them from the sampled output. If `cacheSize` was a field, remove the field declaration.
3. Remove import `com.authx.sdk.cache.CacheStats`.
4. If the sampler writes a JSON/CSV row that included `cacheSize`, remove that column — also update any downstream report generator that reads it. Search: `grep -rn "cacheSize" cluster-test/`.
5. `./gradlew :cluster-test:compileJava` — expect green.

---

### Task T007 [P]: Clean `cluster-test/resilience/R7CloseRobustnessTest.java` [SR:req-23]

**Files:** `cluster-test/src/main/java/com/authx/clustertest/resilience/R7CloseRobustnessTest.java`

**Steps:**

1. Remove `.cache(c -> c.enabled(false))` at line ~36.
2. `./gradlew :cluster-test:compileJava` — expect green.

---

### Task T008 [P]: Clean `cluster-test/resilience/R4TokenStoreTest.java` [SR:req-23]

**Files:** `cluster-test/src/main/java/com/authx/clustertest/resilience/R4TokenStoreTest.java`

**Steps:**

1. Remove `.cache(c -> c.enabled(false))` at line ~64.
2. Remove `.cache(CachePolicy.disabled())` at line ~71 from policy chain.
3. Remove import `com.authx.sdk.policy.CachePolicy`.
4. `./gradlew :cluster-test:compileJava` — expect green.

---

### Task T009 [P]: Clean `cluster-test/caveat/CaveatIT.java` [SR:req-23]

**Files:** `cluster-test/src/test/java/com/authx/clustertest/caveat/CaveatIT.java`

**Steps:**

1. Remove `.cache(c -> c.enabled(false))` at line ~98.
2. `./gradlew :cluster-test:compileTestJava` — expect green.

Commit T004–T009 together:

```bash
git add cluster-test/
git commit -m "refactor(cluster-test): remove cache/watch API usage (SR:req-23)"
```

---

### Task T010: Delete `WatchStormIT.java` [SR:req-23]

**Files:** delete `cluster-test/src/test/java/com/authx/clustertest/watchstorm/WatchStormIT.java`

**Steps:**

1. This test suite **exists only to stress Watch** — it uses `.cache(...).watchInvalidation(true)` and `onRelationshipChange(...)`. Since Watch is going away, the test has no purpose.
2. Also delete the directory if empty:
   ```bash
   git rm cluster-test/src/test/java/com/authx/clustertest/watchstorm/WatchStormIT.java
   rmdir cluster-test/src/test/java/com/authx/clustertest/watchstorm 2>/dev/null || true
   ```
3. `./gradlew :cluster-test:compileTestJava` — expect green.
4. Commit:
   ```bash
   git commit -m "chore(cluster-test): delete WatchStormIT (Watch subsystem removed)"
   ```

---

### Task T011 [P]: Drop schema validation from typed actions [SR:req-18]

**Files:**
- `src/main/java/com/authx/sdk/TypedGrantAction.java`
- `src/main/java/com/authx/sdk/TypedRevokeAction.java`

**Steps:**

1. In `TypedGrantAction.write(String[] refs)`, locate the block:
   ```java
   SchemaCache schema = factory.schemaCache();
   if (schema != null) {
       String resourceType = factory.resourceType();
       for (R rel : relations) {
           String relName = rel.relationName();
           for (String ref : refs) {
               schema.validateSubject(resourceType, relName, ref);
           }
       }
   }
   ```
   Delete it entirely. The remaining aggregation loop stays.
2. Remove import `com.authx.sdk.cache.SchemaCache` from the file.
3. Mirror in `TypedRevokeAction.write(String[] refs)` — delete the identical schema validation block + import.
4. Compile: `./gradlew :compileJava` — expect green.
5. Commit:
   ```bash
   git add src/main/java/com/authx/sdk/TypedGrantAction.java \
           src/main/java/com/authx/sdk/TypedRevokeAction.java
   git commit -m "refactor(typed): drop schema validation at write time (SR:req-18)"
   ```

---

### Task T012: Remove `CacheConfig` + cache fields from `AuthxClientBuilder` [SR:req-19, req-20, req-21]

**Files:** `src/main/java/com/authx/sdk/AuthxClientBuilder.java`

**Steps:**

1. Delete the entire inner class `public class CacheConfig { ... }` (around line 173–196).
2. Delete the `public AuthxClientBuilder cache(Consumer<CacheConfig> config) { ... }` method.
3. Delete the field declarations:
   ```java
   private boolean cacheEnabled = false;
   private long cacheMaxSize = 100_000;
   private boolean watchInvalidation = false;
   private com.authx.sdk.spi.QueueFullPolicy listenerQueueOnFull = ...;
   ```
4. Delete the builder validation rules in `build()`:
   ```java
   if (watchInvalidation && !cacheEnabled) throw new IllegalArgumentException(...);
   if (!watchStrategies.isEmpty() && (!cacheEnabled || !watchInvalidation)) throw ...;
   ```
5. Delete the `ExtendConfig.addWatchStrategy(...)` method and the `watchStrategies` field.
6. Delete any internal plumbing in `build()` / `buildCache()` / `buildWatch()` methods that references `cacheEnabled`, `watchInvalidation`, `cacheMaxSize` — the entire body of `buildCache()` and `buildWatch()` helper methods should be deleted (replace the method bodies with nothing, or delete the methods entirely if no longer called).
7. Remove imports: `com.authx.sdk.CacheHandle`, `com.authx.sdk.cache.Cache`, `com.authx.sdk.cache.CaffeineCache`, `com.authx.sdk.cache.SchemaCache`, `com.authx.sdk.internal.SdkCaching`, `com.authx.sdk.spi.DuplicateDetector`, `com.authx.sdk.spi.QueueFullPolicy`, `com.authx.sdk.transport.CachedTransport`, `com.authx.sdk.transport.SchemaLoader`, `com.authx.sdk.transport.WatchCacheInvalidator`, `com.authx.sdk.watch.WatchDispatcher`, `com.authx.sdk.watch.WatchStrategy`.
8. Inside `BuildContext` helper class, remove `checkCache`, `watchInvalidator` fields.
9. Compile: `./gradlew :compileJava` — this will fail because `AuthxClient`'s constructor still takes an `SdkCaching` parameter. Continue to T013 to fix.
10. Do NOT commit yet.

---

### Task T013: Update `AuthxClient` to drop cache + Watch [SR:req-4, req-5, req-12]

**Files:** `src/main/java/com/authx/sdk/AuthxClient.java`

**Steps:**

1. Delete the `SdkCaching caching` field and constructor parameter. Remove the field initialization in the constructor.
2. Delete the `public CacheHandle cache() { ... }` method.
3. Delete the entire `// ---- Watch (real-time relationship change events) ----` section:
   - `public void onRelationshipChange(Consumer<RelationshipChange> listener)`
   - `public void offRelationshipChange(Consumer<RelationshipChange> listener)`
4. Delete the Watch-dispatcher wiring in the constructor:
   ```java
   if (caching.watchInvalidator() != null && caching.watchDispatcher() != null) {
       caching.watchInvalidator().addListener(caching.watchDispatcher());
   }
   ```
5. Delete the `com.authx.sdk.cache.SchemaCache internalSchemaCache()` package-private accessor.
6. Delete the `inMemory()` factory's caching argument:
   ```java
   // before: var caching = new SdkCaching(null, null, null, null);
   // after: just remove that line and don't pass to new AuthxClient(...)
   ```
7. Update `new AuthxClient(...)` invocation in `inMemory()` to match the new constructor signature (no `caching` arg).
8. Remove imports: `com.authx.sdk.CacheHandle` (even if self-referencing via full name, check carefully), `com.authx.sdk.internal.SdkCaching`, `com.authx.sdk.model.RelationshipChange`, `com.authx.sdk.cache.SchemaCache`, `java.util.function.Consumer` if only used by the removed Watch listener methods.
9. Update `ResourceFactory` instantiation: the schemaCache argument (5th param) becomes `null` or the param is dropped. See T014.
10. Update `SchemaClient`-related code: `this.schemaClient = new SchemaClient(caching.schemaCache());` — delete this line and the field.
11. `./gradlew :compileJava` — still likely red until T014. Continue.
12. Do NOT commit yet.

---

### Task T014: Update `ResourceFactory` to drop schemaCache [SR:req-4]

**Files:** `src/main/java/com/authx/sdk/ResourceFactory.java`

**Steps:**

1. Remove the `SchemaCache schemaCache` constructor parameter AND field.
2. Remove the `public SchemaCache schemaCache()` accessor method (package-private, used by TypedGrantAction/TypedRevokeAction which no longer needs it per T011).
3. Remove `import com.authx.sdk.cache.SchemaCache`.
4. In `init(...)` method (if present, used by `@PermissionResource`), drop the schemaCache parameter.
5. Check for callers:
   ```bash
   grep -rn "new ResourceFactory(" src/ test-app/ cluster-test/
   ```
   Update each call site to drop the schemaCache arg (should be only in `AuthxClient.on(String)` and `AuthxClient.resource(String, String)` and `AuthxClient.create(Class)`).
6. In `AuthxClient.on(String resourceType)`:
   ```java
   // before:
   return new ResourceFactory(type, transport, config.defaultSubjectType(),
           infra.asyncExecutor(), caching.schemaCache());
   // after (drop last arg):
   return new ResourceFactory(type, transport, config.defaultSubjectType(),
           infra.asyncExecutor());
   ```
   Also remove the `if (caching.schemaCache() != null) caching.schemaCache().validateResourceType(type);` guard at the top of `on()` and `resource()` and `create()`.
7. `./gradlew :compileJava` — continue to T015 if still red.

---

### Task T015 [P]: Drop watch fields from `SdkComponents` [SR:req-14]

**Files:** `src/main/java/com/authx/sdk/spi/SdkComponents.java`

**Steps:**

1. Delete the following record components: `watchDuplicateDetector`, `watchListenerExecutor`, `watchListenerDropHandler`.
2. Delete the corresponding builder fields and methods: `watchDuplicateDetector(...)`, `watchListenerExecutor(...)`, `watchListenerDropHandler(...)`.
3. Update `defaults()` and `build()` method bodies to match new record shape.
4. Remove imports: `com.authx.sdk.spi.DuplicateDetector`, `com.authx.sdk.spi.DroppedListenerEvent`, `java.util.concurrent.ExecutorService`, `java.util.function.Consumer` (if no longer used).
5. `./gradlew :compileJava` — continue.

---

### Task T016 [P]: Drop cache field from `ResourcePolicy` + `PolicyRegistry` [SR:req-17]

**Files:**
- `src/main/java/com/authx/sdk/policy/ResourcePolicy.java`
- `src/main/java/com/authx/sdk/policy/PolicyRegistry.java`

**Steps:**

1. In `ResourcePolicy.java`: remove the `CachePolicy cache` record component (or field if it's a class). Update constructor / `with*` methods accordingly.
2. Remove import `com.authx.sdk.policy.CachePolicy` from `ResourcePolicy.java`.
3. In `PolicyRegistry.java`: remove any field / method dealing with cache policy. Keep retry / circuit-breaker / rate-limiter / bulkhead / consistency policies intact.
4. Remove `com.authx.sdk.policy.CachePolicy` import.
5. Run `grep -rn "\.cache(" src/main/java/com/authx/sdk/policy/` to find any residual references and clean.
6. `./gradlew :compileJava` — continue.

---

### Task T017 [P]: Drop cache/watch events from `SdkTypedEvent` [SR:req-15]

**Files:** `src/main/java/com/authx/sdk/event/SdkTypedEvent.java`

**Steps:**

1. Delete these record types from within the sealed interface:
   - `CacheHit`, `CacheMiss`, `CacheEviction` (if present)
   - `WatchConnected`, `WatchDisconnected`, `WatchReconnected`, `WatchStreamStale`, `WatchCursorExpired`
   - `ListenerDropped` (if defined here)
2. Find publishers:
   ```bash
   grep -rn "SdkTypedEvent\.\(Cache\|Watch\|ListenerDropped\)" src/main/java/
   ```
   Remove the publish sites (usually in CachedTransport — being deleted — or WatchCacheInvalidator — being deleted — so this may resolve automatically).
3. `./gradlew :compileJava` — continue.

---

### Task T018 [P]: Drop cache/watch metrics from `SdkMetrics` [SR:req-16]

**Files:** `src/main/java/com/authx/sdk/metrics/SdkMetrics.java`

**Steps:**

1. Remove fields: `cacheHits`, `cacheMisses`, `cacheEvictions`, `cacheSize`, `cacheStatsSource`, `watchReconnects`.
2. Remove the corresponding public getters: `cacheHits()`, `cacheMisses()`, `cacheEvictions()`, `cacheSize()`, `watchReconnects()`, `setCacheStatsSource(...)`, `updateCacheSize(...)`.
3. Remove the fields from `Snapshot` record + its getters.
4. Remove references from `toString()` of Snapshot.
5. Remove `recordCacheHit()`, `recordCacheMiss()`, `recordCacheEviction()`, `recordWatchReconnect()` methods.
6. Find callers:
   ```bash
   grep -rn "\.recordCacheHit\|\.recordCacheMiss\|\.recordCacheEviction\|\.recordWatchReconnect\|\.updateCacheSize\|\.setCacheStatsSource" src/main/java/
   ```
   Should all be in deleted files (CachedTransport / WatchCacheInvalidator / AuthxClientBuilder's buildCache). Verify zero callers outside those.
7. `./gradlew :compileJava` — should eventually go green here after T011–T018 all applied. If not, investigate the remaining error.
8. Commit Phase 2 batch:
   ```bash
   git add src/main/java/com/authx/sdk/
   git commit -m "refactor(sdk): gut cache/watch references from SDK internals (SR:req-4, req-5, req-12, req-14 through req-19)"
   ```

---

### Task T019: Delete `transport/CachedTransport.java` [SR:req-10]

**Files:** `src/main/java/com/authx/sdk/transport/CachedTransport.java`

**Steps:**

1. `git rm src/main/java/com/authx/sdk/transport/CachedTransport.java`
2. Check for remaining references:
   ```bash
   grep -rln "CachedTransport" src/main/java/
   ```
   Should be empty (removed from Builder in T012).
3. `./gradlew :compileJava` — expect green.
4. Do NOT commit yet — batch with T020–T023.

---

### Task T020 [P]: Delete `transport/WatchCacheInvalidator.java` [SR:req-8]

**Files:** delete `src/main/java/com/authx/sdk/transport/WatchCacheInvalidator.java` (includes the inner `WatchStreamSession` class).

**Steps:**

1. `git rm src/main/java/com/authx/sdk/transport/WatchCacheInvalidator.java`
2. Find leftover references:
   ```bash
   grep -rln "WatchCacheInvalidator" src/main/java/
   ```
   Expect empty.

---

### Task T021 [P]: Delete `transport/WatchConnectionState.java` [SR:req-9]

**Files:** delete `src/main/java/com/authx/sdk/transport/WatchConnectionState.java`

**Steps:**

1. `git rm src/main/java/com/authx/sdk/transport/WatchConnectionState.java`
2. Verify no references: `grep -rln "WatchConnectionState" src/main/java/` — empty.

---

### Task T022 [P]: Delete `transport/SchemaLoader.java` [SR:req-11]

**Files:** delete `src/main/java/com/authx/sdk/transport/SchemaLoader.java`

**Steps:**

1. `git rm src/main/java/com/authx/sdk/transport/SchemaLoader.java`
2. Verify no references: `grep -rln "SchemaLoader" src/main/java/` — empty.

Commit T019–T022:

```bash
git commit -m "chore(transport): delete CachedTransport + Watch + SchemaLoader (SR:req-8, req-9, req-10, req-11)"
```

---

### Task T023 [P]: Delete `com.authx.sdk.watch/` package [SR:req-6]

**Files:** delete `src/main/java/com/authx/sdk/watch/`

**Steps:**

1. `git rm -r src/main/java/com/authx/sdk/watch/`
2. Verify: `ls src/main/java/com/authx/sdk/watch/ 2>&1` — "No such file or directory".
3. Verify no residual references: `grep -rln "com\.authx\.sdk\.watch" src/main/java/` — empty.

---

### Task T024 [P]: Delete `com.authx.sdk.dedup/` package [SR:req-7]

**Files:** delete `src/main/java/com/authx/sdk/dedup/`

**Steps:**

1. `git rm -r src/main/java/com/authx/sdk/dedup/`
2. `grep -rln "com\.authx\.sdk\.dedup" src/main/java/` — empty.

---

### Task T025 [P]: Delete `com.authx.sdk.cache/` package [SR:req-1]

**Files:** delete `src/main/java/com/authx/sdk/cache/`

**Steps:**

1. `git rm -r src/main/java/com/authx/sdk/cache/`
2. `grep -rln "com\.authx\.sdk\.cache" src/main/java/` — empty.
3. `./gradlew :compileJava` — expect green.

---

### Task T026 [P]: Delete `CacheHandle`, `SdkCaching`, `CachePolicy` [SR:req-2, req-3, req-17]

**Files:**
- `src/main/java/com/authx/sdk/CacheHandle.java`
- `src/main/java/com/authx/sdk/internal/SdkCaching.java`
- `src/main/java/com/authx/sdk/policy/CachePolicy.java`

**Steps:**

1. `git rm src/main/java/com/authx/sdk/CacheHandle.java src/main/java/com/authx/sdk/internal/SdkCaching.java src/main/java/com/authx/sdk/policy/CachePolicy.java`
2. `grep -rln "CacheHandle\|SdkCaching\|CachePolicy" src/main/java/` — empty.
3. `./gradlew :compileJava` — expect green.

Commit T023–T026:

```bash
git commit -m "chore: delete cache/ watch/ dedup/ packages + CacheHandle + SdkCaching + CachePolicy (SR:req-1, req-2, req-3, req-6, req-7, req-17)"
```

---

### Task T027 [P]: Delete Watch-specific SPI files [SR:req-13]

**Files:**
- `src/main/java/com/authx/sdk/spi/DuplicateDetector.java`
- `src/main/java/com/authx/sdk/spi/DroppedListenerEvent.java`
- `src/main/java/com/authx/sdk/spi/QueueFullPolicy.java`

**Steps:**

1. `git rm` all three.
2. `grep -rln "DuplicateDetector\|DroppedListenerEvent\|QueueFullPolicy" src/main/java/` — empty.
3. `./gradlew :compileJava` — expect green.
4. Commit:
   ```bash
   git commit -m "chore(spi): delete DuplicateDetector / DroppedListenerEvent / QueueFullPolicy (SR:req-13)"
   ```

---

### Task T028: Delete cache-directory tests [SR:req-1]

**Files:** delete `src/test/java/com/authx/sdk/cache/` (entire subdirectory)

**Steps:**

1. List existing tests:
   ```bash
   find src/test/java/com/authx/sdk/cache -name '*.java' 2>/dev/null
   ```
2. `git rm -r src/test/java/com/authx/sdk/cache/`
3. If the directory doesn't exist, skip this task.
4. `./gradlew :compileTestJava` — expect green.

---

### Task T029 [P]: Delete transport cache/watch tests [SR:req-1, req-6, req-10]

**Files:**
- `src/test/java/com/authx/sdk/transport/CachedTransportTest.java`
- `src/test/java/com/authx/sdk/transport/WatchCacheInvalidatorTest.java`
- `src/test/java/com/authx/sdk/transport/WatchCacheInvalidatorOrderingTest.java`
- `src/test/java/com/authx/sdk/transport/WatchConnectionStateTest.java`
- `src/test/java/com/authx/sdk/transport/WatchListenerQueuePolicyTest.java`

**Steps:**

1. `git rm` all five files. If any don't exist, skip that one silently.
2. `./gradlew :compileTestJava :test` — expect green.
3. Commit T028 + T029 together:
   ```bash
   git commit -m "chore(test): delete cache + Watch test suites (SR:req-1, req-6, req-10)"
   ```

---

### Task T030 [P]: Create `BuilderCacheMethodRemovedTest` [SR:req-19]

**Files:** create `src/test/java/com/authx/sdk/BuilderCacheMethodRemovedTest.java`

**Steps:**

1. Write:
   ```java
   package com.authx.sdk;

   import org.junit.jupiter.api.Test;

   import java.lang.reflect.Field;
   import java.lang.reflect.Method;
   import java.util.Arrays;

   import static org.assertj.core.api.Assertions.assertThat;

   /**
    * Regression guard for the 2026-04-18 L1 cache removal: asserts that
    * the cache-related builder API surface has not crept back in.
    */
   class BuilderCacheMethodRemovedTest {

       @Test
       void builderHasNoCacheMethod() {
           boolean hasCache = Arrays.stream(AuthxClientBuilder.class.getDeclaredMethods())
                   .anyMatch(m -> m.getName().equals("cache"));
           assertThat(hasCache)
                   .as("AuthxClientBuilder.cache(...) must not exist — see ADR 2026-04-18")
                   .isFalse();
       }

       @Test
       void builderHasNoCacheFields() {
           String[] forbidden = {"cacheEnabled", "cacheMaxSize", "watchInvalidation",
                                 "listenerQueueOnFull", "watchStrategies"};
           for (String name : forbidden) {
               boolean present = Arrays.stream(AuthxClientBuilder.class.getDeclaredFields())
                       .anyMatch(f -> f.getName().equals(name));
               assertThat(present)
                       .as("AuthxClientBuilder must not declare field %s", name)
                       .isFalse();
           }
       }

       @Test
       void authxClientHasNoCacheMethod() {
           boolean hasCache = Arrays.stream(AuthxClient.class.getDeclaredMethods())
                   .anyMatch(m -> m.getName().equals("cache"));
           assertThat(hasCache)
                   .as("AuthxClient.cache() must not exist — see ADR 2026-04-18")
                   .isFalse();
       }

       @Test
       void authxClientHasNoWatchSubscriptionMethods() {
           boolean hasWatchSub = Arrays.stream(AuthxClient.class.getDeclaredMethods())
                   .anyMatch(m -> m.getName().equals("onRelationshipChange")
                           || m.getName().equals("offRelationshipChange"));
           assertThat(hasWatchSub)
                   .as("AuthxClient Watch subscription methods must not exist")
                   .isFalse();
       }
   }
   ```
2. Run:
   ```bash
   ./gradlew :test --tests com.authx.sdk.BuilderCacheMethodRemovedTest --rerun
   ```
3. Expected: all 4 tests pass.

---

### Task T031 [P]: Create `NoWatchStreamStartsTest` [SR:req-6, req-8]

**Files:** create `src/test/java/com/authx/sdk/NoWatchStreamStartsTest.java`

**Steps:**

1. Write:
   ```java
   package com.authx.sdk;

   import org.junit.jupiter.api.Test;

   import java.util.Arrays;

   import static org.assertj.core.api.Assertions.assertThat;

   /**
    * Regression guard for the 2026-04-18 Watch subsystem removal:
    * building an SDK client must not spawn any authx-sdk-watch thread.
    */
   class NoWatchStreamStartsTest {

       @Test
       void inMemoryClient_noWatchThreadStarted() throws Exception {
           int before = countAuthxWatchThreads();
           try (var client = AuthxClient.inMemory()) {
               // Give a moment for any rogue background thread to start.
               Thread.sleep(100);
               int after = countAuthxWatchThreads();
               assertThat(after)
                       .as("AuthxClient.inMemory() must not start any 'authx-sdk-watch' thread")
                       .isEqualTo(before);
           }
       }

       private static int countAuthxWatchThreads() {
           Thread[] threads = new Thread[Thread.activeCount() * 2];
           int n = Thread.enumerate(threads);
           return (int) Arrays.stream(threads, 0, n)
                   .filter(t -> t != null && t.getName().startsWith("authx-sdk-watch"))
                   .count();
       }
   }
   ```
2. Run:
   ```bash
   ./gradlew :test --tests com.authx.sdk.NoWatchStreamStartsTest --rerun
   ```
3. Expected: 1 test passes.

---

### Task T032 [P]: Create `TransportChainTest` [SR:req-10, req-25]

**Files:** create `src/test/java/com/authx/sdk/transport/TransportChainTest.java`

**Steps:**

1. The transport chain inside an `AuthxClient.inMemory()` doesn't include `ResilientTransport` or `CachedTransport` today — in-memory goes straight to `InMemoryTransport`. For this test we inspect the `AuthxClientBuilder` plumbing. Write a test that uses a real (InProcess) channel to build a normal client and reflects into its transport chain:

   ```java
   package com.authx.sdk.transport;

   import com.authx.sdk.AuthxClient;
   import io.grpc.inprocess.InProcessChannelBuilder;
   import io.grpc.inprocess.InProcessServerBuilder;
   import io.grpc.Server;
   import org.junit.jupiter.api.Test;

   import java.lang.reflect.Field;

   import static org.assertj.core.api.Assertions.assertThat;

   /**
    * Regression guard: the transport chain must not contain a
    * CachedTransport (deleted 2026-04-18), and must still contain a
    * CoalescingTransport (preserved).
    */
   class TransportChainTest {

       @Test
       void chainHasNoCachedTransportAndHasCoalescing() throws Exception {
           String name = InProcessServerBuilder.generateName();
           Server server = InProcessServerBuilder.forName(name)
                   .directExecutor().build().start();
           try {
               var channel = InProcessChannelBuilder.forName(name).directExecutor();
               try (var client = AuthxClient.builder()
                       .connection(c -> c.target("inprocess:" + name).presharedKey("k"))
                       .build()) {
                   SdkTransport t = extractTransport(client);
                   boolean foundCached = false;
                   boolean foundCoalescing = false;
                   while (t != null) {
                       String cn = t.getClass().getSimpleName();
                       if (cn.equals("CachedTransport")) foundCached = true;
                       if (cn.equals("CoalescingTransport")) foundCoalescing = true;
                       t = unwrapDelegate(t);
                   }
                   assertThat(foundCached)
                           .as("CachedTransport must not appear in the chain")
                           .isFalse();
                   assertThat(foundCoalescing)
                           .as("CoalescingTransport must still appear in the chain")
                           .isTrue();
               }
           } finally {
               server.shutdownNow();
           }
       }

       private static SdkTransport extractTransport(AuthxClient client) throws Exception {
           Field f = AuthxClient.class.getDeclaredField("transport");
           f.setAccessible(true);
           return (SdkTransport) f.get(client);
       }

       private static SdkTransport unwrapDelegate(SdkTransport t) {
           if (!(t instanceof ForwardingTransport ft)) return null;
           try {
               Field df = ForwardingTransport.class.getDeclaredField("delegate");
               // or each subclass's own delegate field — fall back to reflection loop
               df.setAccessible(true);
               Object inner = df.get(ft);
               return inner instanceof SdkTransport s ? s : null;
           } catch (NoSuchFieldException nf) {
               // Try subclass-specific field if ForwardingTransport doesn't hold delegate
               for (Field f : t.getClass().getDeclaredFields()) {
                   if (f.getType().isAssignableFrom(SdkTransport.class)
                           || SdkTransport.class.isAssignableFrom(f.getType())) {
                       f.setAccessible(true);
                       Object inner = f.get(t);
                       if (inner instanceof SdkTransport s) return s;
                   }
               }
               return null;
           } catch (Exception e) {
               return null;
           }
       }
   }
   ```

   NOTE: if `ForwardingTransport` doesn't have a `delegate` field at the base class (it's abstract-accessor-style), the `unwrapDelegate` logic needs to scan each wrapper's private `delegate` field via reflection. The test above includes a fallback scan loop.

2. Build a connection against an InProcess server so the builder's production path executes (including ResilientTransport, CoalescingTransport, etc.). `AuthxClient.inMemory()` would skip too many wrappers.

3. Run:
   ```bash
   ./gradlew :test --tests com.authx.sdk.transport.TransportChainTest --rerun
   ```

4. Expected: test passes. If it fails because of reflection hitches on delegate fields, adjust `unwrapDelegate` to explicitly check each Transport class's known delegate-field name (e.g., `delegate`).

Commit T030–T032:

```bash
git commit -m "test: add regression guards for cache/Watch removal (SR:req-19, req-6, req-10, req-25)"
```

---

### Task T033 [P]: Update `CLAUDE.md` package structure [SR:req-30]

**Files:** `CLAUDE.md`

**Steps:**

1. In the "Project structure" diagram, delete the lines:
   ```
   ├── cache/                     # Cache<K,V>, Caffeine, tiered, schema cache
   ...
   ├── watch/                     # Watch dispatcher, strategies
   ├── dedup/                     # Watch cursor-replay duplicate detector
   ```
2. Delete the line about `action/` containing `GrantCompletion/RevokeCompletion` (they stay — but the annotation about "listener handles" stays; only remove cache references).
3. Update the `internal/` description — remove mention of `SdkCaching`.
4. In the "Tech stack" section, update the Cache line:
   ```
   - **Cache**: Caffeine (optional, single-tier L1) with Watch-based invalidation
   ```
   to:
   ```
   - (no SDK-side cache — SpiceDB's server-side dispatch cache is the decision cache)
   ```

---

### Task T034 [P]: Update `README.md` [SR:req-31, req-33]

**Files:** `README.md`

**Steps:**

1. In the "进阶用法" section, delete the entire "### 缓存配置" subsection.
2. In the "进阶用法" section, delete the entire "### Watch 实时变更监听" subsection.
3. In the "可插拔 SPI" section, delete subsections about `DuplicateDetector`, `watchListenerExecutor`, and any cache-related SPI.
4. Add a prominent Changelog entry at the top of the Changelog section (before the 2026-04-18 Write Listener API entry):
   ```markdown
   ### 未发布 — 移除 L1 本地缓存 + Watch 基础设施 (2026-04-18, BREAKING)

   根据 [ADR 2026-04-18](docs/adr/2026-04-18-remove-l1-cache.md) 移除 SDK 所有客户端决策缓存。

   **破坏性变更** —— 以下 API 已删除，升级需调整调用：
   - `AuthxClientBuilder#cache(...)` / `CacheConfig` 整块配置
   - `AuthxClient#cache()` / `CacheHandle`
   - `AuthxClient#onRelationshipChange(...)` / `offRelationshipChange(...)`
   - `SdkComponents.Builder#watchListenerExecutor(...)` / `watchListenerDropHandler(...)` / `watchDuplicateDetector(...)`
   - `ExtendConfig#addWatchStrategy(...)`
   - 整个 `com.authx.sdk.cache`、`com.authx.sdk.watch`、`com.authx.sdk.dedup` 包
   - `CachePolicy`、`SchemaCache`、`SchemaLoader`、`CacheHandle`、`SdkCaching`
   - SPI：`DuplicateDetector`、`DroppedListenerEvent`、`QueueFullPolicy`

   **保留**：`CoalescingTransport`（in-flight 去重）、`DistributedTokenStore`（跨 JVM SESSION 一致性）、全部 resilience 机制。

   **性能影响**：`check()` p50 从 ~3μs（缓存命中）变为 ~1-5ms（SpiceDB RTT）。业务可选 `Consistency.minimizeLatency()` 命中 SpiceDB dispatch cache 降低延迟（schema-aware，无继承失效问题）。
   ```

---

### Task T035 [P]: Update `README_en.md` [SR:req-31]

**Files:** `README_en.md`

**Steps:**

1. Mirror T034 — delete the English-language cache/Watch sections and SPI mentions.
2. README_en doesn't have a Changelog section today — add a prominent note near the top (or wherever breaking changes are tracked) describing the removal. If none exists, add a new section:
   ```markdown
   ## Breaking Change — 2026-04-18

   The L1 in-process cache (`CaffeineCache`) and Watch stream infrastructure
   have been removed entirely. See [ADR](docs/adr/2026-04-18-remove-l1-cache.md)
   for rationale.

   If your code uses any of the following, it will no longer compile:
   `AuthxClientBuilder#cache(...)`, `AuthxClient#cache()`,
   `AuthxClient#onRelationshipChange(...)`, `com.authx.sdk.cache.*`,
   `com.authx.sdk.watch.*`, `com.authx.sdk.dedup.*`, `CachePolicy`,
   `SchemaCache`, `CacheHandle`, `DuplicateDetector`, `DroppedListenerEvent`,
   `QueueFullPolicy`.
   ```

---

### Task T036 [P]: Update `llms.txt` [SR:req-32]

**Files:** `llms.txt`

**Steps:**

1. `grep -n "cache\|watch\|Watch" llms.txt` — find references.
2. Delete any bullet or example that mentions `.cache(...)` configuration or `onRelationshipChange` or watch-related behavior.
3. If the file's "Do" list mentions caching in any way, remove that bullet.

Commit T033–T036:

```bash
git commit -m "docs: remove cache/Watch sections; changelog entry for breaking removal (SR:req-30, req-31, req-32, req-33)"
```

---

### Task T037: Full suite + downstream compile

**Files:** none (verification only).

**Steps:**

1. Full SDK test suite:
   ```bash
   ./gradlew :test -x :test-app:test -x :cluster-test:test --rerun
   ```
   Expected: BUILD SUCCESSFUL, 0 failures.
2. Aggregate totals:
   ```bash
   awk -F'"' '/testsuite / {t+=$4; s+=$6; f+=$8; e+=$10} END{
       print "tests="t, "skipped="s, "failures="f, "errors="e
   }' build/test-results/test/*.xml
   ```
   Expected: `failures=0 errors=0`.
3. Downstream compile:
   ```bash
   ./gradlew :test-app:compileJava :cluster-test:compileJava :sdk-redisson:compileJava
   ```
   Expected: BUILD SUCCESSFUL.
4. Javadoc:
   ```bash
   ./gradlew javadoc
   ```
   Expected: BUILD SUCCESSFUL (no dangling `{@link}` references to deleted types). If it complains about missing types in Javadoc, audit the source for stale `{@link com.authx.sdk.cache.*}` etc. and remove.
5. (No commit — verification only.)

---

### Task T038: Final scope check and commit log

**Files:** none (validation only).

**Steps:**

1. Verify only expected files changed/deleted vs `cleanup/post-sr-critical` (the branch's base):
   ```bash
   git diff --name-status cleanup/post-sr-critical...HEAD | head -80
   ```
2. The diff should show:
   - Added: spec.md, plan.md, tasks.md, 3 regression tests, ADR
   - Deleted: entire cache/, watch/, dedup/, 5+ transport files, 3 SPI files, CacheHandle, SdkCaching, CachePolicy, cache/Watch tests
   - Modified: AuthxClient, AuthxClientBuilder, TypedGrantAction, TypedRevokeAction, ResourceFactory, SdkComponents, SdkTypedEvent, SdkMetrics, ResourcePolicy, PolicyRegistry, README.md, README_en.md, CLAUDE.md, llms.txt, test-app files, cluster-test files
3. (No commit — validation only.)

---

## Self-Review

**Pass 1 — Coverage:**

| Spec Requirement | Task(s) | Status |
|---|---|---|
| req-1 Delete cache/ package | T025 | Covered |
| req-2 Delete CacheHandle | T026 | Covered |
| req-3 Delete SdkCaching | T026 | Covered |
| req-4 Drop caching from AuthxClient constructor | T013, T014 | Covered |
| req-5 Remove AuthxClient#cache() | T013 | Covered |
| req-6 Delete watch/ package | T023 | Covered |
| req-7 Delete dedup/ package | T024 | Covered |
| req-8 Delete WatchCacheInvalidator | T020 | Covered |
| req-9 Delete WatchConnectionState | T021 | Covered |
| req-10 Delete CachedTransport | T019 | Covered |
| req-11 Delete SchemaLoader | T022 | Covered |
| req-12 Remove onRelationshipChange/offRelationshipChange | T013 | Covered |
| req-13 Delete Watch SPI files | T027 | Covered |
| req-14 Remove watch fields from SdkComponents | T015 | Covered |
| req-15 Remove cache/watch events from SdkTypedEvent | T017 | Covered |
| req-16 Remove cache metrics from SdkMetrics | T018 | Covered |
| req-17 Remove CachePolicy + cache field from ResourcePolicy | T016, T026 | Covered |
| req-18 Drop schema validation from typed actions | T011 | Covered |
| req-19 Delete CacheConfig + cache method from builder | T012, T030 | Covered |
| req-20 Delete validation rules for cache | T012 | Covered |
| req-21 Delete addWatchStrategy + watchStrategies | T012 | Covered |
| req-22 Update test-app consumers | T002, T003 | Covered |
| req-23 Update cluster-test consumers | T004, T005, T006, T007, T008, T009, T010 | Covered |
| req-24 sdk-redisson verified unchanged | T037 (compile check) | Covered |
| req-25 Preserve CoalescingTransport | T032 (regression test) | Covered |
| req-26 Preserve DistributedTokenStore | T037 (compile check) | Covered |
| req-27 Preserve TokenTracker | T037 (compile check implicit) | Covered |
| req-28 Preserve non-cache/watch components | T037 | Covered |
| req-29 Write companion ADR | (ADR already written during brainstorming) | Covered |
| req-30 Update CLAUDE.md | T033 | Covered |
| req-31 Update README.md / README_en.md | T034, T035 | Covered |
| req-32 Update llms.txt | T036 | Covered |
| req-33 Changelog entry | T034 | Covered |

No GAPs.

**Pass 2 — Placeholder scan:** No TBD/TODO/implement-later in tasks above. Every code edit step shows full replacement code or points at exact fields/methods to remove.

**Pass 3 — Type consistency:** Type names used in multiple tasks:
- `AuthxClient`, `AuthxClientBuilder`, `ResourceFactory`, `TypedGrantAction`, `TypedRevokeAction` — consistent
- `SdkCaching`, `SdkComponents`, `SdkMetrics`, `SdkTypedEvent`, `ResourcePolicy`, `PolicyRegistry` — consistent
- `CachedTransport`, `WatchCacheInvalidator`, `WatchConnectionState`, `SchemaLoader`, `CacheHandle`, `CachePolicy`, `SchemaCache` — all slated for deletion, consistent
- `CoalescingTransport`, `DistributedTokenStore` — preserved, consistent

**Pass 4 — Dependency integrity:**
- Phase 1 (downstream cleanup) is independent of SDK changes — tasks within are [P].
- Phase 2 (T011–T018) has a critical ordering: T012 breaks compile; T013+T014 restore it; T015–T018 touch different files and can be in any order inside the batch.
- Phase 3 (T019–T022) deletes transport files after T012 removed the Builder references — order is safe.
- Phase 4+ (T023–T026) delete packages AFTER transport files that referenced them are gone.
- Phase 5 (T027) SPIs don't depend on each other.
- Phase 6 (T028–T029) test deletions can only happen after the classes they test are gone.
- Phase 7 (T030–T032) new tests must run against the post-deletion codebase.
- Phase 8 (T033–T036) docs independent of everything else, can run in parallel.
- Phase 9 (T037–T038) must be last.

Parallel [P] markers are internally consistent.

**Pass 5 — Contradiction scan:** plan.md lists preserved classes (CoalescingTransport, DistributedTokenStore, TokenTracker, TypedResourceEntry/action chain, resilience stack) identical to spec.md's req-25 through req-28. Removal list identical. No contradictions.
