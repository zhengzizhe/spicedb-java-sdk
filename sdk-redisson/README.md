# sdk-redisson

Redisson-backed `DistributedTokenStore` for cross-JVM SESSION consistency in [AuthX SDK](../README.md).

## Why

The main SDK ships the `DistributedTokenStore` SPI but no implementation. Without one, ZedTokens are JVM-local and SESSION consistency breaks across SDK instances. This module wires Redisson into that SPI in ~80 lines.

## Add the dependency

```gradle
dependencies {
    implementation("io.github.authxkit:authx-spicedb-sdk:<version>")
    implementation("io.github.authxkit:authx-spicedb-sdk-redisson:<version>")
}
```

(Module is opt-in — main SDK does not depend on Redisson.)

## Wire it

```java
RedissonClient redis = Redisson.create(redissonConfig);  // you own the lifecycle

DistributedTokenStore store = new RedissonTokenStore(
        redis,
        Duration.ofSeconds(60),    // TTL — match your read-after-write SLA
        "authx:token:");           // key prefix — pick something app-unique

AuthxClient client = AuthxClient.builder()
        .connection(c -> c.targets("spicedb-1:50051", "spicedb-2:50051", "spicedb-3:50051")
                          .presharedKey("..."))
        .extend(e -> e.components(SdkComponents.builder()
                .tokenStore(store)
                .build()))
        .build();
```

## Behavior

- Set/get failures are logged at WARNING via `System.Logger` and swallowed (per SPI contract).
- `get` returns `null` on miss or error.
- TTL is reset on every `set`.
- Uses Redisson's `StringCodec` — no Kryo serialization overhead, no historical CVE surface.

## TTL guidance

Pick a TTL ≥ your worst-case read-after-write window. 60 s is a safe default for most cases; lower it (10–30 s) for chatty workloads, raise it (5 min) if writes are infrequent and you want to keep tokens warm across restarts.

## Redis client lifecycle

Caller owns the `RedissonClient`. Don't create one per `RedissonTokenStore`; reuse the same client app-wide.

## Spec

See [`specs/2026-04-16-redisson-token-store/spec.md`](../specs/2026-04-16-redisson-token-store/spec.md).
