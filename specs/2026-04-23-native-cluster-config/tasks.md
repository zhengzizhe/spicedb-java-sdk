# Tasks / acceptance

Operator-driven checklist. Each task is a single command and a
pass/fail check. Complete top to bottom.

## Preflight

- [ ] Host has ≥ 30 GiB free on the data volume
      (`df -h /Users/cses-38 | awk 'NR==2 {print $4}'`)
- [ ] No cockroach or spicedb process currently running
      (`pgrep -f 'cockroach start|spicedb serve'` returns nothing)
- [ ] `/Users/cses-38/spicedb-cluster/config/preshared-key` exists,
      mode 0600, non-empty

## Bootstrap

- [ ] **T1** — `./scripts/01-gen-certs.sh` succeeds
      Check: `ls $CERTS` shows `ca.crt node.crt node.key
      client.root.crt client.root.key client.spicedb.crt
      client.spicedb.key` and `$CERTS/spicedb/tls.{crt,key}`
- [ ] **T2** — `./scripts/02-start-crdb.sh` succeeds
      Check: `pgrep -af 'cockroach start'` shows 3 processes; 3 pid
      files in `$LOGS`
- [ ] **T3** — `./scripts/03-init-cluster.sh` succeeds
      Check: stdout ends with a `cockroach node status` table listing
      3 healthy nodes
- [ ] **T4** — `./scripts/04-spicedb-migrate.sh` succeeds
      Check: exit 0, no migration errors in stderr
- [ ] **T5** — `./scripts/05-start-spicedb.sh` succeeds
      Check: `pgrep -af 'spicedb serve'` shows 3 processes

## Smoke

- [ ] **T6** — `./scripts/health.sh` — all port checks green; CRDB
      node status shows 3 `is_available=true`
- [ ] **T7** — schema read via grpcurl on each SpiceDB node
      ```sh
      PSK=$(cat config/preshared-key)
      for port in 50051 50061 50071; do
        grpcurl -cacert certs/ca.crt -H "authorization: Bearer $PSK" \
          localhost:$port authzed.api.v1.SchemaService/ReadSchema
      done
      ```
      Check: all three return the same (empty) schema without error

- [ ] **T8** — write + read from the AuthX SDK against each SpiceDB
      node (via test-app or `grpcurl WriteRelationships` then
      `CheckPermission`). Verifies the TLS + preshared-key handshake
      works end-to-end.

## Teardown verification

- [ ] **T9** — `./scripts/stop-all.sh` succeeds; `pgrep -af
      'cockroach start|spicedb serve'` returns nothing; all pid files
      removed from `$LOGS`.
- [ ] **T10** — `./scripts/wipe.sh --yes` leaves `certs/`, `safe/`,
      and `config/` intact but clears every data dir and the logs dir.
- [ ] **T11** — Full restart cycle after wipe: T1 → T8 all green
      again, no manual patching needed.

## Exit conditions

All T1–T11 passing closes this spec. Follow-up spec (benchmark harness)
drives workload against this cluster and produces the report that
closes backlog item `cluster-test-1`.
