# Native-host SpiceDB + CockroachDB production-grade cluster

**Date**: 2026-04-23
**Scope**: 3 × SpiceDB + 3 × CockroachDB running as native binaries on a
single macOS host, no containers, TLS everywhere, preshared-key auth, cert
auth between SpiceDB and CRDB. Target: AuthX SDK 2.0.1 cluster validation
(backlog `cluster-test-1`) and future benchmark runs.

## Why

- ADR 2026-04-18 removed the client-side L1 cache on the premise that
  SpiceDB's server-side dispatch cache handles decision caching. The
  prior benchmark harness (`deploy/docker-compose.yml` + `cluster-test/`
  Gradle module) was deleted in commit `6af9f2c`. We need a cluster shape
  that (a) survives that removal and (b) matches the operator's stated
  preference of native binaries over containers.
- Docker Desktop is not running on the host and the operator does not
  want to depend on it. Binaries for both CockroachDB (`/usr/local/bin/cockroach`,
  v26.1.1 CCL) and SpiceDB (`/usr/local/bin/spicedb`, 1.51.0) are already
  installed via Homebrew.
- The existing `/Users/cses-38/spicedb-cluster/` directory tree already
  anticipates a 3+3 topology but had no startup scripts, no configs, and
  stale 2026-04-17 data (wiped on 2026-04-23 as part of this spec).

## Non-goals

- Production deploy to a real multi-host fleet. This spec produces a
  reference-quality single-host cluster; the parameter choices are
  justified for production and explicitly annotated where a real fleet
  would differ.
- Kubernetes / container orchestration. Ruled out per operator.
- The CockroachDB CCL enterprise feature set (backup to cloud storage,
  CDC, follower reads, multi-region survivability, row-TTL). Core is
  sufficient for SpiceDB's workload.

## Requirements

### req-1: host layout + directory skeleton

Under `/Users/cses-38/spicedb-cluster/`:

```
certs/            — CockroachDB node + client certs (cockroach cert output)
certs/spicedb/    — SpiceDB gRPC server TLS (openssl, same CA)
safe/             — CA private key, mode 0700 dir, 0600 key
config/
  preshared-key   — 48 random bytes base64, mode 0600 (generated once)
scripts/          — nine shell scripts (00-common.sh + 01..05 + health/stop/wipe)
logs/             — stdout/stderr capture, pid files
crdb-{1,2,3}/     — CockroachDB node data, 10GiB cap each
spicedb-{1,2,3}/  — SpiceDB working directory (logs)
README.md         — runbook
```

### req-2: CockroachDB 3-node cluster

- Same CA-signed node certificate on all 3 (all listen on localhost).
- TLS on every port (SQL + HTTP admin + inter-node RPC). `--insecure`
  is never used.
- Listen / advertise / HTTP ports: 26257/26258/26259 + 8080/8081/8082.
- Store size capped at 10GiB per node (`--store=...,size=10GiB`) because
  the host has only ~118 GiB free.
- Memory: `--cache=.08 --max-sql-memory=.08` on each of the 3 nodes.
  Satisfies the official constraint `(2 × max-sql-memory) + cache ≤ 80%
  RAM` when summed across all 3 tenants on the same host:
  `3 × (2 × .08 + .08) = .72 ≤ .80`. Single-tenant deployment would use
  the recommended `.25`.
- Bootstrap: `cockroach init` runs once against node 1 after all three
  are up. Idempotent in the startup script — "already initialized" is
  treated as success.
- Database + user: `CREATE DATABASE spicedb`, `CREATE USER spicedb`,
  `GRANT ALL ON DATABASE spicedb TO spicedb`. User authenticates via
  client cert (`client.spicedb.crt` / `.key`).

### req-3: SpiceDB 3-node cluster

- Engine: `cockroachdb`, connection URI with `sslmode=verify-full` and
  three host candidates (`localhost:26257,26258,26259`) so
  `--datastore-connection-balancing=true` (CRDB driver default) spreads
  SQL load across the three CRDB nodes.
