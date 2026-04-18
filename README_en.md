[中文](README.md)

# AuthX SpiceDB SDK

A high-performance Java client that connects directly to SpiceDB. No platform dependencies, tens of thousands of permission checks per second, with pluggable SPI for caches, health checks, and Watch stream listeners.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.authxkit/authx-spicedb-sdk.svg)](https://central.sonatype.com/artifact/io.github.authxkit/authx-spicedb-sdk)

## Features

- **Direct gRPC**: Bypasses middleware. Connects to SpiceDB directly via gRPC. Supports DNS / static multi-address targets.
- **10K+ checks/sec**: Cache hit < 1µs, cache miss (single node) < 10ms.
- **Smart Caching**: Caffeine in-memory cache + Watch-based real-time invalidation. Per-resource-type TTL.
- **Watch invalidation**: Low-level `ClientCall`-based gRPC client with proper `onHeaders`/`onMessage`/`onClose` lifecycle handling, cursor resume on reconnect, and automatic cursor-expiration recovery.
- **Pluggable SPI throughout**: `HealthProbe` / `DuplicateDetector` / `watchListenerExecutor` / `TelemetrySink` / `SdkInterceptor` are all user-injectable.
- **Per-resource-type policies**: Independent cache TTL, consistency, retry, circuit breaker, timeout per resource type.
- **Resilience4j**: CircuitBreaker + Retry + RateLimiter + Bulkhead — out of the box.
- **HdrHistogram metrics**: Lock-free p50/p99/p999 latency tracking with microsecond precision.
- **Request coalescing**: Concurrent identical requests are merged to reduce SpiceDB load.
- **Graceful degradation**: When Caffeine is missing from the classpath, the SDK falls back to a no-op cache instead of failing fast — never crashes due to a missing optional dependency.

## Quick Start

### Add Dependency

```groovy
// build.gradle
dependencies {
    implementation("io.github.authxkit:authx-spicedb-sdk:1.0.0")
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>io.github.authxkit</groupId>
    <artifactId>authx-spicedb-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Requires**: Java 21+

### Initialize the Client

```java
// Minimal config
AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("dns:///spicedb.prod:50051").presharedKey("my-key"))
    .build();

// Recommended: grouped config + cache + Watch invalidation
AuthxClient client = AuthxClient.builder()
    .connection(c -> c
        .target("dns:///spicedb.prod:50051")
        .presharedKey("my-preshared-key")
        .tls(true))
    .cache(c -> c
        .enabled(true)
        .maxSize(100_000)
        .watchInvalidation(true))
    .features(f -> f
        .shutdownHook(true)
        .telemetry(true))
    .build();
```

### Permission Check

```java
// Concise
boolean canView = client.check("document", "doc-1", "view", "alice");

// Chained (reuse the factory)
ResourceFactory doc = client.on("document");
boolean canEdit = doc.check("doc-1", "edit", "alice");

// Multiple permissions in one call
Map<String, Boolean> perms = client.checkAll("document", "doc-1", "alice", "view", "edit", "delete");
```

### Grant / Revoke

```java
client.grant("document", "doc-1", "editor", "bob");
client.grantToSubjects("document", "doc-1", "viewer", "department:eng#member", "user:*");
client.revoke("document", "doc-1", "editor", "bob");
client.revokeAll("document", "doc-1", "bob");
```

### Write completion listeners (grant / revoke)

Typed-chain grant / revoke terminals return a completion handle that lets you
attach one or more completion listeners at the end of the chain. **The write
itself remains synchronous**; only the listener's execution mode is configurable:

```java
// Sync listener — callback runs on the caller's thread before the call returns
client.on(Document.TYPE).select("doc-1")
    .grant(Document.Rel.EDITOR)
    .toUser("bob")
    .listener(r -> log.info("granted, zedToken={}", r.zedToken()));

// Async listener — dispatched to the supplied executor and returns immediately
client.on(Document.TYPE).select("doc-1")
    .grant(Document.Rel.EDITOR)
    .toUser("bob")
    .listenerAsync(r -> audit.write(r), auditExecutor);

// Multiple listeners can be chained
client.on(Document.TYPE).select("doc-1")
    .grant(Document.Rel.EDITOR)
    .toUser("bob")
    .listener(r -> localLog(r))
    .listenerAsync(r -> remoteAudit(r), auditExecutor);

