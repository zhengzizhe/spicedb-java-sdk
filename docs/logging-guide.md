# Logging & Traceability Guide

The AuthX SDK writes logs through `java.lang.System.Logger` — the JDK's
built-in, zero-dependency logging facade. Hosts decide the backend:
`java.util.logging` by default, or route to SLF4J / Log4j 2 / Logback
via the usual bridges.

On top of `System.Logger`, the SDK adds three non-invasive enrichment
layers so that logs, traces, and MDC all tell a consistent story:

| Layer | What it does | Who benefits |
|---|---|---|
| **Trace-id prefix** (`LogCtx`) | Prepends `[trace=<16hex>] ` to every log message when an OTel span is active. | Every reader, even without MDC. |
| **SLF4J MDC bridge** (`Slf4jMdcBridge`) | When SLF4J is on the classpath, pushes 15 `authx.*` keys onto the per-thread MDC for the duration of each RPC. | Logback / Log4j pattern users; JSON-encoder users. |
| **WARN+ suffix** (`LogFields`) | Appends ` [type=... res=... perm\|rel=... subj=...]` to WARN/ERROR messages that have resource context in scope. | On-call engineers grepping plain-text logs. |

All three layers are non-throwing by design (see "Stability guarantees"
below). The SDK never fails a call because logging failed.

---

## Quick start — three patterns

### Minimal (SLF4J + Logback)

```gradle
dependencies {
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    // Route java.util.logging → SLF4J so System.Logger ends up in Logback.
    implementation("org.slf4j:jul-to-slf4j:2.0.13")
}
```

`logback.xml`:

```xml
<configuration>
  <contextListener class="ch.qos.logback.classic.jul.LevelChangePropagator">
    <resetJUL>true</resetJUL>
  </contextListener>

  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%-5level %X{authx.traceId:-} - %msg%n</pattern>
    </encoder>
  </appender>

  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
  </root>
</configuration>
```

In your startup code, install the JUL bridge once:

```java
org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
org.slf4j.bridge.SLF4JBridgeHandler.install();
```

### Middle — pattern with structured fields

```xml
<pattern>%-5level %X{authx.traceId:-} [%X{authx.action:-} %X{authx.resourceType:-}:%X{authx.resourceId:-}] %logger{36} - %msg%n</pattern>
```

Example line:

```
WARN  8899aabbccddeeff [CHECK document:doc-42] c.a.s.t.ResilientTransport - Retry budget exhausted for [document], skipping retry [type=document]
```

### Full — JSON encoder with every authx.* field

With `logstash-logback-encoder`:

```xml
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
  <encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <includeMdcKeyName>authx.traceId</includeMdcKeyName>
    <includeMdcKeyName>authx.spanId</includeMdcKeyName>
    <includeMdcKeyName>authx.action</includeMdcKeyName>
    <includeMdcKeyName>authx.resourceType</includeMdcKeyName>
    <includeMdcKeyName>authx.resourceId</includeMdcKeyName>
    <includeMdcKeyName>authx.permission</includeMdcKeyName>
    <includeMdcKeyName>authx.relation</includeMdcKeyName>
    <includeMdcKeyName>authx.subject</includeMdcKeyName>
    <includeMdcKeyName>authx.consistency</includeMdcKeyName>
    <includeMdcKeyName>authx.retry.attempt</includeMdcKeyName>
    <includeMdcKeyName>authx.retry.max</includeMdcKeyName>
    <includeMdcKeyName>authx.cb.state</includeMdcKeyName>
    <includeMdcKeyName>authx.caveat</includeMdcKeyName>
    <includeMdcKeyName>authx.expiresAt</includeMdcKeyName>
    <includeMdcKeyName>authx.zedToken</includeMdcKeyName>
  </encoder>
</appender>
```

---

## Level semantics

The SDK follows a strict convention. If a line is not operator-actionable
it stays at `DEBUG` — `WARN` is a finite budget and noise erodes trust.

| Level  | When the SDK uses it |
|--------|-----------------------|
| `ERROR` | Unrecoverable startup phase failure (lifecycle); the SDK cannot continue. |
| `WARN`  | Operator needs to act: interceptor bug, distributed token store down, retry budget exhausted, telemetry sink permanently failing, SDK degraded. |
| `INFO`  | Non-noisy lifecycle events: SDK started, CB state transition, token store recovered. At most O(1) per change, never per-request. |
| `DEBUG` | Per-request / per-retry diagnostic detail. Off in production by default. |
| `TRACE` | Not currently used. |

### Level-change changelog (2026-04-20)

Three sites were downgraded from `WARN` to `DEBUG` as part of the
traceability upgrade — they fire on normal products of the resilience
and bulk-RPC policies, not on operator-actionable failures:

1. `ResilientTransport` — per-retry log (`"Retry {n}/{max} for [type]: ..."`).
   The signal operators actually need — *all retries exhausted* — stays at
   `WARN` above as `"Retry budget exhausted for [type], skipping retry"`.
2. `GrpcTransport#checkBulk` — per-item bulk check error. A single bad item
   in a large bulk call is not an operator issue; the item is treated as
   `NO_PERMISSION` and the call succeeds.
3. `GrpcTransport#checkBulkMulti` — same as above, in the multi-batch path.

No levels were raised. All other messages keep their previous level.

---

## MDC fields reference

When SLF4J is on the classpath, the SDK pushes up to 15 `authx.*` keys
onto MDC at the start of each RPC and clears them on return (via
try-with-resources). If SLF4J is not present, the bridge is a silent
no-op — no classes loaded, no per-call overhead.

