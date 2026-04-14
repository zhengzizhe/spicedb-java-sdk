# cluster-test

Production-grade cluster stress test harness for the AuthX SpiceDB SDK.

Validates **correctness**, **baseline performance**, **P0/P1 resilience fixes**, **peak capacity**, and **long-run stability** against a 3 CockroachDB + 3 SpiceDB + 3 Spring Boot instance cluster, then produces a self-contained HTML report.

## Prerequisites

- Docker + Docker Compose (Desktop or daemon)
- JDK 21
- `curl`, `jq`
- ~16 GB free RAM (3 JVMs at 1 GB + Docker for CRDB/SpiceDB)

## Quick start — full run (~60 minutes)

```bash
./orchestrator/run-all.sh
```

The script bootstraps the cluster, generates and imports 10M relationships, runs all 6 phases (correctness, baseline, resilience, stress, soak), and writes `cluster-test/results/report.html`. Open it in any browser — no network needed.

## Manual phase execution

```bash
# Bring up the cluster
./orchestrator/start-cluster.sh

# Generate + import data (small dataset for smoke testing)
curl -X POST 'http://localhost:8091/test/data/generate?small=true'
curl -X POST  http://localhost:8091/test/data/import

# Run individual phases
./orchestrator/run-correctness.sh
DURATION=30 THREADS=50 ./orchestrator/run-baseline.sh
./orchestrator/run-resilience.sh
./orchestrator/run-stress.sh
DURATION_MIN=5 ./orchestrator/run-soak.sh

# Generate the HTML report
curl -X POST http://localhost:8091/test/report/generate

# Tear down
./orchestrator/stop-cluster.sh
```

## Failure injection (manual)

```bash
# Stall all SpiceDB Watch streams (bandwidth=0)
./orchestrator/inject/stall-watch.sh
# ... reproduce something ...
./orchestrator/inject/restore-watch.sh

# Kill a SpiceDB node
./orchestrator/inject/kill-spicedb.sh 1
# ... observe failover ...
./orchestrator/inject/restore-spicedb.sh 1
```

## Test catalogue

| Category | IDs | What it validates |
|---|---|---|
| Correctness | C1-C8 | Grant/revoke, group inheritance, deep folder, caveat, expiration, cross-instance, batch atomicity |
| Baseline | B1-B5 | Read mix, write mix, cross-instance consistency, deep inheritance, batch |
| Resilience | R1-R7 | The 7 P0/P1 SDK risk fixes from the 2026-04-14 audit |
| Stress | S1-S2 | Concurrency ramp + sustained max-TPS |
| Soak | L1 | 30-min sustained run for memory/thread leak detection |

See `specs/2026-04-14-cluster-stress-test/spec.md` for the full requirement table.

## REST API

| Method | Path | Notes |
|---|---|---|
| POST | `/test/data/generate?small=true` | leader-only |
| POST | `/test/data/import` | leader-only |
| POST | `/test/correctness/run-all` | runs C1-C8 |
| POST | `/test/bench/{B1\|B2\|B3\|B4\|B5}?threads=100&duration=60` | per-instance |
| POST | `/test/resilience/{R1\|R2\|R3\|R4\|R5\|R6\|R7}` | leader-only for some |
| POST | `/test/stress/{S1\|S2}` | leader-only |
| POST | `/test/soak/L1?durationMinutes=30` | leader-only |
| POST | `/test/report/generate` | reads results/, writes report.html |
| GET  | `/actuator/health` | Spring Boot health |
| GET  | `/actuator/prometheus` | Prometheus scrape endpoint |

## Per-instance config

Differentiation via env vars at startup:

```bash
NODE_INDEX=0 SERVER_PORT=8091 RESULTS_DIR=./results java -jar build/libs/cluster-test-*.jar
NODE_INDEX=1 SERVER_PORT=8092 RESULTS_DIR=./results java -jar build/libs/cluster-test-*.jar
NODE_INDEX=2 SERVER_PORT=8093 RESULTS_DIR=./results java -jar build/libs/cluster-test-*.jar
```

`NODE_INDEX=0` is the leader — it owns data generation, import, and the report aggregation.

## Results layout

```
cluster-test/results/
├── relations.txt              # generated relationships (leader)
├── instance-0/
│   ├── correctness.json
│   ├── baseline.json
│   ├── resilience.json
│   ├── stress.json
│   └── soak.json
├── instance-1/...
├── instance-2/...
├── instance-{0,1,2}.log       # bootRun logs
└── report.html                # final aggregated report
```
