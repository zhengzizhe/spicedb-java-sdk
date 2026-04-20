# Logging & Traceability Upgrade

**Date**: 2026-04-20
**Status**: Draft
**Scope**: SDK-wide logging and OTel-trace enhancement — non-breaking additive changes
**Parent session**: ADR 2026-04-18 (L1 cache + Watch removal) exposed that SDK's default `System.Logger` output had no trace-id, no structured context, and inconsistent levels.

## Background

AuthX SDK currently uses `java.lang.System.Logger` (JDK platform, zero runtime deps) at 49 call sites across 15 classes. Observations from operating against the 3-node cluster test harness and reviewing log dumps from early adopters:

- **No correlation**. SDK log lines cannot be correlated with the business HTTP request that triggered them, or with the OTel trace the SDK runs under.
- **No structured fields**. Messages are plain-text strings — log aggregation backends (ELK / Loki / Datadog / Splunk) see only the message, can't facet on `resourceType` / `subject` / `action`.
- **Level inconsistency**. ~40 of 49 sites log at WARNING, including normal retry paths. Operators get alerting fatigue; real WARN signal (CB state change, write fail-closed) gets lost in retry noise.
- **No SLF4J bridge documentation**. Users who haven't configured `jul-to-slf4j` get SDK logs on stderr — invisible to their log aggregation setup.
- **OTel span underutilized**. `InstrumentedTransport` creates an `authx.<action>` span only when `features.telemetry(true)`, and with only 3 attributes (type/id/permission). No retry count, no CB state, no result.

The SDK runs against OpenTelemetry API (compileOnly) — every call already has access to `Span.current()` for trace-id. We haven't wired that into logs. The SDK also could optionally bridge to SLF4J's MDC for structured fields, gated on SLF4J being on classpath.

This spec upgrades the logging and traceability surface to **industry Top-1/3 Java SDK quality** (aligned with Spring, Kafka, Temporal; surpassing AWS SDK v2, Netty, Resilience4j) — **without introducing any runtime dependency and without breaking any existing log-based alerts**.

## Goals

- Every SDK log line automatically carries the OTel trace-id when available.
- When SLF4J is on classpath, SDK pushes 15 structured MDC fields around each RPC call, so Logback / Log4j pattern / JSON encoders see them natively.
- `authx.<action>` OTel span carries 10+ structured attributes (retry count, CB state, consistency, result, etc.) for Jaeger / Tempo / Datadog APM.
- Log levels audited per a clear 5-level convention (ERROR / WARN / INFO / DEBUG / TRACE) — retry noise goes to DEBUG, real warnings stay at WARN.
- WARN-or-higher logs include a contextual suffix `[type=... res=... {perm|rel}=... subj=...]` so users without SLF4J still get location info.
- Zero runtime-dependency increase. SLF4J is `compileOnly`. Users who don't add SLF4J get legacy `System.Logger` behavior unchanged.
- Log messages' main body text UNCHANGED — users with alert rules based on message regex keep working.
- New `docs/logging-guide.md` with 3 Logback pattern examples (minimal / middle / full) + Logstash JSON encoder config.

## Non-goals

- **No custom log output format / appender.** Delegate to host app's Logback/Log4j; SDK is emitter only.
- **No async log buffering.** Use Logback `AsyncAppender`; SDK is synchronous.
- **No auto-redaction of sensitive fields.** Subject may contain PII (`user:alice@corp.com`); host app configures redaction filters.
- **No direct Kafka/OTLP log shipping.** Use TelemetrySink SPI (already exists) or Logback appender.
- **No automatic TypedEventBus → log translation.** Events and logs serve different audiences (machine vs human).
- **No Prometheus / Micrometer adapter.** Explicitly out of scope (user confirmed).
- **No Log4j 1.x / Tinylog / Commons-Logging bridge.** SLF4J-only bridge target.
- **No runtime log-level control API on AuthxClient.** Use JUL / SLF4J's own APIs.
- **No custom `LoggingEventBuilder` fluent API wrapping.** Use JDK `System.Logger` (keeps zero-dep default path simple).

## Requirements

Each requirement has ID `req-N` for tasks.md traceability.

### Trace-id enrichment (always-on)

**req-1** New class `com.authx.sdk.trace.LogCtx` (public, stateless):
- `static String fmt(String msg)` — prepends `[trace=<16hex>] ` when a valid OTel `Span.current()` exists; returns `msg` unchanged otherwise.
- `static String fmt(String msg, Object... args)` — same, with JDK `MessageFormat` style `{0}/{1}` args interpolated.
- Trace-id = `Span.current().getSpanContext().getTraceId()` — take the **last 16 hex characters** (W3C allows truncation for display; full 32 chars remains on MDC + span).
- Never throws — all paths wrapped in try/catch, fallback returns original message.

