[中文](README.md)

# AuthX SpiceDB SDK

A high-performance Java client that connects directly to SpiceDB. No platform dependencies, with pluggable SPI for health checks, telemetry, and cross-JVM SESSION consistency.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.authxkit/authx-spicedb-sdk.svg)](https://central.sonatype.com/artifact/io.github.authxkit/authx-spicedb-sdk)

> **Breaking Change — 2026-04-18**: The L1 in-process cache and Watch
> stream infrastructure have been removed entirely. See
> [ADR 2026-04-18](docs/adr/2026-04-18-remove-l1-cache.md) for rationale
> (inheritance-chain invalidation correctness limitation). Upgrading
> code must strip all `.cache(...)`, `CacheHandle`, `onRelationshipChange`,
> and related Watch / `DuplicateDetector` / `QueueFullPolicy` usages.

## Features

- **Direct gRPC**: Bypasses middleware. Connects to SpiceDB directly via gRPC. Supports DNS / static multi-address targets.
- **Pluggable SPI**: `HealthProbe` / `TelemetrySink` / `SdkInterceptor` / `DistributedTokenStore` / `PolicyCustomizer` are all user-injectable.
- **Per-resource-type policies**: Independent consistency, retry, circuit breaker, timeout per resource type.
- **Resilience4j**: CircuitBreaker + Retry + RateLimiter + Bulkhead — out of the box.
- **HdrHistogram metrics**: Lock-free p50/p99/p999 latency tracking with microsecond precision.
- **Request coalescing**: Concurrent identical requests are merged to reduce SpiceDB load.
- **Cross-JVM SESSION consistency**: optional `sdk-redisson` module for shared zedToken store.

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

// Recommended: grouped config
AuthxClient client = AuthxClient.builder()
    .connection(c -> c
        .target("dns:///spicedb.prod:50051")
        .presharedKey("my-preshared-key")
        .tls(true))
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

### Per-Resource-Type Policies

```java
PolicyRegistry policies = PolicyRegistry.builder()
    .defaultPolicy(ResourcePolicy.builder()
        .retry(RetryPolicy.defaults())
        .readConsistency(ReadConsistency.session())
        .build())
    .forResource("document", ResourcePolicy.builder()
        .circuitBreaker(CircuitBreakerPolicy.defaults())
        .readConsistency(ReadConsistency.strong())
        .build())
    .forResource("folder", ResourcePolicy.builder()
        .retry(RetryPolicy.disabled())
        .build())
    .build();

AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("localhost:50051").presharedKey("my-key"))
    .extend(e -> e.policies(policies))
    .build();
```

> The client-side decision cache was removed on 2026-04-18 (see
> [ADR](docs/adr/2026-04-18-remove-l1-cache.md)). For low-latency reads
> use `Consistency.minimizeLatency()` to hit SpiceDB's server-side
> schema-aware dispatch cache.

---

## Pluggable SPI (Recommended for Production)

All SPI components are injected via `SdkComponents`:

```java
AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("localhost:50051").presharedKey("my-key"))
    .extend(e -> e.components(SdkComponents.builder()
        .healthProbe(myProbe)
        .tokenStore(myRedisTokenStore)
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

---

## Multi-Instance Deployment Guide

If you run multiple service instances (K8s deployment with replicas > 1): SESSION consistency requires a shared `DistributedTokenStore`. Without it, SESSION only works within a single JVM (startup log warns). For multi-JVM deployments use the [`sdk-redisson`](sdk-redisson/README.md) module.

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