// Statement form still works — existing callers don't need to change
client.on(Document.TYPE).select("doc-1")
    .grant(Document.Rel.EDITOR)
    .toUser("bob");
```

**Semantics**:
- A write failure (any `AuthxException` subclass) is thrown from the terminal
  method before any listener can be registered, so listeners only observe
  successful writes.
- An exception thrown inside an async listener callback is caught and logged
  at WARNING (logger name `com.authx.sdk.action.GrantCompletion` /
  `RevokeCompletion`). It does NOT reach the caller, does NOT affect the
  write outcome, and does NOT cancel other already-dispatched async listeners.
- Listeners fire in chain registration order for the sync variant; for the
  async variant submission order matches chain order but actual execution
  order is governed by the supplied executor.
- When a typed chain spans multiple internal RPCs (e.g. `select("d1","d2")
  .grant(R1,R2).toUser("a","b")` triggers 4 RPCs), `result()` returns a
  single aggregated `GrantResult` whose `zedToken` is the last internal
  write's token and whose `count` is the sum across all internal writes.

### Close the Client

```java
client.close();  // implements AutoCloseable
// or use .features(f -> f.shutdownHook(true)) to register a JVM shutdown hook
```

---

## Advanced Usage

### Cache Configuration

```java
AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("localhost:50051").presharedKey("my-key"))
    .cache(c -> c
        .enabled(true)            // Enable L1 in-memory cache
        .maxSize(50_000)          // Max cache entries
        .watchInvalidation(true)) // Subscribe to Watch stream for live invalidation
    .build();

// Manual invalidation
client.cache().invalidateResource("document", "doc-1");
```

### Per-Resource-Type Policies

```java
PolicyRegistry policies = PolicyRegistry.builder()
    .defaultPolicy(ResourcePolicy.builder()
        .cache(CachePolicy.of(Duration.ofMinutes(5)))
        .retry(RetryPolicy.defaults())
        .build())
    .forResource("document", ResourcePolicy.builder()
        .cache(CachePolicy.of(Duration.ofSeconds(30)))   // 30s for documents
        .circuitBreaker(CircuitBreakerPolicy.defaults())
        .build())
    .forResource("folder", ResourcePolicy.builder()
        .cache(CachePolicy.of(Duration.ofMinutes(10)))   // 10min for folders
        .build())
    .build();

AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("localhost:50051").presharedKey("my-key"))
    .extend(e -> e.policies(policies))
    .build();
```

### Watch Stream Listener

```java
// Requires cache + watchInvalidation enabled
client.onRelationshipChange(change -> {
    System.out.println(change.resourceType() + ":" + change.resourceId()
        + " " + change.operation() + " " + change.relation()
        + " -> " + change.subjectType() + ":" + change.subjectId());

    // Audit: transaction metadata is propagated from the writer
    String actor = change.transactionMetadata().get("actor");
    String traceId = change.transactionMetadata().get("trace_id");

    // Temporal grants: caveat name and expiration are exposed
    String caveat = change.caveatName();          // null if none
    Instant expiresAt = change.expiresAt();       // null if none
});
```

---

## Pluggable SPI (Recommended for Production)

All SPI components are injected via `SdkComponents`:

```java
AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("localhost:50051").presharedKey("my-key"))
    .cache(c -> c.enabled(true).watchInvalidation(true))
    .extend(e -> e.components(SdkComponents.builder()
        .healthProbe(myProbe)
        .watchDuplicateDetector(myDedup)
        .watchListenerExecutor(myExecutor)
        .build()))
    .build();
```

### `HealthProbe` — Health Checks

A composite probe (`ChannelStateHealthProbe` + `SchemaReadHealthProbe`) is wired by default — no configuration needed:

```java
HealthResult result = client.health();
result.isHealthy();         // overall status
result.spicedbLatencyMs();  // end-to-end latency (ms)
result.probe();             // full ProbeResult tree (per-sub-probe details)
```

**Custom probes**:

```java
// 1. Compose built-ins
SdkComponents.builder()
    .healthProbe(HealthProbe.all(
        new ChannelStateHealthProbe(channel),       // sub-microsecond, zero RPC
        new SchemaReadHealthProbe(channel, key,     // end-to-end RPC
            Duration.ofMillis(500))))               // custom timeout
    .build();

