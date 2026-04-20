# Cache and Consistency Guide — REMOVED

> **This guide is obsolete.** The AuthX SDK's client-side L1 decision cache
> and Watch-based invalidation infrastructure were removed on 2026-04-18.
> See [ADR 2026-04-18](adr/2026-04-18-remove-l1-cache.md) for rationale
> (inheritance-chain invalidation correctness limitation).
>
> The SDK no longer caches permission decisions. All `check()` calls go
> directly to SpiceDB. Decision-level caching now relies on SpiceDB's
> server-side dispatch cache, which is schema-aware and handles
> inheritance correctly.

## Current consistency API (still valid)

The four consistency modes remain:

| Mode | Fresh-as-of | Typical use |
|---|---|---|
| `Consistency.minimizeLatency()` | SpiceDB's dispatch-cache quantization (~5s) | Low-latency reads; hits server-side cache |
| `Consistency.atLeastAsFresh(token)` | As fresh as `token` (SESSION consistency) | Read-your-writes inside a session |
| `Consistency.atExactSnapshot(token)` | Exactly `token` | Reproducible reads pinned to a revision |
| `Consistency.fullyConsistent()` | Latest committed | When 5s staleness is unacceptable |

SDK-maintained `TokenTracker` (in-JVM) and optional `DistributedTokenStore`
(cross-JVM, via [`sdk-redisson`](../sdk-redisson/README.md)) automatically
thread zedTokens through SESSION reads — business code only writes once
and reads once, the SDK handles the chaining.

## Historical context

For the original L1/L2 design that was removed, see the two ADRs:
- [2026-04-08: Remove L2 Redis cache](adr/2026-04-08-remove-redis-l2-cache.md)
- [2026-04-18: Remove L1 Caffeine cache + Watch](adr/2026-04-18-remove-l1-cache.md)
