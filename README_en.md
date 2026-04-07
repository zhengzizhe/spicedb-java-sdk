[中文](README.md)

# AuthCSES Java SDK

A high-performance permission-checking client that connects directly to SpiceDB, with no platform dependencies, supporting tens of thousands of permission checks per second.

## Features

- **Direct gRPC Connection**: Bypasses middleware, connects directly to SpiceDB via gRPC, supports DNS / static multi-address
- **Tens of Thousands of Checks per Second**: Cache hit < 1us, cache miss (single node) < 10ms
- **Two-Level Cache**: L1 in-memory cache (Caffeine) + optional L2 distributed cache, `PolicyAwareCheckCache` with independent TTL per resource type
- **Watch-Based Real-Time Invalidation**: Subscribes to SpiceDB Watch stream, automatically evicts cache entries on relationship changes
- **Per-Resource-Type Policies**: Each resource type can have independent cache TTL, consistency, retry, circuit breaker, and timeout configuration
- **Resilience4j Circuit Breaker & Retry**: CircuitBreaker + Retry + RateLimiter + Bulkhead, ready out of the box
- **HdrHistogram Metrics**: Lock-free latency percentile tracking (p50/p99/p999)
- **Request Coalescing**: Automatically merges concurrent identical requests to reduce SpiceDB load

## Quick Start

### Add Dependency

```groovy
// build.gradle
dependencies {
    implementation("com.authcses:authcses-sdk:1.0.0-SNAPSHOT")
}
```

### Initialize the Client

```java
// Minimal configuration
AuthCsesClient client = AuthCsesClient.builder()
    .target("dns:///spicedb.prod:50051")
    .presharedKey("my-preshared-key")
    .build();

// Recommended: grouped configuration + cache + Watch invalidation
AuthCsesClient client = AuthCsesClient.builder()
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

### Permission Checks

```java
// Simple form
boolean canView = client.check("document", "doc-1", "view", "alice");

// Chained form (recommended for reuse via factory)
ResourceFactory doc = client.on("document");
boolean canEdit = doc.check("doc-1", "edit", "alice");

// Check multiple permissions at once
Map<String, Boolean> perms = client.checkAll("document", "doc-1", "alice", "view", "edit", "delete");
```

### Grant / Revoke

```java
// Grant
client.grant("document", "doc-1", "editor", "bob");

// Grant to subject references (group members, wildcards, etc.)
client.grantToSubjects("document", "doc-1", "viewer", "department:eng#member", "user:*");

// Revoke
client.revoke("document", "doc-1", "editor", "bob");

// Revoke all relationships for a user on a resource
client.revokeAll("document", "doc-1", "bob");
```

### Shutting Down the Client

```java
// Implements AutoCloseable, recommend try-with-resources or explicit close
client.close();

// Spring Bean scenario: registerShutdownHook(true) auto-closes on JVM exit
```

---

## Advanced Usage

### Cache Configuration

```java
AuthCsesClient client = AuthCsesClient.builder()
    .target("localhost:50051")
    .presharedKey("my-key")
    .cache(c -> c
        .enabled(true)          // Enable L1 in-memory cache
        .maxSize(50_000)        // Maximum number of cache entries
        .watchInvalidation(true)) // Subscribe to Watch stream, auto-evict on changes
    .build();

// Manual invalidation
client.cache().invalidateResource("document", "doc-1");
```

### Batch Checks

```java
// Batch check for the same resource type (single gRPC call)
Map<String, Boolean> results = client.on("document")
    .checkAll("doc-1", "alice", "view", "edit", "delete");

// Cross-resource-type batch (CrossResourceBatchBuilder)
var batch = client.batch();
// Add operations via respective ResourceHandles, then execute
```

### Lookup Queries

```java
LookupQuery lookup = client.lookup("document");

// Find all users with view permission
List<String> subjects = lookup.subjects("doc-1", "view").list();

