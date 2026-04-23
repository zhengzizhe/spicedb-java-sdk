# AuthX SDK — SpiceDB Java SDK

## What is this

Java SDK for [SpiceDB](https://authzed.com/spicedb) — a Zanzibar-inspired authorization system. This SDK wraps gRPC calls to SpiceDB and provides resilience, observability, and a fluent API for permission checks, relationship writes, lookups, and schema management. (No client-side decision caching — see ADR 2026-04-18.)

## Tech stack

- **Java 21**, Gradle, gRPC (Netty-shaded)
- **Resilience**: Resilience4j (circuit breaker, retry, rate limiter, bulkhead)
- **Decision cache**: none in SDK — SpiceDB's server-side dispatch cache handles it (ADR 2026-04-18)
- **Observability**: OpenTelemetry API, Micrometer (optional), HdrHistogram
- **Logging**: `java.lang.System.Logger` + optional SLF4J 2.0.13 MDC bridge (`com.authx.sdk.trace.Slf4jMdcBridge`). All log messages auto-enriched with OTel trace-id prefix when a span is active. See [`docs/logging-guide.md`](docs/logging-guide.md).
- **Testing**: JUnit 5, AssertJ, Mockito

## Project structure

```
src/main/java/com/authx/sdk/
├── AuthxClient.java          # Main entry point
├── AuthxClientBuilder.java   # Client builder (connection/feature/extend)
├── ResourceFactory/Handle/Type.java   # Resource navigation (typed chain uses these)
├── Typed*.java               # Typed fluent chain (tightly coupled with
│                              #   ResourceFactory package-private internals — stays here)
├── SchemaClient.java         # Public wrapper over schema metadata (resource
│                              #   types, relations, permissions, caveats, per-
│                              #   relation subject types). Exposed as
│                              #   AuthxClient.schema().
├── AuthxCodegen.java         # Static generator — emits typed schema classes
│                              #   (Document.Rel, Document.Perm, IpAllowlist.ref(),
│                              #   Caveats) plus a flat Schema.java aggregator
│                              #   (XxxDescriptor + XxxRel/PermProxy for
│                              #   `import static Schema.*` ergonomics). From
│                              #   2026-04-22 the per-type TYPE constant is gone;
│                              #   descriptor lookup goes through Schema.Xxx.
│                              #   See docs/migration-schema-flat-descriptors.md.
├── cache/                     # Metadata caches only. SchemaCache (definitions +
│                              #   caveats + per-relation subject types) used by
│                              #   codegen and runtime fail-fast subject
│                              #   validation. NO decision cache (ADR 2026-04-18).
├── internal/                  # Internal plumbing: SdkConfig, SdkInfrastructure,
│                              #   SdkObservability. Not public API — do not depend on.
├── action/                    # Untyped fluent chain actions: Grant/Revoke/Check/
│                              #   Batch (terminal methods return results directly;
│                              #   WriteFlow handles typed grant/revoke at top level)
├── transport/                 # Transport chain: gRPC → resilient → coalescing → instrumented
├── model/                     # Value objects, requests, results, enums
├── exception/                 # Exception hierarchy (maps gRPC status codes)
├── policy/                    # Retry, circuit breaker, consistency policies
├── builtin/                   # Built-in interceptors (validation, debug, resilience4j)
├── spi/                       # Extension points (interceptor, clock, telemetry sink, tokenStore)
├── lifecycle/                 # SDK lifecycle state machine
├── event/                     # Typed event bus
├── health/                    # HealthProbe implementations
├── trace/                     # TraceParent propagation + logging/MDC enrichment
│                              #   (LogCtx prefix, Slf4jMdcBridge, LogFields suffix)
├── metrics/                   # SDK metrics
└── telemetry/                 # Telemetry reporter
test-app/                      # Demo Spring Boot app
sdk-redisson/                  # Optional Redisson-backed DistributedTokenStore
                               # (multi-JVM SESSION consistency)
```

## Build commands

```bash
./gradlew compileJava                 # Compile SDK
./gradlew test -x :test-app:test      # SDK unit tests only
./gradlew test                         # All tests (SDK + test-app)
```

## Workflow

All engineering workflows are managed by **authx skills** in `.claude/skills/`. Use the Skill tool to invoke them:

- New feature / behavior change: `authx-brainstorming` → `authx-writing-plans` → `authx-executing-plans`
- Bug fix: `authx-systematic-debugging` → fix → verify
- Completion: `authx-verification-before-completion`

