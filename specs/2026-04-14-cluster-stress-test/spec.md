# Production-Grade Cluster Stress Test + HTML Report

**Date:** 2026-04-14
**Goal:** Build a comprehensive end-to-end test harness that validates correctness, baseline performance, P0/P1 risk fixes, peak capacity, and long-run stability against a realistic 3-CRDB + 3-SpiceDB + 3-instance Spring Boot cluster, then produces a self-contained HTML report.

---

## Scope

A new `cluster-test/` Gradle submodule containing a Spring Boot 3.x application that runs in 3 coordinated instances. An external bash orchestrator drives the entire suite. Failure injection uses Toxiproxy (network layer) plus docker commands (container layer). Output is a single self-contained HTML file plus per-phase JSON results.

---

## Requirements

### req-1: Cluster bootstrap

The orchestrator must bring up a complete cluster on a single host:

- 3 CockroachDB nodes (already in `deploy/docker-compose.yml`)
- 3 SpiceDB nodes (already there)
- 1 Toxiproxy container as control plane for failure injection
- Prometheus + Grafana (already there)
- 3 Spring Boot instances (NEW — `cluster-test`) on ports 8091/8092/8093

**Toxiproxy proxies must be configured** for each SpiceDB instance, exposing both data ports (50061-50063) and Watch ports through the proxy. Spring Boot instances connect via Toxiproxy when in resilience mode, directly otherwise.

**Success:** `orchestrator/start-cluster.sh` returns 0 only when all 3 Spring Boot instances respond `UP` on `/actuator/health` and Prometheus scrapes are succeeding for all targets.

### req-2: cluster-test Gradle submodule

A new submodule `cluster-test/` registered in `settings.gradle`. Spring Boot 3.x, Java 21, dependencies:

- `project(":")` (authcses-sdk)
- `spring-boot-starter-web` + `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`
- `caffeine`
- `com.authzed.api:authzed:1.5.4` (for direct BulkImport gRPC)
- `org.hdrhistogram:HdrHistogram:2.2.2`
- `eu.rekawek.toxiproxy:toxiproxy-java:2.1.7` (Toxiproxy client)

**Configuration via `application.yml`:**

```yaml
server:
  port: ${SERVER_PORT:8091}
cluster:
  node-index: ${NODE_INDEX:0}
  node-count: 3
  leader-port: 8091
  results-dir: ${RESULTS_DIR:./results}
spicedb:
  targets: ${SPICEDB_TARGETS:localhost:50061,localhost:50062,localhost:50063}
  preshared-key: testkey
toxiproxy:
  host: ${TOXIPROXY_HOST:localhost}
  port: ${TOXIPROXY_PORT:8474}
  enabled: ${TOXIPROXY_ENABLED:false}
```

**Success:** `./gradlew :cluster-test:compileJava :cluster-test:bootJar` produces a runnable jar.

### req-3: Data generation and bulk import

Generate ~10 million relationships matching the schema in `deploy/schema-v2.zed`:

| Entity | Count |
|---|---|
| user | 10,000 |
| group | 200 |
| organization | 10 |
| space | 1,000 |
| folder | 50,000 (max 20 levels deep) |
| document | 500,000 |

| Relation type | Approximate count |
|---|---|
| group#member | 4,000 |
| organization#member/admin | 10,100 |
| space#member/viewer/admin | 30,000 |
| folder#parent + ancestor flattening | 1,500,000 |
| folder#editor/viewer | 200,000 |
| document#folder + document#space | 1,000,000 |
| document#viewer/editor/owner | 4,000,000 |
| document#link_viewer | 250,000 |

**Implementation:**

- `data/RelationshipFileGenerator` writes deterministic relationships to a local file using fixed seed
- `data/BulkImporter` calls `BulkImportRelationshipsRequest` via direct gRPC stub (not through SDK)
- 1000 relationships per gRPC message

**REST API:**
- `POST /test/data/generate` — generate file (instance-0 only)
- `POST /test/data/import` — import to SpiceDB (instance-0 only)
- `GET /test/data/status` — entity + relationship counts

**Success:** SpiceDB ReadRelationships count >= 9,000,000 within 5 minutes of import start.

### req-4: Correctness tests (C1-C8)

Each test returns `{name, status: PASS|FAIL, durationMs, details}`.

| ID | Test |
|---|---|
| C1 | Direct grant → check returns true |
| C2 | Direct revoke → check returns false |
| C3 | Group member inheritance — user added to group → group has space access → user has access |
| C4 | 20-level folder ancestor — top-level viewer sees deepest document |
| C5 | Caveat (ip_allowlist) — write with caveat + check with matching/non-matching context |
| C6 | Time-bounded grant — `expiringIn(2 seconds)` → check before expiry true, after expiry false |
| C7 | Cross-instance: instance-0 writes → instance-1 reads with `Strong` consistency → returns true |
| C8 | Batch atomicity — batch with 100 grants commits atomically |