// 2. Write your own (just implement HealthProbe)
HealthProbe customProbe = () -> {
    long start = System.nanoTime();
    boolean ok = myExternalCheck();
    return HealthProbe.ProbeResult.up("custom", Duration.ofNanos(System.nanoTime()-start), "ok");
};

// 3. Force maintenance mode
SdkComponents.builder()
    .healthProbe(HealthProbe.down("maintenance window 02:00-04:00 UTC"))
    .build();
```

`SchemaReadHealthProbe` defaults to a 500ms timeout (matched to typical K8s liveness probes) and treats `NOT_FOUND` as healthy ("SpiceDB is up but no schema written yet" is not a failure).

### `DuplicateDetector` — Watch Replay Deduplication

SpiceDB's Watch stream may **replay** events around the cursor boundary on reconnect. By default the SDK does not deduplicate (backwards-compatible). If your listener has side effects that can't tolerate duplicates, enable LRU dedup:

```java
SdkComponents.builder()
    .watchDuplicateDetector(
        DuplicateDetector.lru(10_000, Duration.ofMinutes(5)))  // requires Caffeine
    .build();
```

The dedup key is `zedToken` (SpiceDB's monotonic transaction marker), which is naturally unique.

**Important**: Dedup gates **listeners only** — cache invalidation is NOT gated. Each pod must clear its own local cache regardless. This is by design.

If Caffeine is missing from the classpath, the SDK silently falls back to noop and logs a WARNING (same pattern as `CachedTransport`). It will not crash.

### `watchListenerExecutor` — Custom Listener Dispatch Executor

Default: single-threaded, 10 000-element bounded queue, drop-on-full (counted via the `droppedListenerEvents` metric).

Suitable for low-frequency listeners, strict ordering requirements, or when you don't want to introduce a new thread pool.

If your listeners are slow or you need parallel processing, supply your own executor:

```java
import java.util.concurrent.Executors;

ExecutorService myExecutor = Executors.newFixedThreadPool(8);
// or JDK 21+ virtual threads:
ExecutorService myExecutor = Executors.newVirtualThreadPerTaskExecutor();

SdkComponents.builder()
    .watchListenerExecutor(myExecutor)
    .build();

// Note: you own the lifecycle of the executor you provide.
// The SDK will NOT call shutdown() on it during close().
```

---

## Multi-Instance Deployment Guide

If you run multiple service instances (K8s deployment with replicas > 1), there are two important things to understand:

### Cache invalidation **should** run N times

Each pod has its own Caffeine cache. Each pod needs to clear its own. When N pods receive a Watch event, they each invalidate independently — this is correct, do not optimize it away.

### Listener side effects **may be wrong** if executed N times

If your listener performs side effects (audit logging, sending notifications, calling external APIs), N pods will execute the same event. Three solutions:

#### Solution 1: Make the destination idempotent (recommended)

`zedToken` is SpiceDB's globally unique transaction ID — all pods receiving the same event see the same zedToken. Use this as a natural idempotency key:

```java
// Elasticsearch: zedToken as document ID
client.onRelationshipChange(change -> {
    es.put("audit-index", change.zedToken(), toJson(change));
});

// PostgreSQL: UNIQUE constraint + ON CONFLICT DO NOTHING
jdbc.update(
    "INSERT INTO audit(zed_token, resource, action, actor) " +
    "VALUES (?, ?, ?, ?) ON CONFLICT (zed_token) DO NOTHING",
    change.zedToken(), ..., change.transactionMetadata().get("actor"));

// Kafka: enable.idempotence=true + message key = zedToken
kafka.send("events", change.zedToken(), serialize(change));
```

3 pods each issue a write; the destination's deduplication mechanism guarantees one record at the end.

#### Solution 2: Route via message bus + consumer group

A more decoupled approach: app instances only publish Watch events to Kafka, and an independent consumer service uses a consumer group to guarantee exactly-once processing:

```
App pods (N) → Watch → publish to Kafka → independent consumer → real side effect
```

Kafka consumer groups naturally guarantee each message is consumed by exactly one consumer in the group.

#### Solution 3: zedToken LRU dedup (single-pod only)

```java
SdkComponents.builder()
    .watchDuplicateDetector(DuplicateDetector.lru(10_000, Duration.ofMinutes(5)))
    .build();
