# Remove L1 Caffeine Cache + Watch Infrastructure

**Date**: 2026-04-18
**Status**: Draft
**Scope**: SDK-wide removal of client-side decision caching
**Companion ADR**: [docs/adr/2026-04-18-remove-l1-cache.md](../../docs/adr/2026-04-18-remove-l1-cache.md)

## Background

On 2026-04-08 the team removed the L2 Redis cache
([ADR](../../docs/adr/2026-04-08-remove-redis-l2-cache.md)), keeping only
the L1 Caffeine in-process cache plus Watch-based invalidation for
cross-instance coherence. Two weeks of operation surfaced a correctness
limitation that is fundamental to key-based cache invalidation:

**Inheritance-chain invalidation**. When a SpiceDB schema defines
`permission view = parent->editor` and `folder:f-1#editor = user:alice`
changes, the Watch event only carries `folder:f-1` as the changed
resource. `WatchCacheInvalidator` invalidates index `"folder:f-1"`, but
cached `CheckKey(document:d-1, view, alice)` entries — which logically
depend on the folder's editor relation — stay intact. The SDK returns
stale `HAS_PERMISSION` for up to the TTL (default 5s) plus the SpiceDB
dispatch-cache quantization window (~5s), totaling ~10s of
eventual-consistency drift on inherited permissions.

The team judged this drift unacceptable and that the workarounds
(subject-keyed secondary index, schema-aware dependency graph, forced
`FullyConsistent` consistency) each carry their own over-invalidation,
memory, or latency penalties that do not cleanly resolve the issue.

Industry alignment: OpenFGA, Cerbos, and the SpiceDB documentation
itself all recommend against caching authorization decisions on the
client. SpiceDB's server-side dispatch cache (schema-aware,
distributed across the SpiceDB cluster via consistent hashing) is the
correct layer for decision-level caching; the SDK should query through
to it and rely on the server-side cache for deduplication.

This spec removes the entire L1 caching subsystem — including the Watch
stream infrastructure that existed solely to invalidate it — and
collapses the transport chain accordingly.

## Goals

- Delete all client-side permission-decision caching code.
- Delete the Watch stream subsystem (it exists only to invalidate the
  cache we are removing).
- Simplify the transport chain from seven layers to six (drop
  `CachedTransport`).
- Preserve functionality that does not depend on decision caching:
  `CoalescingTransport` (in-flight dedup), `DistributedTokenStore`
  (cross-JVM SESSION consistency), resilience, metrics, telemetry,
  lifecycle, exception handling, typed chain, write-completion listeners.
- Ship as a single coherent breaking change; no soft-deprecation path.

## Non-goals

- **No partial retention.** SchemaCache is being removed alongside
  decision cache — the team evaluated it explicitly and judged the
  write-time validation benefit (marginal; SpiceDB validates server-side
  anyway) not worth the residual complexity.
- **No replacement cache.** The SDK will have no caching layer after
  this change. Users who need low-latency reads should rely on SpiceDB's
  dispatch cache and, if necessary, increase their SpiceDB cluster size.
- **No changes to resilience, coalescing, or consistency protocols.**
- **No public API beyond `.cache(...)` / `.watchInvalidation(...)` / Watch
  subscriptions is removed.** The typed chain, check/grant/revoke APIs,
  event bus, and SPIs unrelated to caching stay.

## Requirements

Each requirement has an ID (`req-N`) for traceability in tasks.md.

### Decision-cache removal

**req-1** Delete package `com.authx.sdk.cache` in full. No surviving
files, no package-info. Affected classes: `Cache`, `CaffeineCache`,
`CacheStats`, `IndexedCache`, `NoopCache`, `SchemaCache`.

**req-2** Delete `com.authx.sdk.CacheHandle`.

**req-3** Delete `com.authx.sdk.internal.SdkCaching`.

**req-4** Remove the `caching` parameter from `AuthxClient`'s constructor
and delete all references to the caching aggregate.

**req-5** Remove `AuthxClient#cache()` and any API that returns a
`CacheHandle`.

