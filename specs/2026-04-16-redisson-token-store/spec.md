# Redisson-backed `DistributedTokenStore`

## Background

The SDK already has SPI `com.authx.sdk.spi.DistributedTokenStore` (set/get) used by `TokenTracker` to share ZedTokens across SDK instances for cross-process SESSION consistency. Without an implementation, `AuthxClientBuilder` logs `"No DistributedTokenStore configured — SESSION consistency only works within a single JVM"` on every startup, and multi-JVM deployments silently fall back to single-JVM-only consistency.

The cluster benchmark (2026-04-08) confirmed the SPI is wired correctly but produced this warning on every cell, motivating shipping a first-party impl.

## Goal

Provide an opt-in Redisson-backed `DistributedTokenStore` so users running multi-JVM deployments can enable cross-process SESSION consistency without writing the glue code themselves.

## Requirements

- **req-1**: Live in a separate gradle subproject `sdk-redisson` so users who don't need Redis don't pull Redisson (~5 MB + 8 MB transitive). Main SDK depends on nothing in `sdk-redisson`.
- **req-2**: Public class `com.authx.sdk.redisson.RedissonTokenStore implements DistributedTokenStore`.
- **req-3**: Constructor takes `(RedissonClient client, Duration ttl, String keyPrefix)`. SDK does **not** own the `RedissonClient` lifecycle — caller creates and closes it.
- **req-4**: Use `RBucket<String>` with `org.redisson.client.codec.StringCodec.INSTANCE` to avoid Redisson's default Kryo5 codec (extra serialization overhead and historical CVE surface for arbitrary string payloads).
- **req-5**: Honor SPI contract — `set` MUST NOT throw on Redis failure; `get` MUST return `null` on miss or any error. Both wrap exceptions, log at WARN via `java.util.Logging` (matches main SDK's logging stack), and swallow.
- **req-6**: Storage key = `keyPrefix + key`. TTL applied via `RBucket.set(value, ttl.toMillis(), TimeUnit.MILLISECONDS)`.
- **req-7**: No background threads, no scheduled work, no event listeners — purely request-driven.
- **req-8**: Integration test with Testcontainers Redis covering: round-trip set/get, TTL expiry, Redis-down resilience (`set/get` don't throw when container stopped), key-prefix isolation between two stores sharing the same Redis.
- **req-9**: README in `sdk-redisson/` showing wiring example with `AuthxClient.builder().extend(...)` and recommended TTL guidance.

## Non-goals

- Auto-discovery of `RedissonClient` from Spring context. Spring Boot starter is out of scope; users wire it manually in their `@Configuration`.
- Cluster/Sentinel-specific code paths — Redisson handles that transparently via its own config; we just call `getBucket`.
- Compression, encryption, or per-tenant keyspace isolation. If a user needs those, they implement `DistributedTokenStore` directly.
- Metrics/tracing emission from the store. The caller's Redisson client already exposes Micrometer integration.

## Design

### Module layout

```
sdk-redisson/
  build.gradle                                    # standalone module, depends on :sdk
  README.md                                       # usage example
  src/main/java/com/authx/sdk/redisson/
    RedissonTokenStore.java                       # ~80 lines incl. javadoc
  src/test/java/com/authx/sdk/redisson/
    RedissonTokenStoreIT.java                     # Testcontainers-based
```

### Dependencies

`sdk-redisson/build.gradle`:
- `implementation(project(":"))` — for the SPI
- `implementation("org.redisson:redisson:3.27.x")` — current stable, Java 21 compatible
- `testImplementation("org.testcontainers:testcontainers")` + `:redis` (or generic image)
- `testImplementation("org.junit.jupiter:junit-jupiter")` + `org.assertj:assertj-core`

### Implementation sketch

```java
public final class RedissonTokenStore implements DistributedTokenStore {
    private static final Logger LOG = Logger.getLogger(RedissonTokenStore.class.getName());

    private final RedissonClient client;
    private final long ttlMs;
    private final String prefix;

    public RedissonTokenStore(RedissonClient client, Duration ttl, String keyPrefix) {
        this.client = Objects.requireNonNull(client, "client");
        this.ttlMs = ttl.toMillis();
        this.prefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        if (ttlMs <= 0) throw new IllegalArgumentException("ttl must be positive");
    }

    @Override public void set(String key, String token) {
        try {
            client.<String>getBucket(prefix + key, StringCodec.INSTANCE)
                  .set(token, ttlMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "RedissonTokenStore set failed for key " + key, e);
        }
    }

    @Override public @Nullable String get(String key) {
        try {
            return client.<String>getBucket(prefix + key, StringCodec.INSTANCE).get();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "RedissonTokenStore get failed for key " + key, e);
            return null;
        }
    }
}
```

### Wiring (in user code)

```java
RedissonClient redis = Redisson.create(redissonConfig);
DistributedTokenStore store = new RedissonTokenStore(
    redis, Duration.ofSeconds(60), "authx:token:");

AuthxClient client = AuthxClient.builder()
    .connection(c -> c.targets("...").presharedKey("..."))
    .extend(e -> e.components(SdkComponents.builder()
            .tokenStore(store)
            .build()))
    .build();
```

## Testing

Integration test `RedissonTokenStoreIT` (`*IT` so it can be excluded in fast suites):

- **t-1**: round-trip — `set("k","v")` then `get("k")` returns `"v"`
- **t-2**: TTL — set with ttl=1s, sleep 1.5s, `get` returns `null`
- **t-3**: prefix isolation — two stores with different prefixes don't see each other's keys
- **t-4**: Redis down — stop the container, `set` does not throw, `get` returns `null`
- **t-5**: missing key — fresh `get` returns `null` without error

## Settings.gradle update

Add `include("sdk-redisson")` to root `settings.gradle`. Update root `CLAUDE.md` "Project structure" section to mention the optional module.

## Risks

- **Redisson Netty version vs `grpc-netty-shaded`**: Redisson uses Netty 4.1.x; gRPC ships shaded Netty. They coexist (different classloaders), but if user app brings unshaded Netty there can be drift. Documented in README.
- **TTL semantics**: `RBucket.set(value, ttl)` resets TTL on every write. This matches our intent (refresh on each token observation).
- **Single Redisson instance shared across many `RedissonTokenStore` calls is the assumption**. Caller must manage that.

## Success criteria

- `./gradlew :sdk-redisson:build` passes
- Integration tests pass with Testcontainers Redis
- A user can replace 30 lines of glue code with 4 lines (one `import` + 3 lines wiring)
- Main SDK `compileJava` and `test` unaffected (no new dependency on Redisson)
