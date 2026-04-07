# Cache Layer Refactor Implementation Plan

> **For agentic workers:** Use authx-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the generic L2 cache SPI with built-in Caffeine (L1) + Redis/Lettuce (L2), fix TieredCache IndexedCache support, and ensure O(1) Watch invalidation for Redis.

**Architecture:** TieredCache gains `IndexedCache` support. A new `RedisCacheAdapter` uses Redis Hash-per-resource structure for O(1) invalidation. `SdkComponents.l2Cache()` is removed; Redis is configured via `CacheConfig.redis(RedisClient)`. Lettuce is a `compileOnly` dependency like Caffeine.

**Tech Stack:** Java 21, Lettuce 6.3.2, Caffeine 3.1.8, JUnit 5, Mockito

---

## File Structure

### New Files

| File | Purpose |
|---|---|
| `src/main/java/com/authx/sdk/cache/RedisCacheAdapter.java` | IndexedCache backed by Redis Hash via Lettuce |
| `src/test/java/com/authx/sdk/cache/RedisCacheAdapterTest.java` | Unit tests with mocked Lettuce commands |
| `src/test/java/com/authx/sdk/cache/TieredCacheIndexedTest.java` | TieredCache IndexedCache delegation tests |

### Modified Files

| File | Changes |
|---|---|
| `build.gradle` | Add `compileOnly("io.lettuce:lettuce-core:6.3.2.RELEASE")`, `testImplementation` same |
| `src/.../cache/TieredCache.java` | `implements Cache` → `implements IndexedCache`, add `invalidateByIndex()` |
| `src/.../spi/SdkComponents.java` | Remove `l2Cache` field and builder method |
| `src/.../AuthxClient.java` | CacheConfig: add `redis()`/`redisTtl()` fields; buildTransportStack: new L2 wiring |
| `docs/cache-consistency-guide.md` | Update L2 section for Redis configuration |

---

### Task T001: Add Lettuce dependency

**Files:**
- Modify: `build.gradle`

**Steps:**
1. Add Lettuce compileOnly and testImplementation:

```groovy
compileOnly("io.lettuce:lettuce-core:6.3.2.RELEASE")
// in test block:
testImplementation("io.lettuce:lettuce-core:6.3.2.RELEASE")
```

2. Run `./gradlew compileJava`
3. Commit: "build: add Lettuce compileOnly dependency"

---

### Task T002: TieredCache implements IndexedCache

**Files:**
- Modify: `src/main/java/com/authx/sdk/cache/TieredCache.java`
- Create: `src/test/java/com/authx/sdk/cache/TieredCacheIndexedTest.java`

**Steps:**
1. Change class declaration:
   ```java
   public class TieredCache<K, V> implements IndexedCache<K, V> {
   ```

2. Add `invalidateByIndex` method:
   ```java
   @Override
   public void invalidateByIndex(String indexKey) {
       if (l1 instanceof IndexedCache<K, V> idx) idx.invalidateByIndex(indexKey);
       else l1.invalidateAll(key -> true); // fallback: should not happen with CaffeineCache
       if (l2 instanceof IndexedCache<K, V> idx) idx.invalidateByIndex(indexKey);
       else l2.invalidateAll(key -> true); // fallback: should not happen with RedisCacheAdapter
   }
   ```

3. Write test `TieredCacheIndexedTest`:
   ```java
   @Test
   void invalidateByIndex_delegatesToBothLayers() {
       // Use two CaffeineCache instances (both implement IndexedCache)
       // Put entries in both, call invalidateByIndex, verify both cleared
   }

   @Test
   void invalidateByIndex_onlyAffectsMatchingResource() {
       // Put entries for doc:1 and doc:2, invalidate doc:1, verify doc:2 remains
   }
   ```

4. Run `./gradlew test --tests "com.authx.sdk.cache.TieredCacheIndexedTest"`
5. Commit: "feat: TieredCache implements IndexedCache"

---

### Task T003: Create RedisCacheAdapter

**Files:**
- Create: `src/main/java/com/authx/sdk/cache/RedisCacheAdapter.java`
- Create: `src/test/java/com/authx/sdk/cache/RedisCacheAdapterTest.java`