### Watch-infrastructure removal

**req-6** Delete package `com.authx.sdk.watch` in full:
`WatchDispatcher`, `WatchStrategy`.

**req-7** Delete package `com.authx.sdk.dedup` in full.

**req-8** Delete `transport/WatchCacheInvalidator.java` and its
internal `WatchStreamSession` class.

**req-9** Delete `transport/WatchConnectionState.java`.

**req-10** Delete `transport/CachedTransport.java`.

**req-11** Delete `transport/SchemaLoader.java`.

**req-12** Remove `AuthxClient#onRelationshipChange` and
`offRelationshipChange` (their only source of events was
`WatchCacheInvalidator`).

### SPI removal (Watch-specific only)

**req-13** Delete `spi/DuplicateDetector.java`,
`spi/DroppedListenerEvent.java`, `spi/QueueFullPolicy.java`.

**req-14** Remove the following fields from `SdkComponents` (and their
builder methods): `watchDuplicateDetector`, `watchListenerExecutor`,
`watchListenerDropHandler`.

### Event bus cleanup

**req-15** Remove Watch-related and cache-related event types from
`event/SdkTypedEvent`: `CacheHit`, `CacheMiss`, `CacheEviction`,
`WatchConnected`, `WatchDisconnected`, `WatchReconnected`,
`WatchStreamStale`, `WatchCursorExpired`, `ListenerDropped` (if
present). Remove all publishers of these events from the transport
chain.

### Metrics cleanup

**req-16** Remove cache-related counters from `SdkMetrics`:
`cacheHits`, `cacheMisses`, `cacheEvictions`, `cacheSize`,
`watchReconnects`. Corresponding `Snapshot` fields and getters
removed. `SdkMetrics` retains all non-cache metrics unchanged (request
latency, error count, overflow count, circuit-breaker state, etc.).

### Policy cleanup

**req-17** Remove `policy/CachePolicy.java` if it exists as a separate
file. Remove `cache` field from `ResourcePolicy`. `PolicyRegistry`
retains retry / circuit-breaker / rate-limiter / bulkhead / consistency
policies.

### Typed-chain cleanup

**req-18** Remove schema-cache validation from `TypedGrantAction#write`
and `TypedRevokeAction#write` (the `if (schema != null)` block).
Invalid subjects now fail at the SpiceDB boundary with
`AuthxInvalidArgumentException` — same exception type, different
detection point.

### Builder API removal

**req-19** Delete `AuthxClientBuilder.CacheConfig` inner class in full.
Delete the `cache(...)` method on `AuthxClientBuilder`. Delete the
fields `cacheEnabled`, `cacheMaxSize`, `watchInvalidation`,
`listenerQueueOnFull` from `AuthxClientBuilder`.

**req-20** Delete the builder validation rules that reference cache:
the `SR:C7` "cache.watchInvalidation(true) requires cache.enabled(true)"
check and the "extend.watchStrategy(...) requires ..." check.

**req-21** Delete `ExtendConfig.addWatchStrategy(...)` and the
`watchStrategies` field.

### In-repo consumer updates

**req-22** `test-app/` — remove any `.cache(...)`, `WatchStrategy`,
`CacheHandle`, or Watch-event subscription usage. Tests that asserted on
cache stats deleted or rewritten.

**req-23** `cluster-test/` — remove cache-related assertions from
benchmark and test harness code. Reports that printed cache hit rates
print a "n/a — cache removed" line or omit the metric.

**req-24** `sdk-redisson/` — verified-no-op: this module only
implements `DistributedTokenStore` and does not depend on cache or
Watch. No changes expected.

### Preserved components (negative requirements — must NOT be deleted)

**req-25** `CoalescingTransport` is retained in the transport chain.
In-flight duplicate elimination (same-key concurrent check collapses
to one RPC) is valuable independent of caching.

**req-26** `DistributedTokenStore` SPI and the `sdk-redisson`
implementation are retained. Cross-JVM SESSION consistency is
independent of the cache.