**req-2** Replace all 49 `LOG.log(LEVEL, "msg", args)` call sites with `LOG.log(LEVEL, LogCtx.fmt("msg", args))`. Mechanical replacement — message text body unchanged (only prefix added when trace-id available).

### SLF4J MDC bridge (optional, compileOnly)

**req-3** Add `compileOnly("org.slf4j:slf4j-api:2.0.13")` to `build.gradle`. Add `testImplementation("org.slf4j:slf4j-api:2.0.13")` and `testImplementation("org.slf4j:slf4j-simple:2.0.13")` for tests.

**req-4** New class `com.authx.sdk.trace.Slf4jMdcBridge` (public):
- `static final boolean SLF4J_PRESENT` — probed once at class-init time via `Class.forName("org.slf4j.MDC")`; cached.
- `static Closeable push(Map<String, String> fields)` — when `SLF4J_PRESENT` true, writes each key to SLF4J MDC and returns a Closeable that removes all keys on `close()`. When false, returns a shared `NOOP` Closeable constant (zero allocation).
- All MDC operations wrapped in try/catch; on any `Throwable` the bridge permanently disables itself (static `AtomicBoolean disabled`) and logs one WARN via `System.Logger`.

**req-5** New class `com.authx.sdk.trace.LogFields` (public, constants-only):
- 15 MDC key constants, all prefixed `authx.*`:
  - `authx.traceId`, `authx.spanId`
  - `authx.action` (CHECK / GRANT / REVOKE / LOOKUP / READ / EXPAND / TELEMETRY / LIFECYCLE)
  - `authx.resourceType`, `authx.resourceId`
  - `authx.permission`, `authx.relation`, `authx.subject`
  - `authx.consistency`
  - `authx.retry.attempt`, `authx.retry.max`
  - `authx.cb.state`
  - `authx.caveat`, `authx.expiresAt`, `authx.zedToken`
  - `authx.result`, `authx.errorType`
- `static String suffix(String resourceType, String resourceId, String permOrRel, String subjectRef)` — builds ` [type=... res=... perm=... subj=...]` suffix. Returns empty string when all fields null.
- `static Map<String, String> toMdcMap(...)` — fields for `Slf4jMdcBridge.push`.

**req-6** Transport layers push MDC on RPC entry, pop on exit:
- `InterceptorTransport` (or equivalent RPC entry) wraps each call in `try (Closeable c = Slf4jMdcBridge.push(...)) { ... }`.
- Fields pushed: `authx.action`, `authx.resourceType`, `authx.resourceId`, `authx.permission` or `authx.relation`, `authx.subject`, `authx.consistency`, `authx.traceId`, `authx.spanId`.
- MDC scope strictly bounded to the RPC call (no leak to listener threads or async work).

### OTel span attributes (always-on)

**req-7** `InstrumentedTransport` — when creating the `authx.<action>` span, set additional attributes beyond current 3:
- `authx.consistency` (string: "minimize_latency" / "session" / "full" / "at_exact" / "at_least")
- `authx.subject` (string: `"user:alice"` / `"group:eng#member"`)
- `authx.retry.attempt` (long, set by ResilientTransport at retry time)
- `authx.retry.max` (long)
- `authx.cb.state` (string: "CLOSED" / "OPEN" / "HALF_OPEN" at call time)
- `authx.caveat` (string, nullable — caveat name if present)
- `authx.result` (string: "HAS_PERMISSION" / "NO_PERMISSION" / "CONDITIONAL" for check)
- `authx.errorType` (string: exception class simple name, nullable)

**req-8** `ResilientTransport` publishes `authx.retry.attempt` as span attribute AND emits a span event `retry_attempt` with attributes `{attempt, max, errorType}` on each retry.

**req-9** `PolicyAwareConsistencyTransport` sets `authx.consistency` span attribute.

### Level audit (non-breaking)

**req-10** Change 18 specific log call sites from WARNING to DEBUG (full table in plan.md). Criteria: logging is normal behavior, not operator-actionable (e.g., retry attempt, bulk check item error). Main message text UNCHANGED.

**req-11** Change 3 specific log call sites from INFO to WARNING (full table in plan.md). Criteria: current INFO misclassifies operator-actionable events (e.g., telemetry sink timeout). Main message text UNCHANGED.

**req-12** All remaining log sites retain their current level.

### WARN+ suffix enrichment

**req-13** Every log call at WARNING or ERROR level MUST include `LogFields.suffix(type, id, permOrRel, subj)` appended to the message — format: `originalMsg [type=<T> res=<R> perm|rel=<X> subj=<S>]`. Fields that are null are omitted from the suffix. If all four fields are null, the whole suffix (including the leading space + brackets) is omitted.