**Steps:**
1. Create `RedisCacheAdapter`:
   ```java
   package com.authx.sdk.cache;

   import com.authx.sdk.model.CheckKey;
   import com.authx.sdk.model.CheckResult;
   import com.authx.sdk.model.enums.Permissionship;
   import io.lettuce.core.api.sync.RedisCommands;

   import java.time.Instant;
   import java.util.Optional;
   import java.util.concurrent.atomic.LongAdder;
   import java.util.function.Function;
   import java.util.function.Predicate;

   public class RedisCacheAdapter implements IndexedCache<CheckKey, CheckResult> {

       private static final String KEY_PREFIX = "authx:check:";
       private final RedisCommands<String, String> commands;
       private final long ttlSeconds;
       private final LongAdder hits = new LongAdder();
       private final LongAdder misses = new LongAdder();

       public RedisCacheAdapter(RedisCommands<String, String> commands, long ttlSeconds) {
           this.commands = commands;
           this.ttlSeconds = ttlSeconds;
       }

       @Override
       public Optional<CheckResult> get(CheckKey key) {
           var result = getIfPresent(key);
           return Optional.ofNullable(result);
       }

       @Override
       public CheckResult getIfPresent(CheckKey key) {
           String raw = commands.hget(redisKey(key), field(key));
           if (raw == null) { misses.increment(); return null; }
           hits.increment();
           return deserialize(raw);
       }

       @Override
       public void put(CheckKey key, CheckResult value) {
           String rk = redisKey(key);
           commands.hset(rk, field(key), serialize(value));
           commands.expire(rk, ttlSeconds);
       }

       @Override
       public CheckResult getOrLoad(CheckKey key, Function<CheckKey, CheckResult> loader) {
           CheckResult v = getIfPresent(key);
           if (v != null) return v;
           v = loader.apply(key);
           if (v != null) put(key, v);
           return v;
       }

       @Override
       public void invalidate(CheckKey key) {
           commands.hdel(redisKey(key), field(key));
       }

       @Override
       public void invalidateByIndex(String indexKey) {
           commands.del(KEY_PREFIX + indexKey);
       }

       @Override
       public void invalidateAll(Predicate<CheckKey> filter) {
           throw new UnsupportedOperationException(
               "Predicate-based invalidation not supported for Redis. Use invalidateByIndex().");
       }

       @Override
       public void invalidateAll() {
           // No-op: full Redis flush is dangerous in shared environments
       }

       @Override
       public long size() { return -1; }

       @Override
       public CacheStats stats() {
           return new CacheStats(hits.sum(), misses.sum(), 0);
       }

       // Key: authx:check:{resourceType}:{resourceId}
       private String redisKey(CheckKey key) {
           return KEY_PREFIX + key.resourceIndex();
       }

       // Field: {permission}:{subjectType}:{subjectId}
       private String field(CheckKey key) {
           return key.permission().name() + ":" + key.subject().type() + ":" + key.subject().id();
       }

       private String serialize(CheckResult r) {
           String exp = r.expiresAt().map(Instant::toString).orElse("");
           return r.permissionship().name() + "|" + (r.zedToken() != null ? r.zedToken() : "") + "|" + exp;
       }

       private CheckResult deserialize(String raw) {
           String[] parts = raw.split("\\|", -1);
           if (parts.length < 2) return null;
           var perm = Permissionship.valueOf(parts[0]);
           var token = parts[1].isEmpty() ? null : parts[1];
           var expiresAt = parts.length > 2 && !parts[2].isEmpty()
                   ? Optional.of(Instant.parse(parts[2])) : Optional.<Instant>empty();
           return new CheckResult(perm, token, expiresAt);
       }
   }
   ```

2. Write `RedisCacheAdapterTest` using Mockito to mock `RedisCommands<String, String>`:
   ```java
   @Test void get_hit_returnsResult()
   @Test void get_miss_returnsEmpty()
   @Test void put_callsHsetAndExpire()
   @Test void invalidate_callsHdel()
   @Test void invalidateByIndex_callsDel()
   @Test void invalidateAll_predicate_throwsUnsupported()
   @Test void stats_countsHitsAndMisses()
   @Test void serialize_deserialize_roundTrip()
   ```

3. Run tests
4. Commit: "feat: RedisCacheAdapter — IndexedCache backed by Redis Hash"

---

### Task T004: Remove SdkComponents.l2Cache

**Files:**
- Modify: `src/main/java/com/authx/sdk/spi/SdkComponents.java`

**Steps:**
1. Remove `l2Cache` from the record:
   ```java
   public record SdkComponents(
           TelemetrySink telemetrySink,
           SdkClock clock,
           DistributedTokenStore tokenStore
   ) {
       public static SdkComponents defaults() {
           return new SdkComponents(TelemetrySink.NOOP, SdkClock.SYSTEM, null);
       }
   }
   ```

2. Remove `l2Cache` from Builder:
   - Remove `private Cache<...> l2Cache` field
   - Remove `l2Cache(...)` method
   - Update `build()` to not pass l2Cache

3. Remove `import com.authx.sdk.cache.Cache` and `import com.authx.sdk.model.CheckKey/CheckResult` if now unused

4. Run `./gradlew compileJava` — expect compile errors in AuthxClient (next task fixes those)

