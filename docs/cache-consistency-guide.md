# Cache and Consistency Guide

The AuthX SDK provides an optional caching layer for `check()` results to reduce latency and SpiceDB load. This guide covers what gets cached, how TTLs work, cache tiers, Watch-based invalidation, and consistency modes.

---

## Which Requests Are Cached

Only `check()` results that satisfy **both** of these conditions are cached:

1. **Consistency is `MinimizeLatency`** -- requests with `full()`, `atLeast()`, or `atExactSnapshot()` consistency always bypass the cache
2. **No caveat context** -- requests with a non-empty `caveatContext` always bypass the cache (caveat evaluation depends on runtime context values that may differ between calls)

All other operation types (`writeRelationships`, `deleteRelationships`, `lookupSubjects`, `lookupResources`, `readRelationships`, `expand`) are **never cached**.

Writes (`writeRelationships`, `deleteRelationships`, `deleteByFilter`) trigger **double-delete invalidation** (Facebook TAO / Hibernate L2 / Spring Cache pattern): all cached entries for the affected resource are evicted **both before and after** the write reaches SpiceDB. The post-write invalidation runs in `finally` so partial writes (where SpiceDB persisted some updates before throwing) still purge the cache. Without the post-invalidation, a concurrent reader landing between the pre-invalidation and the write completion can repopulate the cache with the soon-to-be-stale value, poisoning it for the full TTL window.

---

## Cache Implementation

The SDK uses `CaffeineCache` (in-process) for caching `check()` results. Cache hit latency is < 1µs.

If Caffeine is not on the classpath, the SDK logs a warning and falls back to a no-op cache.

### Cache key indexing

`CaffeineCache` maintains a secondary index by resource (`resourceType:resourceId`). This enables O(k) invalidation of all cache entries for a specific resource, rather than O(n) full-cache scans.

### Single-flight (request coalescing)

`CaffeineCache.getOrLoad()` uses Caffeine's built-in single-flight: when multiple threads request the same cache key concurrently and all miss, only one thread executes the gRPC call. The other threads block and receive the same result.

---

## TTL Configuration

### Per-type and per-permission TTLs

TTLs are configured via `CachePolicy` within `PolicyRegistry`:

```java
PolicyRegistry.builder()
    .defaultPolicy(ResourcePolicy.builder()
        .cache(CachePolicy.builder()
            .ttl(Duration.ofSeconds(5))        // default TTL for all permissions
            .build())
        .build())
    .forResourceType("document", ResourcePolicy.builder()
        .cache(CachePolicy.builder()
            .ttl(Duration.ofSeconds(3))                         // base TTL for documents
            .forPermission("view", Duration.ofSeconds(10))      // view can be staler
            .forPermission("delete", Duration.ofMillis(500))    // delete must be fresh
            .build())
        .build())
    .forResourceType("folder", ResourcePolicy.builder()
        .cache(CachePolicy.ofTtl(Duration.ofSeconds(30)))
        .build())
    .forResourceType("group", ResourcePolicy.builder()
        .cache(CachePolicy.disabled())                          // no caching for groups
        .build())
    .build();
```

Resolution order: per-permission TTL -> per-resource-type TTL -> global default TTL.

### Default CachePolicy values

| Parameter | Default |
|-----------|---------|
| `enabled` | `true` (but `ResourcePolicy.defaults()` sets `enabled=false`) |
| `ttl` | `5 seconds` |
| `maxIdleTime` | `null` (no idle eviction) |

Note: the global `ResourcePolicy.defaults()` ships with cache **disabled**. You must explicitly enable caching to use it.

### +/-10% jitter

To prevent **cache stampede** (many entries expiring simultaneously and causing a burst of gRPC calls), the SDK applies +/-10% random jitter to each entry's TTL at creation time. For example, a 5-second TTL will actually expire between 4.5s and 5.5s.

This is implemented in `AuthxClient` using Caffeine's variable `Expiry` interface:

```java
long baseNanos = policies.resolveCacheTtl(key.resource().type(), key.permission().name()).toNanos();
long jitter = ThreadLocalRandom.current().nextLong(-baseNanos / 10, baseNanos / 10 + 1);
return baseNanos + jitter;
```

---

## Watch-Based Cache Invalidation

The SDK can subscribe to SpiceDB's Watch API to receive real-time relationship change notifications. `WatchCacheInvalidator` runs a background daemon thread that:

1. Opens a server-streaming gRPC Watch call to SpiceDB
2. On each `WatchResponse`, extracts changed resource types and IDs
3. Invalidates all cache entries for affected resources using the secondary index (O(k) per resource)
4. Dispatches `RelationshipChange` events to registered listeners asynchronously

### Invalidation scope

Watch invalidation operates at the **resource level**: when any relationship involving `document:123` changes, **all** cached `check()` entries for `document:123` are evicted (regardless of permission or subject).

### Reconnection behavior

| Scenario | Max failures | Then |
|----------|-------------|------|
| Never connected (server unreachable at startup) | 3 | Watch stops; cache relies on TTL only |
| Connected then disconnected (server went away) | 20 | Watch stops; cache relies on TTL only |

Reconnection uses exponential backoff starting at 1 second, doubling up to 30 seconds, with 25% random jitter. Permanent gRPC errors (UNIMPLEMENTED, UNAUTHENTICATED, PERMISSION_DENIED) cause immediate stop with a single warning log.

### Cursor expiry: full cache invalidation