**req-14** INFO / DEBUG / TRACE logs do NOT get the suffix (avoid verbosity in normal-operation logs).

### Documentation

**req-15** New `docs/logging-guide.md`:
- Level semantics table (ERROR / WARN / INFO / DEBUG / TRACE: when to use which)
- Full 15-key MDC field reference (key, type, when populated, value format)
- Three ready-to-copy Logback pattern examples:
  - Minimal: `%-5level %X{authx.traceId} - %msg%n`
  - Middle: `%-5level %X{authx.traceId} [%X{authx.action} %X{authx.resourceType}:%X{authx.resourceId}] %logger{36} - %msg%n`
  - Full: includes all relevant MDC keys
- One Logstash JSON encoder example (shows JSON output with all MDC fields)
- Section on "how to disable SLF4J bridge" — just don't add `slf4j-api` at runtime
- Section on OTel SDK wiring pointing to official OTel getting-started

**req-16** Update `README.md` / `README_en.md` — add a new "Logging" section + Changelog entry:
- Non-breaking additive upgrade
- Level changes: 18 WARN→DEBUG + 3 INFO→WARN (full list in `docs/logging-guide.md`)
- WARN+ suffix format change (additive)
- Example config snippets

**req-17** Update `CLAUDE.md` — one-line mention of logging layer, pointer to `docs/logging-guide.md`.

**req-18** Update `src/main/resources/META-INF/authx-sdk/GUIDE.md` — new "Logging & Tracing" section for AI agents.

### Stability guarantees (SG-*)

**req-19 (SG-1)** No log enrichment code path throws. All `LogCtx.fmt`, `Slf4jMdcBridge.push`, `LogFields.suffix` wrap internal logic in try/catch and return safe fallback on any `Throwable`. Unit tests must cover "internal API throws" paths.

**req-20 (SG-2)** When SLF4J absent from classpath:
- `Slf4jMdcBridge.SLF4J_PRESENT == false`
- `push(...)` returns a shared `NOOP` Closeable constant (zero allocation, zero class-loader action on SLF4J classes)
- No `org.slf4j.*` class is loaded

**req-21 (SG-3)** When SLF4J present but first MDC.put throws (rare — e.g., classpath conflict between SLF4J 1.7 and 2.x APIs): `Slf4jMdcBridge` disables itself permanently via `AtomicBoolean`, logs one WARN via `System.Logger`, all subsequent `push` calls return NOOP.

**req-22 (SG-4)** Existing log message main body text MUST NOT change. Only prefix (`[trace=...]`) and suffix (`[type=... res=...]`) are additive. Regex-based alerts on existing message bodies continue to match.

**req-23 (SG-5)** `Slf4jMdcBridge.push(Map)` Closeable MUST symmetrically remove every key it pushed, even if one removal throws — the others still complete. No MDC leak between requests.

**req-24 (SG-6)** Existing behavior: DEBUG-level call sites use eager argument evaluation (consistent with rest of SDK). No `fmtLazy` / Supplier-based API. Rationale: production deployments rarely keep DEBUG on long-term; simplicity wins.

**req-25 (SG-7)** Replacing 49 log call sites with `LogCtx.fmt(...)` wrapper is binary-compatible — the `LOG.log(Level, String)` / `LOG.log(Level, String, Object...)` signatures are identical. No AOT / GraalVM impact.

## Acceptance Tests

Each requirement maps to at least one test (full mapping in tasks.md Coverage section).

### Unit tests

- **`LogCtxTest`** (~8 cases):
  - `fmt_withActiveSpan_prefixesTraceId`
  - `fmt_withoutSpan_returnsOriginal`
  - `fmt_withInvalidSpanContext_returnsOriginal`
  - `fmt_withArgs_interpolatesCorrectly`
  - `fmt_throwsNever`
  - `traceIdSuffix_last16Chars_correctLength`
  - `fmt_withNullMsg_returnsEmpty`
  - `fmt_whenOtelThrows_returnsOriginal`

- **`Slf4jMdcBridgeTest`** (~7 cases):
  - `probe_whenSlf4jPresent_returnsTrue`
  - `push_whenPresent_writesMdc`
  - `push_closeable_popsAllKeys`
  - `push_whenAbsent_returnsNoop` (using `URLClassLoader` filter)
  - `push_nullFields_returnsNoop`
  - `push_mdcPutThrows_swallowsAndDisables`
  - `push_afterDisabled_returnsNoop`