- Pool sizing: official best-practices values — read pool 15–30,
  write pool 10–20 per SpiceDB node. (Total pool across 3 SpiceDB
  nodes: 45–90 read / 30–60 write CRDB-side connections — well under
  CRDB's default `sql.max_open_connections` ceiling.)
- gRPC server TLS: `--grpc-tls-cert-path` + `--grpc-tls-key-path`,
  shared cert across all 3 SpiceDB nodes, same CA as CRDB.
- Preshared key: single 48-byte base64 string in
  `config/preshared-key`, file mode 0600. All 3 nodes share it; clients
  present it as a bearer token.
- Ports per node:

  | Node | grpc | dispatch | http | metrics |
  |---|---|---|---|---|
  | spicedb-1 | :50051 | :50053 | :50055 | :9090 |
  | spicedb-2 | :50061 | :50063 | :50065 | :9091 |
  | spicedb-3 | :50071 | :50073 | :50075 | :9092 |

- Dispatch: **each node dispatches locally** (no
  `--dispatch-upstream-addr`). Rationale in req-4 below.

### req-4: dispatch topology — deliberately local

SpiceDB's `--dispatch-upstream-addr` takes a single gRPC URI
(`grpc.Dial` target) and uses the `ConsistentHashringBuilder` registered
globally to load-balance across the resolved addresses. Verified in
`spicedb-src/pkg/cmd/server/server.go:54-299` and
`spicedb-src/internal/dispatch/combined/combined.go:260`. gRPC-Go's
built-in resolvers are `dns://`, `kuberesolver://` (registered by
SpiceDB's own `main.go`), and `unix://` — **no built-in "static
comma-separated peer list" resolver**.

For a 3-node cluster on the **same machine**, cross-node dispatch buys
nothing:

- The dispatch cache sits in the same process RAM on each node. Three
  local caches on one machine are worse (3× duplicate entries) than one
  process-local cache per node.
- There is no network partition concern between peers on localhost.
- The hashring's value is distributing work across machines; there's
  one machine here.

Therefore: leave `--dispatch-upstream-addr` empty. Each SpiceDB node
handles its own dispatches locally. The `--dispatch-cluster-addr` port
is still allocated (`:50053`/`:50063`/`:50073`) so the listener is bound
and the flag is set correctly for future expansion, but nothing dials
into it.

On a real multi-host deployment, re-enable cross-node dispatch by
setting on each node:
```
--dispatch-upstream-addr=dns:///spicedb.headless.svc.cluster.local:50053
```
with a k8s headless Service or a DNS A-record list. This spec does not
ship that config.

### req-5: TLS posture

| Hop | Encryption | Auth |
|---|---|---|
| SDK client → SpiceDB gRPC (:50051) | TLS (server cert) | preshared-key bearer token |
| SpiceDB → CRDB SQL (:26257/8/9) | TLS, `verify-full` | client cert (`spicedb` user) |
| CRDB ↔ CRDB (Raft, SQL, HTTP) | mTLS | node cert |
| SpiceDB ↔ SpiceDB (dispatch) | N/A (local dispatch only, see req-4) | N/A |

No `--insecure` or plaintext anywhere. Operator requested "TLS 全开";
the only omission is dispatch-peer TLS which is vacuously satisfied
when there are no peer connections.

### req-6: no containers

Every process runs as a native OS user process under `cses-38`:
`cockroach start ...` and `spicedb serve ...`, backgrounded via `nohup
... > log 2>&1 &`, pid tracked in `logs/*.pid`. No Docker, no
Kubernetes, no launchd plists (can be added later).

### req-7: idempotent operator workflow

The operator should be able to:
1. Wipe state and start over without special knowledge:
   `./scripts/wipe.sh --yes` then re-run 01–05.
2. Stop cleanly at any time: `./scripts/stop-all.sh` (graceful TERM,
   fallback KILL after 10s, pid-file cleanup).
3. Check cluster health at a glance: `./scripts/health.sh` —
   port listeners + `cockroach node status` + optional grpcurl schema
   probe.

## Out of scope for this spec

- Backup / restore (CockroachDB CCL-only; if desired later, switch to
  `BACKUP TO 's3://...'` or use `cockroach debug zip` for ad-hoc).
- Prometheus / Grafana stack (metrics endpoints are exposed on
  `:9090`/`:9091`/`:9092` for spicedb and `:8080/1/2` for crdb; hook up
  a Prometheus scrape config separately when needed).
- launchd auto-start at boot.
- TLS certificate rotation (certs valid 365 days from generation;
  regenerate annually via `01-gen-certs.sh`, which wipes and re-signs).
- Benchmark harness — a separate follow-up spec drives workload against
  this cluster and produces the report that closes `cluster-test-1`.
