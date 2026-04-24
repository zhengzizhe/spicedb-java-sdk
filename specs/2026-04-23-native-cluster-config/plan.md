# Plan: native 3+3 cluster parameter table + execution order

This plan documents the full parameter choices for every process in the
cluster. Every row has four columns:

- **Default** — what the binary ships with (no flag).
- **Official recommendation** — from `context7 /authzed/docs` and
  `context7 /cockroachdb/docs`, with source cited where not obvious.
- **Our value** — chosen for this cluster.
- **Why** — one-line justification.

## CockroachDB (`cockroach start`)

Source: `cockroachdb/docs/v26.1/recommended-production-settings.md`,
`cockroachdb/docs/v26.1/secure-a-cluster.md`, `cockroach-init.md`.

| Flag | Default | Official recommendation | Our value | Why |
|---|---|---|---|---|
| `--certs-dir` | `~/.cockroach-certs` | `certs/` dir with CA-signed node.crt, client.root.crt | `$CLUSTER_ROOT/certs` | Single shared cert dir |
| `--store=path=,size=` | `cockroach-data`, no size cap | SSD-backed dir per node | `crdb-{1,2,3}`, `size=10GiB` | Host has 118 GiB free — 3×10 = 30 GiB disk budget |
| `--listen-addr` | `:26257` | SQL + inter-node | `localhost:{26257,26258,26259}` | 3 nodes on same host → unique ports |
| `--advertise-addr` | = `--listen-addr` | NAT scenarios need it explicit | same as `--listen-addr` | Same host, no NAT, still set explicitly for clarity |
| `--http-addr` | `:8080` | DB Console | `localhost:{8080,8081,8082}` | Per-node admin UI, no conflict |
| `--join` | empty | All nodes' listen addrs | `localhost:26257,26258,26259` | Every node joins the same 3-peer list |
| `--cache` | `128MiB` | `.25` ~ `.35` of RAM (**single tenant**) | `.08` | 3 tenants share 64 GiB → .08 each; formula check below |
| `--max-sql-memory` | `128MiB` | `.25` | `.08` | Same reasoning |
| Memory formula | — | `(2 × max-sql-memory) + cache ≤ 80% RAM` | 3 × (2 × .08 + .08) = .72 ≤ .80 ✓ | Per-host aggregate, safe |
| `--cluster-name` | empty | Recommended when running multiple clusters on one net | `spicedb-local` | Defends against accidentally joining another cluster |
| `--log-dir` | `<store>/logs` | Separate dir OK | `$LOGS/crdb-$N` | Centralised under cluster logs |
| `--locality` | empty | Multi-region: required | not set | Single host, single AZ — no meaningful topology |
| `--background` | off | OK for operator use, bad for orchestration | **not used** | Use `nohup ... &` + pidfile for uniform pattern with SpiceDB |

**Bootstrap** (exactly once per cluster lifetime):
```
cockroach init --certs-dir=$CERTS --host=localhost:26257 --cluster-name=spicedb-local
```

**Post-init SQL** (idempotent):
```sql
CREATE DATABASE IF NOT EXISTS spicedb;
CREATE USER     IF NOT EXISTS spicedb;   -- cert auth, no password
GRANT ALL ON DATABASE spicedb TO spicedb;
```

## SpiceDB (`spicedb serve`)

Source: `authzed/docs/pages/spicedb/concepts/commands.mdx`,
`authzed/docs/pages/best-practices/index.mdx`.