5. Commit (may defer to after T005 if compile fails)

---

### Task T005: Update AuthxClient Builder — Redis config + wiring

**Files:**
- Modify: `src/main/java/com/authx/sdk/AuthxClient.java`

**Steps:**
1. Add fields to Builder (after `watchInvalidation`):
   ```java
   private Object redisClient;  // io.lettuce.core.RedisClient (Object to avoid compile-time dep)
   private Duration redisTtl = Duration.ofSeconds(30);
   ```

2. Add methods to CacheConfig:
   ```java
   public class CacheConfig {
       public CacheConfig enabled(boolean e) { Builder.this.cacheEnabled = e; return this; }
       public CacheConfig maxSize(long s) { Builder.this.cacheMaxSize = s; return this; }
       public CacheConfig watchInvalidation(boolean e) { Builder.this.watchInvalidation = e; return this; }
       public CacheConfig redis(Object redisClient) { Builder.this.redisClient = redisClient; return this; }
       public CacheConfig redisTtl(Duration ttl) { Builder.this.redisTtl = ttl; return this; }
   }
   ```

3. Update `buildTransportStack()` cache construction — replace `spi.l2Cache()` logic:
   ```java
   if (cacheEnabled) {
       Cache<CheckKey, CheckResult> effectiveCache;
       try {
           var expiry = new Expiry<CheckKey, CheckResult>() { /* same as before */ };
           var l1 = new CaffeineCache<>(cacheMaxSize, expiry, CheckKey::resourceIndex);

           if (redisClient != null) {
               try {
                   var redis = buildRedisCache();
                   effectiveCache = new TieredCache<>(l1, redis);
               } catch (NoClassDefFoundError e) {
                   LOG.log(WARNING, "Redis cache requested but Lettuce not on classpath. " +
                       "Add: io.lettuce:lettuce-core:6.3.2.RELEASE. Falling back to L1 only.");
                   effectiveCache = l1;
               }
           } else {
               effectiveCache = l1;
           }
       } catch (NoClassDefFoundError e) {
           LOG.log(WARNING, "Cache enabled but Caffeine not on classpath. ...");
           effectiveCache = Cache.noop();
       }
       // ... rest unchanged
   }
   ```

4. Add private helper `buildRedisCache()`:
   ```java
   private RedisCacheAdapter buildRedisCache() {
       var client = (io.lettuce.core.RedisClient) redisClient;
       var connection = client.connect();
       var commands = connection.sync();
       return new RedisCacheAdapter(commands, redisTtl.toSeconds());
   }
   ```

5. Fix `SdkComponents.defaults()` call (no more l2Cache param) and any other `spi.l2Cache()` references

6. Run `./gradlew compileJava`
7. Run `./gradlew test -x :test-app:test`
8. Commit: "feat: Redis L2 cache via CacheConfig.redis() — replaces SdkComponents.l2Cache"

---

### Task T006: Watch invalidation path verification test

**Files:**
- Create: `src/test/java/com/authx/sdk/transport/WatchInvalidationPathTest.java`

**Steps:**
1. Write test that verifies Watch uses `invalidateByIndex` path (not `invalidateAll(Predicate)`):
   ```java
   @Test
   void watchInvalidation_usesTieredCacheIndexedPath() {
       // Setup: CaffeineCache (L1) + mock IndexedCache (L2) wrapped in TieredCache
       // Put an entry, call invalidateByIndex
       // Verify L1 entry gone, L2 mock.invalidateByIndex called with correct key
   }

   @Test
   void multipleInvalidators_sharedL2_idempotent() {
       // Two WatchCacheInvalidator instances sharing same RedisCacheAdapter
       // Both call invalidateByIndex for same resource
       // Verify no errors (second DEL returns 0 — harmless)
   }
   ```

2. Run tests
3. Commit: "test: Watch invalidation path uses IndexedCache for L1+L2"

---

### Task T007: Update documentation

**Files:**
- Modify: `docs/cache-consistency-guide.md`

**Steps:**
1. Update L2 section: remove generic SPI references, add Redis configuration example:
   ```java
   var redisClient = RedisClient.create("redis://localhost:6379");
   AuthxClient.builder()
       .cache(c -> c.enabled(true).redis(redisClient).redisTtl(Duration.ofSeconds(30)))
       .build();
   ```

2. Update invalidation section: explain Redis Hash structure and O(1) DEL
3. Commit: "docs: update cache guide for Redis L2 configuration"

---

### Task T008: Final verification

**Steps:**
1. Run `./gradlew test -x :test-app:test`
2. Verify zero new failures
3. Grep for `spi.l2Cache` — zero results
4. Grep for `SdkComponents.*l2Cache` — zero results
5. Commit tasks.md with all items marked complete
