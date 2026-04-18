# AuthX SDK — SpiceDB Java SDK

## What is this

Java SDK for [SpiceDB](https://authzed.com/spicedb) — a Zanzibar-inspired authorization system. This SDK wraps gRPC calls to SpiceDB and provides caching, resilience, observability, and a fluent API for permission checks, relationship writes, lookups, and schema management.

## Tech stack

- **Java 21**, Gradle, gRPC (Netty-shaded)
- **Resilience**: Resilience4j (circuit breaker, retry, rate limiter, bulkhead)
- **Cache**: Caffeine (optional, single-tier L1) with Watch-based invalidation
- **Observability**: OpenTelemetry API, Micrometer (optional), HdrHistogram
- **Testing**: JUnit 5, AssertJ, Mockito

## Project structure

```
src/main/java/com/authx/sdk/
├── AuthxClient.java          # Main entry point
├── transport/                 # Transport chain: gRPC → resilient → cached → instrumented
├── cache/                     # Cache<K,V>, Caffeine, tiered, schema cache
├── model/                     # Value objects, requests, results, enums
├── exception/                 # Exception hierarchy (maps gRPC status codes)
├── policy/                    # Retry, circuit breaker, cache, consistency policies
├── builtin/                   # Built-in interceptors (validation, debug, resilience4j)
├── spi/                       # Extension points (interceptor, clock, telemetry sink)
├── lifecycle/                 # SDK lifecycle state machine
├── event/                     # Typed event bus
├── watch/                     # Watch dispatcher, strategies
├── metrics/                   # SDK metrics
└── telemetry/                 # Telemetry reporter
test-app/                      # Demo Spring Boot app
cluster-test/                  # Production cluster stress test harness
                               # (3 SpringBoot instances + Toxiproxy + HTML report)
sdk-redisson/                  # Optional Redisson-backed DistributedTokenStore
                               # (multi-JVM SESSION consistency)
```

## Build commands

```bash
./gradlew compileJava                                    # Compile SDK
./gradlew test -x :test-app:test -x :cluster-test:test   # SDK unit tests only
./gradlew test                                            # All tests
./gradlew :cluster-test:bootJar                           # Build cluster-test runnable jar
```

For cluster stress testing see `cluster-test/README.md`.

## Workflow

All engineering workflows are managed by **authx skills** in `.claude/skills/`. Use the Skill tool to invoke them:

- New feature / behavior change: `authx-brainstorming` → `authx-writing-plans` → `authx-executing-plans`
- Bug fix: `authx-systematic-debugging` → fix → verify
- Completion: `authx-verification-before-completion`

