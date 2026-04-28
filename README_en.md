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
>
> **Breaking Change — 2026-04-22**: `TypedHandle.grant(R)` / `.revoke(R)`
> now return `WriteFlow`. Chains must end with `.commit()` or the write
> is silently dropped. See
> [ADR 2026-04-22](docs/adr/2026-04-22-grant-revoke-flow-api.md) and
> [CHANGELOG.md](CHANGELOG.md).

## Features

- **Direct gRPC**: Bypasses middleware. Connects to SpiceDB directly via gRPC. Supports DNS / static multi-address targets.
- **Pluggable SPI**: `HealthProbe` / `TelemetrySink` / `SdkInterceptor` / `DistributedTokenStore` / `PolicyCustomizer` are all user-injectable.
- **Per-resource-type policies**: Independent consistency, retry, circuit breaker, timeout per resource type.
- **Resilience4j**: CircuitBreaker + Retry + RateLimiter + Bulkhead — out of the box.
- **HdrHistogram metrics**: Lock-free p50/p99/p999 latency tracking with microsecond precision.
- **Request coalescing**: Concurrent identical requests are merged to reduce SpiceDB load.
- **Cross-JVM SESSION consistency SPI**: `DistributedTokenStore` extension point; applications provide and operate their own token storage.

## Quick Start

### Add Dependency

```groovy
// build.gradle
dependencies {
    implementation("io.github.authxkit:authx-spicedb-sdk:2.0.1")
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>io.github.authxkit</groupId>
    <artifactId>authx-spicedb-sdk</artifactId>
    <version>2.0.1</version>
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

Two parallel fluent APIs; pick one. Subjects must be canonical `type:id`
form (no default subject type).

```java
// Untyped (string-driven)
ResourceHandle doc = client.on("document").resource("doc-1");

boolean canView = doc.check("view").by("user:alice").hasPermission();

// Multiple permissions in one bulk RPC → PermissionSet
PermissionSet perms = doc.checkAll("view", "edit", "delete").by("user:alice");
perms.can("edit");   // boolean
perms.allowed();     // Set<String>

// Typed (using codegen enums — recommended)
boolean canView = client.on(Document).select("doc-1")
    .check(Document.Perm.VIEW).by(User, "alice");
```

### Grant / Revoke

**Untyped path**: `.to(...)` / `.from(...)` are terminal — the write
happens immediately.

```java
ResourceHandle doc = client.on("document").resource("doc-1");

// grant — writes immediately, returns GrantResult (zedToken + count)
doc.grant("editor").to("user:bob");
doc.grant("viewer").to("group:engineering#member", "user:*");

// revoke — writes immediately
doc.revoke("editor").from("user:bob");

// revokeAll — filter-based delete of all relationships for this subject
doc.revokeAll().from("user:bob");
doc.revokeAll("editor", "viewer").from("user:bob");  // scoped to relations
```

**Typed path**: `TypedHandle.grant(R)` / `.revoke(R)` return a
`WriteFlow`. Accumulate `.to(...)` / `.from(...)` calls and commit them
atomically in one `WriteRelationships` RPC via `.commit()`.

```java
// Single relation, multiple subjects
client.on(Document).select("doc-1")
    .grant(Document.Rel.VIEWER)
    .to(User, "alice")
    .to(User, "bob")
    .to(Group, "eng", Group.Rel.MEMBER)
    .commit();

// Mixed grant + revoke — one atomic commit, no intermediate visible state
client.on(Document).select("doc-1")
    .revoke(Document.Rel.EDITOR).from(User, "alice")
    .grant(Document.Rel.VIEWER).to(User, "alice")
    .commit();

// Async commit
CompletableFuture<WriteCompletion> f = client.on(Document).select("doc-1")
    .grant(Document.Rel.VIEWER).to(User, "alice")
    .commitAsync();
```

`commit()` returns a `WriteCompletion`; attach `.listener(...)` /
`.listenerAsync(..., executor)` for post-write side effects such as audit
logging. The write itself has already happened by the time the listener
runs.

**`.commit()` is mandatory on the typed path** — forgetting it neither
throws nor writes (see
[ADR 2026-04-22](docs/adr/2026-04-22-grant-revoke-flow-api.md)).
Guarded at the code-review layer today; ErrorProne wiring planned.

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
        .tokenStore(myTokenStore)
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

If you run multiple service instances (K8s deployment with replicas > 1): SESSION consistency requires a shared `DistributedTokenStore`. Without it, SESSION only works within a single JVM (startup log warns). The SDK only provides the SPI; it no longer ships or publishes a concrete token-store implementation. For multi-JVM deployments, provide and operate your own Redis, database, or other shared-storage implementation.

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
| `tokenStore` | null | User-provided zedToken store for cross-instance SESSION consistency |
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
| `org.slf4j:slf4j-api` | 2.0.13 | **Optional** `compileOnly` — when present, the SDK auto-pushes 15 `authx.*` MDC keys; absent, the bridge is a silent no-op |

### Logging & Traceability

SDK logs go through `java.lang.System.Logger` (JDK-built-in, zero-dependency, JUL by default). For production, route to SLF4J:

```gradle
dependencies {
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.slf4j:jul-to-slf4j:2.0.13")   // System.Logger → SLF4J
}
```

When SLF4J is on the classpath, `Slf4jMdcBridge` pushes 15 `authx.*` MDC keys at each RPC entry so your Logback pattern / JSON encoder picks them up. When an OTel span is active, every message is automatically prefixed with `[trace=<16hex>] `. `WARN` and above also carry a ` [type=... res=... perm|rel=... subj=...]` suffix for readers without SLF4J.

See [`docs/logging-guide.md`](docs/logging-guide.md) for the full configuration guide, level semantics, and the complete MDC field reference.

> **Requires**: Java 21+
>
> **Not affiliated with Authzed, Inc.** This is an independent Java SDK that depends on SpiceDB's official `authzed-api` protobuf definitions.