**req-27** `TokenTracker` is retained. It tracks the latest zedToken
for session-consistency reads, independent of cache.

**req-28** The typed chain (`Typed*` classes), write-completion
listeners (`GrantCompletion` / `RevokeCompletion`), resilience stack,
exception hierarchy, lifecycle state machine, telemetry, non-cache
metrics, all non-cache SPIs (`SdkInterceptor`, `HealthProbe`,
`TelemetrySink`, `SdkClock`, `PolicyCustomizer`,
`AuthxClientCustomizer`, `DistributedTokenStore`, `AttributeKey`) are
retained.

### Documentation

**req-29** Write companion ADR at
`docs/adr/2026-04-18-remove-l1-cache.md`, structurally mirroring the
2026-04-08 L2 ADR: background, inheritance-chain-invalidation argument,
industry comparison, decision, what's preserved, what's removed,
performance impact, future directions.

**req-30** Update `CLAUDE.md` project structure: remove `cache/`,
`watch/`, `dedup/` packages from the listing.

**req-31** Update `README.md` and `README_en.md`:
- Delete "缓存配置" / "Cache Configuration" section
- Delete "Watch 实时变更监听" / "Watch real-time change listener" section
- Delete "可插拔 SPI" subsections covering `DuplicateDetector`,
  `watchListenerExecutor`, Watch-related SPIs
- Add a Changelog entry describing this removal and its breaking impact

**req-32** Update `llms.txt` if it references cache or Watch APIs.

### Breaking-change communication

**req-33** The Changelog entry in `README.md` explicitly lists the
breaking API removals so upgraders know what to strip from their builder
config.

## Acceptance Tests

Each requirement MUST have a verification step. Tests live under
`src/test/java/com/authx/sdk/` where applicable.

| Test / Check | Requirement(s) | What it asserts |
|---|---|---|
| `BuilderCacheMethodRemovedTest.cacheMethodNoLongerExists` | req-19 | Reflection-based check: `AuthxClientBuilder.class.getDeclaredMethods()` contains no method named `cache`. Prevents accidental re-introduction. |
| `BuilderCacheMethodRemovedTest.watchInvalidationFieldGone` | req-19, req-20 | Reflection: no field named `watchInvalidation` or `cacheEnabled` on `AuthxClientBuilder`. |
| `TransportChainTest.chainHasNoCachedTransport` | req-10 | Build a client, inspect transport chain (via package-private accessor or interceptor); assert no `CachedTransport` instance. |
| `TransportChainTest.chainHasCoalescingTransport` | req-25 | Same inspection; assert `CoalescingTransport` IS present. |
| `NoWatchStreamStartsTest.inMemoryClientStartsNoWatchThread` | req-6, req-8 | `AuthxClient.inMemory().build()`; enumerate live threads; assert no thread named `authx-sdk-watch` or similar exists. |
| `NoWatchStreamStartsTest.realClientStartsNoWatchThread` | same | With an InProcess gRPC channel, same assertion. |
| `TypedGrantActionTest.invalidSubjectPropagatesFromServer` | req-18 | Remove expected `IllegalArgumentException`-at-client-side test; add test that verifies SpiceDB's `AuthxInvalidArgumentException` is correctly mapped when schema rejects an invalid subject. |
| Existing full test suite `./gradlew :test` | req-28 | All non-cache tests still pass. Cache-specific tests deleted — `TEST-*.xml` should show zero references to `cache`, `Watch`, `DuplicateDetector`, `SchemaCache`, `CacheHandle`, `CachedTransport` class names. |
| `./gradlew :test-app:compileJava :cluster-test:compileJava :sdk-redisson:compileJava` | req-22, req-23, req-24 | All three downstream modules compile after consumer cleanup. |
| `./gradlew javadoc` | req-15, req-29, req-30, req-31 | Javadoc builds clean (no dangling `{@link}` references to removed types). |

## File-level change list (informative)

**Deleted** (whole files):