```

**Note**: This only deduplicates **within a single pod** (e.g. when the Watch stream replays events on reconnect). It does **NOT** deduplicate across pods. For cross-pod deduplication, use Solution 1 or 2.

---

## Watch Stream Observability

```java
// Current Watch connection state
client.cache().watchInvalidator().state();
//   NOT_STARTED / CONNECTING / CONNECTED / RECONNECTING / STOPPED

// Cumulative reconnect count (for monitoring abnormal disconnects)
client.metrics().snapshot().watchReconnects();

// Number of listener events dropped due to queue saturation
client.cache().watchInvalidator().droppedListenerEvents();
```

The `CONNECTED` state fires on **either** of:
1. SpiceDB explicitly responding with HTTP/2 HEADERS
2. The underlying gRPC channel being in `READY` state

So even on a pure-read system (where SpiceDB pushes nothing), as long as the channel is healthy, state will show CONNECTED.

### Watch Auto-Recovery

The SDK handles three failure modes internally:

| Scenario | Handling |
|---|---|
| Transient network drop | Exponential backoff (1s → 2s → 4s → ... → 30s cap), cursor preserved for resume |
| `--grpc-max-conn-age` connection rotation | Automatic reconnect, fully transparent |
| **Cursor expired** (disconnect longer than SpiceDB's `--datastore-gc-window`) | Auto-detect → reset cursor → resubscribe from HEAD. **Events during this window are lost** (unrecoverable). |
| Permanent error (UNIMPLEMENTED / UNAUTHENTICATED / PERMISSION_DENIED) | Stop retrying, cache invalidation degrades to TTL-only |

---

## Configuration Reference

| Option | Default | Description |
|--------|---------|-------------|
| `target` | — | SpiceDB address, e.g. `dns:///spicedb.prod:50051` |
| `targets` | — | Multiple addresses (StaticNameResolver + round_robin) |
| `presharedKey` | — | Required, SpiceDB preshared key |
| `useTls` | `false` | Enable TLS |
| `loadBalancing` | `round_robin` | gRPC load balancing strategy |
| `keepAliveTime` | `30s` | gRPC keepalive interval |
| `requestTimeout` | `5s` | Single gRPC request timeout |
| `cacheEnabled` | `false` | Enable L1 in-memory cache |
| `cacheMaxSize` | `100000` | Max L1 cache entries |
| `watchInvalidation` | `false` | Subscribe to Watch stream for invalidation |
| `coalescingEnabled` | `true` | Merge concurrent duplicate requests |
| `useVirtualThreads` | `false` | Use Java 21 virtual threads |
| `registerShutdownHook` | `false` | Auto-call `close()` on JVM exit |
| `telemetryEnabled` | `false` | Enable OpenTelemetry metrics export |
| `defaultSubjectType` | `user` | Default subject type for shorthand API |

### `SdkComponents` SPI

| Field | Default | Description |
|---|---|---|
| `telemetrySink` | NOOP | Custom telemetry export (Kafka/OTLP/file) |
| `clock` | SYSTEM | Clock (for testing) |
| `tokenStore` | null | Cross-instance SESSION consistency zedtoken store (Redis, etc.) |
| `healthProbe` | `all(ChannelState, SchemaRead)` | Custom health probe |
| `watchDuplicateDetector` | `noop()` | Watch event deduplication (default: no dedup) |
| `watchListenerExecutor` | Default 1 thread + 10K queue | Custom listener dispatch executor |

---

## Core Dependencies

| Dependency | Version | Purpose |
|------|------|------|
| `com.authzed.api:authzed` | 1.5.4 | SpiceDB gRPC protocol |
| `io.grpc:grpc-netty-shaded` | 1.80.0 | gRPC transport (CVE-2025-55163 fixed) |
| `io.github.resilience4j:*` | 2.4.0 | CircuitBreaker / Retry / RateLimiter / Bulkhead |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | Latency percentile tracking |
| `io.opentelemetry:opentelemetry-api` | 1.40.0 | Observability API (no-op without an SDK) |
| `com.github.ben-manes.caffeine:caffeine` | 3.1.8 | L1 cache (**optional**, falls back to noop if missing) |

> **Requires**: Java 21+
>
> **Not affiliated with Authzed, Inc.** This is an independent Java SDK that depends on SpiceDB's official `authzed-api` protobuf definitions.
