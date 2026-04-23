# Changelog

All notable changes to the AuthX SpiceDB SDK are documented here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This changelog starts at 2.0. Pre-2.0 history lives in git and in `docs/adr/`.
Every PR that affects SDK behavior appends a line under `[Unreleased]`; on
release, that block rolls into a dated version heading.

---

## [Unreleased]

### Changed
- `build.gradle` POM description no longer advertises the removed L1 cache
  or Watch-based invalidation.

---

## [2.0.1] — 2026-04

Consolidated release covering the architectural reset from the 2026-04 cycle.
See `docs/adr/` for the full rationale of each breaking change below.

### Removed
- **Client-side decision cache and Watch-based invalidation**
  (ADR 2026-04-18). `com.authx.sdk.cache.Cache`, `CaffeineCache`,
  `IndexedCache`, `NoopCache`, `com.authx.sdk.watch.*`, `CacheHandle`,
  `CachedTransport`, `WatchCacheInvalidator`, `WatchConnectionState`,
  `internal.SdkCaching`, `spi.DuplicateDetector`,
  `spi.DroppedListenerEvent`, `spi.QueueFullPolicy` all deleted.
  `AuthxClientBuilder.cache(...)` and `.extend().addWatchStrategy(...)`
  removed. `AuthxClient#onRelationshipChange` / `offRelationshipChange`
  removed. The SDK now relies on SpiceDB's server-side dispatch cache for
  decision-level caching.
- **Redis L2 cache layer** (ADR 2026-04-08, earlier in the cycle).
- **`TypedGrantAction` / `TypedRevokeAction`** and their `GrantCompletion`
  / `RevokeCompletionImpl` supporting classes (ADR 2026-04-22). Replaced
  by `WriteFlow` — see below.
- **`TypedHandle.grant(R...)` varargs and `grant(Collection<R>)` overloads**
  (ADR 2026-04-22). Multi-relation writes go through `WriteFlow`'s chained
  `.grant(R).to(...).grant(R2).to(...)` form.
- **Per-type `TYPE` constant on generated typed classes** (2026-04-22
  schema-flat-descriptors). Descriptor lookup now goes through the flat
  `Schema.Xxx` aggregator; see `docs/migration-schema-flat-descriptors.md`.
- **`GrantCompletion.listener` / `listenerAsync`** — zero real usage
  beyond tests.

### Added
- **`WriteFlow`** — unified grant/revoke/mixed write API.
  `.grant(R).to(...)` / `.revoke(R).from(...)` accumulate updates;
  `.commit()` flushes them atomically in one `WriteRelationships` RPC.
  Mixed TOUCH/DELETE in a single flow supported (e.g. role change in one
  atomic call). `commitAsync()` for non-blocking callers. `WriteCompletion`
  return value offers chainable sync/async listeners.
- **Schema flat descriptors** — `import static Schema.*` lets business
  code drop `.TYPE` / `.class` from chains:
  `client.on(Document).select(id).check(Document.Perm.VIEW).by(User, userId)`.
  See `docs/migration-schema-flat-descriptors.md`.
- **`SchemaCache` (metadata-only) + `SchemaLoader` + `SchemaClient`**
  restored in a cache-free form after the L1 removal (Addendum to ADR
  2026-04-18, 2026-04-21). Powers codegen and runtime fail-fast subject
  validation. No decision caching. `UNIMPLEMENTED` from older SpiceDB
  versions is non-fatal.
- **Caveat codegen** — `AuthxCodegen` emits `Caveats` type with
  per-caveat context record classes (spec 2026-04-13).
- **`sdk-redisson` module** — Redisson-backed `DistributedTokenStore`
  implementation for multi-JVM SESSION consistency. Opt-in; main SDK
  stays Redisson-free (spec 2026-04-16).
- **Logging traceability upgrade** — `LogCtx` prefix, `LogFields` suffix,
  `Slf4jMdcBridge` auto-enriches SLF4J MDC with OTel trace-id and
  `authx.*` fields when SLF4J is on classpath (spec 2026-04-20).
- **Schema-aware typed codegen** — `Document.Rel.VIEWER.subjectTypes()`
  metadata drives `.to(User, userId)` typed overloads and runtime
  fail-fast subject validation (spec 2026-04-21).

### Changed
- **`TypedHandle.grant(R)` / `.revoke(R)` return type changed** from
  `TypedGrantAction<R>` / `TypedRevokeAction<R>` to `WriteFlow`.
  Callers must now end the chain with `.commit()` — forgetting does
  **not** throw and the RPC is silently skipped. Guard with code review
  (ErrorProne `@CheckReturnValue` planned).
- **Transport stack reduced from 7 to 6 layers** after L1 cache removal:
  `Interceptor → Instrumented → Coalescing → PolicyAwareConsistency →
  Resilient → gRPC → SpiceDB`.
- **`check()` latency** — hot-path p50 moved from ~3µs (L1 hit) to
  ~1-5ms (direct SpiceDB). Use `Consistency.minimizeLatency()` to hit
  SpiceDB's server-side dispatch cache (~sub-ms) where needed.
- **SpiceDB QPS amplification** — each client `check()` now translates
  to one server-side call (was ~5% under L1). Operators must size
  SpiceDB cluster capacity accordingly.
- **gRPC dependency** — `io.grpc:grpc-*` upgraded 1.72.0 → 1.80.0
  (2026-04-10) to pick up fix for CVE-2025-55163 (Netty HTTP/2 DoS).

### Fixed
- **`target` and `targets` mutual exclusion** (SR:C6) — builder now
  rejects configurations that set both, instead of silently preferring
  `target`.
- **AssertJ 3.25.3 → 3.27.7** (test-only) — CVE-2026-24400 (XXE in
  `isXmlEqualTo`).

---

## Migration notes

- **From a pre-L1-removal version**: delete all `.cache(...)` builder
  calls; remove `CacheHandle` / `WatchStrategy` / `DuplicateDetector`
  references; drop `client.onRelationshipChange(...)` subscriptions.
- **From a pre-WriteFlow version**: append `.commit()` (or
  `.commitAsync()`) to every grant/revoke chain. Quick grep:
  `grep -rn '\.to([^)]*);' | grep -v commit`.
- **From a pre-flat-descriptor version**: add
  `import static <pkg>.schema.Schema.*;` and drop the `.TYPE` /
  `.class` arguments from chain calls. See
  `docs/migration-schema-flat-descriptors.md`.