- **`LogFieldsTest`** (~5 cases):
  - `suffix_allFieldsNull_returnsEmpty`
  - `suffix_someFieldsPresent_includesThem`
  - `suffix_allFieldsPresent_formatsCorrectly`
  - `toMdcMap_nullSafeConstruction`
  - `keyConstants_allStartWithAuthxPrefix`

### Integration tests

- **`LogEnrichmentIntegrationTest`** (~6 cases):
  - `checkCall_emitsLogWithTraceIdAndMdc` — OTel + SLF4J both wired; use `ListAppender` + `InMemorySpanExporter`
  - `retryingCall_logsAtDebugNotWarning` — assert 2 DEBUG entries, 0 WARN
  - `cbOpensCall_logsAtWarning` — assert 1 WARN with suffix `[type=document]`
  - `noOtel_tracePrefixEmpty` — reset GlobalOpenTelemetry, assert no `[trace=...]` in output
  - `noSlf4j_messageStillIncludesSuffix` — prove suffix works via `System.Logger` only
  - `concurrentRequests_noMdcLeakBetweenThreads` — 100 parallel checks, assert MDC empty after each

### Span attribute tests

- **`SpanAttributeTest`** extend existing `InstrumentedTransportTest`:
  - `authxCheckSpan_hasAllAttributes`
  - `retrySetsRetryAttemptAttribute`
  - `cbStateCapturedInAttribute`
  - `consistencyAttributeMatchesPolicy`

### Regression tests

- Existing 651-test suite: assert still green after changes.
- ~5-10 tests whose assertions reference log message content expected to need updating (update them explicitly in the same PR, not as silent ride-alongs).

### Performance tests (non-gate)

- `LogCtxPerformanceTest` (JMH):
  - `fmt_noSpan_benchmark` — target p99 < 100ns/call
  - `fmt_withSpan_benchmark` — target p99 < 500ns/call
  - `slf4jBridge_push_benchmark` — target p99 < 1μs/call
- Run once for reference; not a CI gate.

## File-level Impact

**New files**:
- `src/main/java/com/authx/sdk/trace/LogCtx.java` (~60 LOC)
- `src/main/java/com/authx/sdk/trace/Slf4jMdcBridge.java` (~120 LOC)
- `src/main/java/com/authx/sdk/trace/LogFields.java` (~80 LOC)
- `src/test/java/com/authx/sdk/trace/LogCtxTest.java` (~100 LOC)
- `src/test/java/com/authx/sdk/trace/Slf4jMdcBridgeTest.java` (~130 LOC)
- `src/test/java/com/authx/sdk/trace/LogFieldsTest.java` (~60 LOC)
- `src/test/java/com/authx/sdk/trace/LogEnrichmentIntegrationTest.java` (~150 LOC)
- `docs/logging-guide.md` (~200 LOC)

**Modified files**:
- `build.gradle` (+3 lines: SLF4J compileOnly + testImplementation)
- 15 files with `System.Logger` calls — 49 replacements to `LogCtx.fmt(...)` wrapper
- `src/main/java/com/authx/sdk/transport/InstrumentedTransport.java` — +15 lines span attributes
- `src/main/java/com/authx/sdk/transport/ResilientTransport.java` — +5 lines retry span attribute + event
- `src/main/java/com/authx/sdk/transport/PolicyAwareConsistencyTransport.java` — +3 lines consistency attribute
- `src/main/java/com/authx/sdk/transport/InterceptorTransport.java` (or equivalent RPC entry point) — +10 lines MDC push/pop
- `README.md`, `README_en.md` — Changelog + Logging section (~50 LOC each)
- `CLAUDE.md` — 1-line mention
- `src/main/resources/META-INF/authx-sdk/GUIDE.md` — new section (~40 LOC)

**Deleted files**: none.

## Migration

**For existing users**: **zero action required** in the typical case.

- If already using SLF4J (Spring Boot 2+/3+, Logback, Log4j2 with slf4j-api 2.x) → MDC fields auto-populate; recommended to update Logback pattern to include `%X{authx.traceId}`.
- If not using SLF4J → SDK logs continue via `System.Logger` to default JDK destination (stderr); trace-id prefix still added if OTel is configured.
- If relying on log level conventions → review the 21 level changes (18 WARN→DEBUG, 3 INFO→WARN) listed in `docs/logging-guide.md`; update alerts if needed. Main body message text unchanged.

**For users who want JSON output**: add Logstash encoder dependency + config (example in `docs/logging-guide.md`).

## Rollback

Single PR; `git revert` restores prior behavior. The 18 WARN→DEBUG level changes can be partially reverted by the user via Logback `<logger name="com.authx.sdk.transport.ResilientTransport" level="WARN"/>` override without touching code.

## Open Questions

None. All 7 design sections approved 2026-04-20.