| Flag | Default | Official recommendation | Our value | Why |
|---|---|---|---|---|
| `--datastore-engine` | `memory` | `cockroachdb` for prod | `cockroachdb` | Requirement |
| `--datastore-conn-uri` | — | postgres URI with sslmode=verify-full + client cert | multi-host: `postgresql://spicedb@localhost:26257,26258,26259/spicedb?sslmode=verify-full&sslrootcert=...&sslcert=...&sslkey=...` | 3-CRDB HA + mutual TLS |
| `--datastore-connection-balancing` | `true` | Leave on for CRDB driver | `true` | Official default; load-spreads |
| `--datastore-conn-pool-read-max-open` | varies | **30** | `30` | Official best-practices |
| `--datastore-conn-pool-read-min-open` | varies | **15** | `15` | Official best-practices |
| `--datastore-conn-pool-write-max-open` | varies | **20** | `20` | Official best-practices |
| `--datastore-conn-pool-write-min-open` | varies | **10** | `10` | Official best-practices |
| `--grpc-addr` | `:50051` | — | `:{50051,50061,50071}` | Per-node gRPC |
| `--grpc-tls-cert-path` + `--grpc-tls-key-path` | empty = plaintext | Set both for TLS | `$CERTS/spicedb/tls.{crt,key}` | Operator required TLS 全开 |
| `--grpc-preshared-key` | — | **long random secret** | 48-byte base64 from `config/preshared-key` | SDK client auth |
| `--dispatch-cluster-addr` | `:50053` | — | `:{50053,50063,50073}` | Listener bound for future expansion |
| `--dispatch-upstream-addr` | empty (local) | `dns:///...:50053` in prod multi-host | **empty** (local dispatch) | Single host — see spec.md req-4 |
| `--dispatch-upstream-timeout` | `1m` | Keep | not set (use default) | 1m is sane |
| `--http-addr` | `:50055` | — | `:{50055,50065,50075}` | Per-node HTTP gateway |
| `--http-enabled` | `false` | true for health checks | `true` | enables /healthz etc. |
| `--metrics-addr` | `:9090` | — | `:{9090,9091,9092}` | Prometheus scrape target |
| `--log-level` | `info` | `info` for prod | `info` | Default |

Schema migration, run once per datastore lifetime:
```
spicedb datastore migrate head \
  --datastore-engine=cockroachdb \
  --datastore-conn-uri="postgresql://spicedb@localhost:26257/spicedb?sslmode=verify-full&sslrootcert=...&sslcert=...&sslkey=..."
```

## TLS certificate inventory

Shared CA under `$CA_KEY` (mode 0600, dir 0700). Certs in `$CERTS`.

| File | Purpose | Generated by |
|---|---|---|
| `ca.crt` / `../safe/ca.key` | Internal CA | `cockroach cert create-ca` |
| `node.crt` + `node.key` | CRDB node (shared across 3) | `cockroach cert create-node localhost 127.0.0.1 ::1` |
| `client.root.crt` + `.key` | CRDB root client (for admin) | `cockroach cert create-client root` |
| `client.spicedb.crt` + `.key` | CRDB spicedb client (SpiceDB → CRDB) | `cockroach cert create-client spicedb` |
| `spicedb/tls.crt` + `tls.key` | SpiceDB gRPC server (shared across 3) | openssl, signed by same CA, SAN=localhost+127.0.0.1+::1 |

All validity defaults: CA 10y, node 5y, clients 5y, SpiceDB cert 365d
(set explicitly in openssl). Rotation = re-run `01-gen-certs.sh` (wipes
and re-signs).

## Execution order

```
01-gen-certs.sh         (1×, ~5s)
02-start-crdb.sh        (1×, 3 nohup nodes + pidfiles)
03-init-cluster.sh      (1× per cluster lifetime — idempotent)
04-spicedb-migrate.sh   (1× per datastore lifetime — idempotent)
05-start-spicedb.sh     (1×, 3 nohup nodes + pidfiles)
health.sh               (any time — port listeners + cockroach node status)
stop-all.sh             (any time — graceful TERM, fallback KILL)
wipe.sh --yes           (destructive: stop + rm data + rm logs, keep certs)
```

## Not wired

- **Prometheus scrape / Grafana dashboards** — metrics endpoints are
  up at `:9090`/`:9091`/`:9092` (spicedb) and `:8080`/`:8081`/`:8082`
  (cockroach DB Console, which exposes its own metrics). Hook up
  externally when needed. ADR-free decision — skip until there's an
  actual consumer.
- **launchd / systemd auto-start** — scripts are operator-invoked. A
  `.plist` can be authored later; pid files already align with that
  pattern.
- **Cross-node dispatch with consistent-hashring** — see req-4; not
  applicable on single host.
- **CRDB enterprise license** — `v26.1.1 CCL` is installed but no
  `SET CLUSTER SETTING enterprise.license` runs, so the cluster operates
  in Core mode.