**REST API:**
- `POST /test/correctness/run-all` — runs C1-C8 sequentially, returns aggregated JSON

**Success:** All 8 tests PASS.

### req-5: Baseline benchmarks (B1-B5)

Each runs for 60 seconds (configurable), 3 instances concurrent, results aggregated by orchestrator.

| ID | Workload | Mix | Caches |
|---|---|---|---|
| B1 | Read-heavy | 70% check, 10% checkAll, 10% lookupSubjects, 5% lookupResources, 5% read | enabled |
| B2 | Write-heavy | 60% grant, 30% revoke, 10% deleteByFilter | n/a |
| B3 | Cross-instance consistency | instance-0 write, instance-1/2 read with `Strong` immediately after | enabled |
| B4 | Deep inheritance | check on docs at folder depth 15-20 | enabled |
| B5 | Batch | batch grant/revoke 100 items per call | n/a |

**Per scenario the runner records:**
- TPS
- Latency p50/p90/p99/p999 via HdrHistogram (microsecond resolution)
- Error count by exception type
- Cache hit rate (B1, B4)

**REST API:**
- `POST /test/bench/{B1..B5}?duration=60&threads=100`
- `GET /test/bench/results` — last run JSON

**Success:**
- B1 aggregate TPS across 3 instances >= 10,000 with cache hit rate >= 70%
- B2 aggregate TPS >= 1,000
- B3 reads after writes never return stale data
- B4 p99 < 100ms

### req-6: Resilience tests (R1-R7) — P0/P1 fix validation

Each R-test runs in this shape: setup → inject failure → run workload → observe events/state → cleanup → assert.

| ID | Validates | Failure injection | Assertion |
|---|---|---|---|
| R1 | #1 stream early-exit leak | none — drive 1000 lookupSubjects with `limit(10)` against datasets of 100K subjects | After test, `netstat` count of half-open HTTP/2 streams is ≤ 10. (Without the CloseableGrpcIterator fix this would grow unbounded.) |
| R2 | #2 cursor expiry → cache invalidation | toxiproxy `bandwidth=0` on all SpiceDB Watch ports for `(SpiceDB GC window + 30s)` | At least one `WatchCursorExpired` event captured. After event, `cache.size()` reads `0`. New writes invalidate immediately afterwards. |
| R3 | #3 Watch app-layer stall detection | toxiproxy `bandwidth=0` on Watch port for 90s only (less than cursor GC) | At least one `WatchStreamStale` event captured within 60-90s. Watch reconnects automatically after toxic removed. |
| R4 | #4 TokenStore degradation observability | Use mock `DistributedTokenStore` whose backend goes through toxiproxy → toxiproxy `down` for 60s | `TokenStoreUnavailable` event received exactly once at toxic start. `TokenStoreRecovered` event received exactly once at toxic remove. `distributedFailureCount() > 0`. |
| R5 | #5 Cache double-delete (no race poisoning) | none — 100 writer threads grant/revoke `doc-{1..50}`, 100 reader threads check same docs concurrently for 30s | For every (resource, subject, permission), the final cached state matches the last committed write within 1s. No instance reads `NO_PERMISSION` for a relation that was created earlier and never revoked. |
| R6 | #6 W-TinyLFU breaker eviction | none — write checks for 5000 unique resource types in sequence, then check `hot_type` 100 more times | Reflect `breakers` map: size ≤ 1100; `hot_type` key present. |
| R7 | #7 Close partial-failure resilience | inject: register an EventBus subscriber that throws on `ClientStopping`; call `client.close()` | `infra.scheduler.isShutdown() == true`. `infra.channel.isShutdown() == true`. Process exits cleanly within 10s. |

**REST API:**
- `POST /test/resilience/{R1..R7}` — each runs end-to-end (setup → inject → workload → cleanup → assert)
- Orchestrator scripts in `orchestrator/inject/` invoke toxiproxy CLI between phases as needed

**Success:** All 7 R-tests return `PASS`.

### req-7: Stress tests (S1-S2)

| ID | Workload |
|---|---|
| S1 | Concurrency ramp: 10/100/500/1000/2000/5000 threads, each step 30s, find the breakpoint where p99 > 1s or error rate > 5% |
| S2 | Sustained max-TPS: 5 minutes at the highest concurrency that kept p99 < 500ms in S1 |

**Output per step:** thread count, achieved TPS, p99, error rate, JVM heap, GC pauses.

**REST API:**
- `POST /test/stress/S1`
- `POST /test/stress/S2`