// Find all documents where alice has view permission
List<String> resources = lookup.resources("view", "alice").list();
```

### Layered Policies (Per-Resource-Type)

```java
PolicyRegistry policies = PolicyRegistry.builder()
    .defaultPolicy(ResourcePolicy.builder()
        .cacheTtl(Duration.ofMinutes(5))
        .retryMaxAttempts(3)
        .build())
    .forResource("document", ResourcePolicy.builder()
        .cacheTtl(Duration.ofSeconds(30))  // Document permissions cached for 30s
        .circuitBreakerEnabled(true)
        .build())
    .forResource("folder", ResourcePolicy.builder()
        .cacheTtl(Duration.ofMinutes(10))  // Folder permissions cached longer
        .build())
    .build();

AuthCsesClient client = AuthCsesClient.builder()
    .target("localhost:50051")
    .presharedKey("my-key")
    .policies(policies)
    .build();
```

### Resilience4j Interceptor (Rate Limiting + Bulkhead)

```java
AuthCsesClient client = AuthCsesClient.builder()
    .target("localhost:50051")
    .presharedKey("my-key")
    .extend(e -> e
        .addInterceptor(new Resilience4jInterceptor(
            RateLimiterConfig.custom()
                .limitForPeriod(5000)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .build(),
            BulkheadConfig.custom()
                .maxConcurrentCalls(200)
                .build())))
    .build();
```

### Watch Real-Time Change Listener

```java
// Requires watchInvalidation(true) to be enabled first
client.onRelationshipChange(change -> {
    System.out.println("Relationship changed: " + change.resourceType()
        + "/" + change.resourceId()
        + " " + change.operation());
});
```

---

## Configuration Reference

| Option | Default | Description |
|--------|---------|-------------|
| `target` | -- | SpiceDB address, e.g. `dns:///spicedb.prod:50051` |
| `targets` | -- | Multi-address list (StaticNameResolver + round_robin) |
| `presharedKey` | -- | Required. SpiceDB preshared key |
| `useTls` | `false` | Whether to enable TLS |
| `loadBalancing` | `round_robin` | gRPC load balancing policy |
| `keepAliveTime` | `30s` | Keepalive probe interval for detecting dead connections |
| `requestTimeout` | `5s` | Timeout for a single gRPC request |
| `cacheEnabled` | `false` | Whether to enable local in-memory cache |
| `cacheMaxSize` | `100000` | Maximum number of L1 cache entries |
| `watchInvalidation` | `false` | Whether to invalidate cache in real-time via Watch stream |
| `coalescingEnabled` | `true` | Whether to merge concurrent duplicate requests |
| `useVirtualThreads` | `false` | Whether to use Java 21 virtual threads (async operations + scheduler) |
| `registerShutdownHook` | `false` | Automatically call `close()` on JVM exit |
| `telemetryEnabled` | `false` | Whether to enable OpenTelemetry metrics reporting |
| `defaultSubjectType` | `user` | Default subject type (for shorthand API) |
| `policies` | Default policy | `PolicyRegistry`, supports per-resource-type overrides |

---

## Core Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `com.authzed.api:authzed` | 1.5.4 | SpiceDB gRPC protocol |
| `io.grpc:grpc-netty-shaded` | 1.72.0 | gRPC transport layer |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.0 | JSON serialization |
| `io.github.resilience4j:resilience4j-circuitbreaker` | 2.4.0 | Circuit breaker |
| `io.github.resilience4j:resilience4j-retry` | 2.4.0 | Retry |
| `io.github.resilience4j:resilience4j-ratelimiter` | 2.4.0 | Rate limiter |
| `io.github.resilience4j:resilience4j-bulkhead` | 2.4.0 | Bulkhead |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | Latency percentile tracking |
| `io.opentelemetry:opentelemetry-api` | 1.40.0 | Observability API (no-op when SDK is absent) |
| `com.github.ben-manes.caffeine:caffeine` | 3.1.8 | L1 cache (optional, compile-time dependency) |

> **Requirement**: Java 21+