```
src/main/java/com/authx/sdk/cache/                       # entire package
src/main/java/com/authx/sdk/watch/                       # entire package
src/main/java/com/authx/sdk/dedup/                       # entire package
src/main/java/com/authx/sdk/CacheHandle.java
src/main/java/com/authx/sdk/internal/SdkCaching.java
src/main/java/com/authx/sdk/transport/WatchCacheInvalidator.java
src/main/java/com/authx/sdk/transport/WatchConnectionState.java
src/main/java/com/authx/sdk/transport/CachedTransport.java
src/main/java/com/authx/sdk/transport/SchemaLoader.java
src/main/java/com/authx/sdk/spi/DuplicateDetector.java
src/main/java/com/authx/sdk/spi/DroppedListenerEvent.java
src/main/java/com/authx/sdk/spi/QueueFullPolicy.java
src/main/java/com/authx/sdk/policy/CachePolicy.java      # if present
src/test/java/com/authx/sdk/cache/**                     # all cache tests
src/test/java/com/authx/sdk/transport/CachedTransportTest.java
src/test/java/com/authx/sdk/transport/WatchCacheInvalidator*Test.java
src/test/java/com/authx/sdk/transport/WatchConnectionStateTest.java
src/test/java/com/authx/sdk/transport/WatchListenerQueuePolicyTest.java
src/test/java/com/authx/sdk/transport/WatchCacheInvalidatorOrderingTest.java
```

**Modified** (partial edits):

```
src/main/java/com/authx/sdk/AuthxClient.java
src/main/java/com/authx/sdk/AuthxClientBuilder.java
src/main/java/com/authx/sdk/ResourceFactory.java         # drop schemaCache param
src/main/java/com/authx/sdk/TypedGrantAction.java        # drop schema validation
src/main/java/com/authx/sdk/TypedRevokeAction.java       # drop schema validation
src/main/java/com/authx/sdk/spi/SdkComponents.java       # drop watch fields
src/main/java/com/authx/sdk/event/SdkTypedEvent.java     # drop watch/cache events
src/main/java/com/authx/sdk/metrics/SdkMetrics.java      # drop cache/watch counters
src/main/java/com/authx/sdk/policy/ResourcePolicy.java   # drop cache field
src/main/java/com/authx/sdk/policy/PolicyRegistry.java   # drop cache wiring
README.md
README_en.md
CLAUDE.md
llms.txt
```

**Created**:

```
docs/adr/2026-04-18-remove-l1-cache.md
src/test/java/com/authx/sdk/BuilderCacheMethodRemovedTest.java
src/test/java/com/authx/sdk/NoWatchStreamStartsTest.java
src/test/java/com/authx/sdk/transport/TransportChainTest.java (if not already present)
```

## Migration Notes

For downstream users (external):

1. Remove all `.cache(...)` calls from `AuthxClientBuilder`.
2. Remove all Watch-event subscriptions
   (`client.onRelationshipChange(...)`).
3. Remove any `CacheHandle` / `WatchStrategy` / `DuplicateDetector`
   usages.
4. If relying on L1 hit rate for latency, negotiate with infra team for
   SpiceDB cluster sizing to absorb the ~20x RPS increase.
5. For per-request low-latency needs, consider
   `Consistency.minimizeLatency()` — reads hit SpiceDB's dispatch cache
   which is schema-aware and doesn't have the inheritance invalidation
   problem.

## Performance Baseline to Record

Before merge, capture from cluster-test:

| Metric | Before (with L1) | After (no L1) — measured |
|---|---|---|
| p50 check latency | 3μs (hot) / 1-5ms (cold) | (record) |
| p99 check latency | (record) | (record) |
| SpiceDB cluster QPS at 100k check/s | ~5k (95% cache hit) | ~100k (estimated) |
| Client JVM CPU | (record) | (expected lower — no cache maintenance) |
| Client JVM memory | (record) | (expected lower — no 100k-entry cache) |

Record in the ADR's "Performance impact" section before the PR merges.

## Open Questions

None. Design is locked per the brainstorming dialogue on 2026-04-18.