**Success:** S1 successfully reports a breakpoint (the run completes; finding *where* it breaks IS the result, not a failure).

### req-8: Soak test (L1)

30 minutes at sustained 200 TPS (mix from B1). Sample every 30s:

- Cache size + L1 hit rate
- Circuit breaker state per resource type
- JVM heap used / committed
- Live thread count
- Watch reconnect cumulative count
- gRPC active stream count

**Output:** time-series JSON for each sample dimension.

**REST API:**
- `POST /test/soak/L1?durationMinutes=30`

**Success:**
- Heap does not grow >2× over the run (after stabilization)
- Live thread count stays within ±20% of baseline
- No `WatchStreamStale` events except in R3
- L1 hit rate > 70% throughout

### req-9: HTML report

`report/HtmlReportGenerator` produces a single self-contained file at `cluster-test/results/report.html`:

- One `<head>` containing inline CSS + Chart.js (CDN-free, embedded JS)
- One `<body>` with sections corresponding to test categories

**Sections:**

1. **Environment** — JDK version, Docker version, image versions (CRDB, SpiceDB, Toxiproxy), host CPU/RAM, generation timestamp
2. **Executive Summary** — table: per-category PASS/FAIL counts, total wall-clock duration
3. **C-Correctness** — per-test row with status + duration + details
4. **B-Baseline** — per-scenario card with TPS, p50/p90/p99/p999 numbers, plus a Chart.js latency histogram per scenario
5. **R-Resilience** — per-test card: failure injection parameters used, expected behavior, observed behavior, captured events list, PASS/FAIL
6. **S-Stress** — line chart of TPS / p99 / error rate vs. concurrency
7. **L-Soak** — multi-line time-series chart for each sampled dimension
8. **Prometheus snapshot** — at end of run, scrape and embed: cache hit rate, breaker states, watch reconnect count, retry budget exhaustion count
9. **Appendix** — full `docker-compose.yml`, full SDK config used, JVM flags

**Implementation constraints:**
- No external network fetches at view time — all CSS, JS, fonts inlined
- Charts rendered via inline Chart.js (vendored copy in `cluster-test/src/main/resources/web/`)
- Generator reads JSON from `results/{correctness,baseline,resilience,stress,soak}.json`

**REST API:**
- `POST /test/report/generate` — produces `report.html`

**Success:** Opening `report.html` in a browser without network access shows all charts and content correctly.

### req-10: Orchestrator scripts

In `cluster-test/orchestrator/`:

| Script | Role |
|---|---|
| `run-all.sh` | Single entry point: bootstrap → all phases → report. Idempotent (cleanup on exit) |
| `start-cluster.sh` | Bring up Docker + 3 Spring Boot instances |
| `stop-cluster.sh` | Tear down |
| `run-correctness.sh` | Phase 2 |
| `run-baseline.sh` | Phase 3 |
| `run-resilience.sh` | Phase 4 — invokes `inject/*.sh` between scenarios |
| `run-stress.sh` | Phase 5 |
| `run-soak.sh` | Phase 6 |
| `inject/stall-watch.sh` | toxiproxy: bandwidth=0 on Watch port |
| `inject/restore-watch.sh` | toxiproxy: remove all toxics |
| `inject/kill-spicedb.sh <node>` | docker stop + record start time |
| `inject/restore-spicedb.sh <node>` | docker start |

All scripts are bash, must work on macOS and Linux, no external dependencies beyond `docker`, `curl`, `jq`.

**Success:** `./run-all.sh` from a clean state produces a valid `report.html` in under 60 minutes.

---

## Out of scope

- Multi-host distributed deployment (single docker-compose host only)
- Real Redis integration for `DistributedTokenStore` (R4 uses a mock backed by toxiproxy)
- Continuous CI integration (this is a manual-run benchmark, not a PR check)
- Comparison reports against historical runs (single-snapshot HTML only)
- Alerting / paging integrations
- Adding Resilience4j fault-injection metrics — toxiproxy is the source of truth

---

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| 10M relationships exceeds dev machine memory | Generator streams to disk; importer reads in chunks; CockroachDB cache=4GiB sized accordingly |
| Toxiproxy adds latency baseline | Baseline benchmarks (B1-B5) bypass toxiproxy, only resilience tests go through it |
| Test flakiness on slow hosts | Per-test timeout configurable via REST query param; S1 breakpoint detection adapts |
| Cursor expiry test (R2) takes 60+ minutes | Configure SpiceDB `--datastore-gc-window=2m` for the test environment; R2 runs in ~3 min |
| 3 Spring Boot JVMs strain dev RAM | Each instance configured with `-Xmx1g`; total ≤4 GiB JVM footprint |