When the disconnect window exceeds SpiceDB's GC window (default ~1 hour), the cursor SDK reconnects with becomes too old. SpiceDB returns `FAILED_PRECONDITION` and the SDK cannot resume from the last seen revision — events between the disconnect and reconnect are **permanently lost**.

To avoid serving wrong permission decisions from cache entries written before the gap, the SDK applies the K8s-informer / etcd / Debezium pattern: on cursor expiry it **fully invalidates the L1 cache** and publishes a `WatchCursorExpired` event before resubscribing from HEAD. Subscribe to alert on data-loss windows in production:

```java
client.eventBus().subscribe(SdkTypedEvent.WatchCursorExpired.class, e -> {
    log.error("Permission cache invalidated due to Watch gap (expired cursor: {})", e.expiredCursor());
    alertManager.fire("permission.watch_gap");
});
```

### Application-layer stall detection

gRPC keepalive only catches TCP-level death. A SpiceDB server that's deadlocked, a stuck datastore replica, or a middlebox dropping packets while keeping NAT alive can leave the Watch stream "connected but blind" — `hasNext()` blocks forever and cache invalidation silently stops working.

The SDK applies the Kafka `max.poll.interval.ms` / etcd `WithProgressNotify` pattern: it tracks the wall-clock time of the last received message (data or checkpoint, since SpiceDB sends checkpoints every few seconds even when idle) and forces a reconnect when the gap exceeds **60 seconds** (configurable via `WatchCacheInvalidator.setStaleStreamThreshold`). On detection the SDK publishes a `WatchStreamStale` event:

```java
client.eventBus().subscribe(SdkTypedEvent.WatchStreamStale.class, e -> {
    log.error("Watch stalled for {}s, reconnecting", e.idleFor().toSeconds());
});
```

### Graceful degradation

If Watch is unavailable (e.g., SpiceDB backend does not support it, or the connection is lost permanently), the SDK falls back to **TTL-only expiration**. A warning is logged once; cache continues to function with the configured TTLs. This means entries may be stale up to their TTL duration, but the system remains operational.

---

## SESSION Consistency Limitation

`ReadConsistency.session()` is the **default** read consistency mode. It provides read-after-write guarantees by tracking ZedTokens from write operations and using `at_least_as_fresh(lastWriteToken)` for subsequent reads on the same resource type.

### Single-JVM limitation

By default, SESSION consistency tokens are stored **in-process** in a `ConcurrentHashMap` inside `TokenTracker`. This means:

- Reads after writes within the **same JVM** are guaranteed to see the write
- Reads after writes from a **different JVM** (e.g., another pod in a Kubernetes deployment) will **not** see the write -- they will use `MinimizeLatency` (potentially stale by up to ~5 seconds, SpiceDB's quantization window)

### Cross-instance SESSION with DistributedTokenStore

To enable SESSION consistency across multiple SDK instances, implement the `DistributedTokenStore` SPI:

```java
public interface DistributedTokenStore {
    void set(String key, String token);  // must not throw -- log and swallow on error
    String get(String key);              // returns null on miss or error
}
```

Example with Redis:

```java
DistributedTokenStore store = new DistributedTokenStore() {
    public void set(String key, String token) {
        redis.setex("authx:token:" + key, 60, token);
    }
    public String get(String key) {
        return redis.get("authx:token:" + key);
    }
};
```

When provided, `TokenTracker` writes tokens to both local and distributed storage, and reads from distributed first (falling back to local on miss or error).

### Graceful degradation

If the distributed token store becomes unavailable at runtime, `TokenTracker` degrades to local-only tokens. The first failure logs a WARN; subsequent log lines are suppressed to avoid spam, but the SDK exposes the outage through three observable signals:

| Signal | Use |
|---|---|
| `SdkTypedEvent.TokenStoreUnavailable(reason)` | Fired on first failure — subscribe to alert |
| `SdkTypedEvent.TokenStoreRecovered` | Fired when an operation succeeds again — subscribe to clear alerts |
| `tokenTracker.distributedFailureCount()` | LongAdder counter — accumulates every failure for monitoring dashboards |
| `tokenTracker.isDistributedAvailable()` | Boolean for K8s readiness probes |

Without these signals the application would have no way to detect that cross-instance SESSION consistency is broken — reads would silently fall back to MinimizeLatency and serve stale data.

### Consistency modes reference

| Mode | SpiceDB mapping | Cache behavior | Use case |
|------|----------------|----------------|----------|
| `minimizeLatency()` | `minimize_latency=true` | Cached (if enabled) | High-throughput reads where ~5s staleness is acceptable |
| `session()` | `at_least_as_fresh(token)` after a write; `minimize_latency` otherwise | Bypasses cache after write | Default -- read-after-write safety |
| `strong()` | `fully_consistent=true` | Always bypasses cache | Admin operations, security-critical checks |
| `snapshot()` | `at_exact_snapshot(token)` | Always bypasses cache | Pagination, export (frozen point in time) |
| `boundedStaleness(duration)` | `minimize_latency` (planned: time-based bound) | Cached | Future use -- exact mapping depends on SpiceDB version |

Note: `PolicyAwareConsistencyTransport` sits **above** `CachedTransport` in the transport chain. When a SESSION policy detects a prior write token, it upgrades the consistency to `atLeast(token)`, which correctly bypasses the cache. When no write token exists, consistency remains `MinimizeLatency` and the cache is used normally.