| Key | When populated | Example |
|---|---|---|
| `authx.traceId` | Always, when an OTel span is active. | `8899aabbccddeeff0011223344556677` |
| `authx.spanId`  | Always, when an OTel span is active. | `0011223344556677` |
| `authx.action`  | Every RPC. | `CHECK`, `GRANT`, `REVOKE`, `LOOKUP`, `READ`, `EXPAND` |
| `authx.resourceType` | Check / read / lookup-resources / write. | `document` |
| `authx.resourceId`   | Check / read; absent for lookup-resources. | `doc-42` |
| `authx.permission`   | Check / lookup. | `view` |
| `authx.relation`     | Read / write / expand. | `editor` |
| `authx.subject`      | Check / write / lookup. Format `type:id` or `type:id#relation`. | `user:alice` |
| `authx.consistency`  | Every read. Lowercase simple class name. | `minimizelatency`, `atleast`, `fullyconsistent` |
| `authx.retry.attempt`| Set on Span inside the Resilience4j retry event — visible in the span but not pushed to MDC. | — |
| `authx.retry.max`    | Same as above (span attribute only). | — |
| `authx.cb.state`     | Span attribute only; INFO log captures CB state transitions. | — |
| `authx.caveat`       | Write path, when a caveat is attached. | `valid_ip_range` |
| `authx.expiresAt`    | Write path, when `expiresAt` is set. | `2026-04-21T00:00:00Z` |
| `authx.zedToken`     | On write; rarely useful in logs but available for read-after-write debugging. | `GhUKCzE3MDAwMDAwMDA=` |

Keys that are null or blank are omitted — no MDC noise for fields that
don't apply to the current action.

---

## OTel span attributes

The SDK also enriches the active span with attributes at key points:

| Attribute | Source | Fires when |
|---|---|---|
| `authx.action` | `InstrumentedTransport` | Every RPC. |
| `authx.resource.type` / `authx.resource.id` | `InstrumentedTransport` | Every RPC. |
| `authx.permission` | `InstrumentedTransport` | Every check / lookup. |
| `authx.subject` | `InstrumentedTransport` | When subject is known (multi-tenant triage). |
| `authx.result` | `InstrumentedTransport` | On success. |
| `authx.errorType` | `InstrumentedTransport` | On exception — simple class name for fast APM filtering. |
| `authx.consistency` | `PolicyAwareConsistencyTransport` | Every read. |
| `authx.retry.attempt` / `authx.retry.max` | `ResilientTransport` | Each retry. Span event `retry_attempt` fires alongside. |

Wiring OTel is host-side:

```java
// at startup — any standard OTel SDK install will do
OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
    .setTracerProvider(
        SdkTracerProvider.builder()
            .addSpanProcessor(BatchSpanProcessor.builder(otlpExporter).build())
            .build())
    .buildAndRegisterGlobal();
```

The SDK uses `GlobalOpenTelemetry` via `Span.current()`; nothing further
is needed inside the SDK.

---

## WARN+ suffix

Every message emitted at `WARN` or higher that has resource context in
scope is automatically augmented with a bracketed suffix:

```
"...original message..." [type=<resourceType> res=<resourceId> perm=<permission> subj=<subjectRef>]
```

The suffix is driven by `LogFields.suffixPerm(...)` / `suffixRel(...)`:
perm-style for check/lookup, rel-style for write / expand. Fields that
are null or blank are omitted — the brackets contain only what's known.

The message body text upstream of the suffix is unchanged from prior
SDK versions — alerting rules keyed on message regexes continue to
match. Only the tail is new.

---

## Stability guarantees

The logging layer is subject to seven design rules (SG-1..SG-7 in the
2026-04-20 spec):

1. **Never throws.** Every public method in `LogCtx`, `LogFields`, and
   `Slf4jMdcBridge` is wrapped in try/catch. If enrichment fails, the
   raw message still goes to the log.
2. **Works without SLF4J.** The bridge probes for `org.slf4j.MDC` at
   class-load time; if absent, every `push(...)` returns a shared
   `NOOP` Closeable and no SLF4J classes are loaded.
3. **Suffix is opt-in per call site.** Not every WARN has resource
   context; sites without it (e.g., async listener threw, telemetry
   sink failure) emit the original message unchanged.
4. **Permanent self-disable on MDC error.** If `MDC.put(...)` throws,
   the bridge flips an atomic flag, logs one WARN, and returns NOOP on
   every subsequent push. The SDK continues serving requests.
5. **Host MDC preserved.** The bridge only pops the keys it pushed —
   business MDC keys set by your application survive the SDK call.
6. **Message body frozen.** Only `[trace=...] ` prefix and ` [type=...]`
   suffix are added — the message text upstream of the suffix is never
   changed. Regex-based alerting rules continue to match.
7. **Eager formatting.** Message arguments are formatted immediately
   (`MessageFormat.format`). There is no lazy supplier — the JDK
   logger decides filtering based on the final message string.

---

## Disabling the MDC bridge

Don't add `org.slf4j:slf4j-api` to your runtime classpath — the bridge
auto-detects this and turns into a no-op. Trace prefix (`LogCtx`) still
works because it only depends on OTel; only the per-call MDC push is
skipped.

Alternatively, to keep SLF4J on the classpath but silence the bridge
permanently at runtime, you can throw from a Logback `TurboFilter`
once — the self-disable path catches it and never touches MDC again.
This is not a supported pattern; removing SLF4J is cleaner.

---

## See also

- `docs/resilience-guide.md` — retry / circuit-breaker level conventions.
- ADR 2026-04-18 (`docs/adr/2026-04-18-remove-l1-cache.md`) — why SDK
  logs no longer include cache-layer lines.
- `specs/2026-04-20-logging-traceability-upgrade/spec.md` — the full
  25-requirement spec that drove this guide.
