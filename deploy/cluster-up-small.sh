#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
#  开发级本地集群（缩水版）：3 CockroachDB + 3 SpiceDB
#
#  目标硬件：32–36 GB RAM Mac（非 64 GB）
#  内存预算：
#    CRDB:    2 GiB cache + 3 GiB SQL-mem × 3 = 15 GB
#    SpiceDB: ~1.5 GB (512 MiB dispatch + 64 MiB ns + runtime) × 3 ≈ 4.5 GB
#    总计:    ~19.5 GB（vs 原脚本 ~43 GB）
#
#  调整历史：
#    v1 (2026-04-10): cache/sql-mem 各 1 GiB → 压测时 CRDB SQL 内存池打爆
#                     (memory budget exceeded: 1073741824 bytes in budget)
#    v2 (2026-04-10): cache 2 GiB, sql-mem 3 GiB 各 3 节点 (共 15 GB)
#
#  与 cluster-up.sh 的区别：
#    1) CRDB cache 从 6 GiB 降到 2 GiB, sql-mem 从 6 GiB 降到 3 GiB
#    2) dispatch-cache 从 1 GiB 降到 512 MiB
#    3) ns-cache 从 128 MiB 降到 64 MiB
#    4) 连接池 read=20~60 (原 100~200), write=10~30 (原 50~100)
#    5) dispatch-concurrency-limit 从 100 降到 50
#    6) 其他参数（一致性、rangefeed、closed_ts、GC、端口）保持不变
#
#  何时用它：
#    - 本机内存 < 48 GB
#    - 只做功能验证 / 冒烟测试 / 小流量基准（不做 10M 数据集压测）
#    - 需要生产级基准时改用 cluster-up.sh（要求 64 GB+ RAM）
# ═══════════════════════════════════════════════════════════════

DATA_DIR="$HOME/spicedb-cluster"
CRDB_BIN="cockroach"
SPICEDB_BIN="spicedb"
ZED_BIN="zed"
SCHEMA_FILE="$(cd "$(dirname "$0")" && pwd)/schema-v2.zed"
PSK="testkey"

# 颜色
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

log_step()  { echo -e "${BOLD}${CYAN}[$1]${NC} $2"; }
log_ok()    { echo -e "  ${GREEN}✓${NC} $1"; }
log_fail()  { echo -e "  ${RED}✗${NC} $1"; }
log_warn()  { echo -e "  ${YELLOW}⚠${NC} $1"; }
log_info()  { echo -e "  ${NC}$1"; }

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║   开发集群启动 (缩水版 v2): 3×CockroachDB + 3×SpiceDB         ║${NC}"
echo -e "${BOLD}║   内存预算 ~19.5 GB  (32–36 GB Mac 专用)                      ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# ── 0. 停止旧进程 ──
log_step "0/6" "停止旧进程"
if pgrep -f 'cockroach start' > /dev/null 2>&1 || pgrep -f 'spicedb serve' > /dev/null 2>&1; then
    pkill -f 'cockroach start' 2>/dev/null || true
    pkill -f 'spicedb serve' 2>/dev/null || true
    for i in $(seq 1 30); do
        if ! pgrep -f 'cockroach start' > /dev/null 2>&1 && \
           ! pgrep -f 'spicedb serve' > /dev/null 2>&1; then
            break
        fi
        if [ "$i" -eq 8 ]; then
            log_warn "进程未退出，强制 kill"
            pkill -9 -f 'cockroach' 2>/dev/null || true
            pkill -9 -f 'spicedb' 2>/dev/null || true
        fi
        sleep 1
    done
    sleep 2
fi
log_ok "旧进程已停止"

# ── 1. 创建数据目录 ──
log_step "1/6" "准备数据目录"
rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR"/{crdb-1,crdb-2,crdb-3,spicedb-1,spicedb-2,spicedb-3}
log_ok "$DATA_DIR"

# ── 2. 启动 3 节点 CockroachDB 集群 ──
log_step "2/6" "启动 CockroachDB 集群 (3 节点, cache=2GiB, sql-mem=3GiB)"

for n in 1 2 3; do
    rpc_port=$((26456 + n))
    sql_port=$((26556 + n))
    http_port=$((8279 + n))
    # NOTE: bind explicitly to 127.0.0.1 (IPv4) — NOT "localhost" — to avoid
    # IPv6/IPv4 dual-stack conflicts with other Docker containers on the host
    # that bind to 0.0.0.0:26557 (which on macOS dual-stacks include ::1).
    # See README "Watch Stream Observability" / port-conflict notes.
    if ! $CRDB_BIN start \
        --insecure \
        --store="$DATA_DIR/crdb-$n" \
        --listen-addr="127.0.0.1:$rpc_port" \
        --sql-addr="127.0.0.1:$sql_port" \
        --http-addr="127.0.0.1:$http_port" \
        --join=127.0.0.1:26457,127.0.0.1:26458,127.0.0.1:26459 \
        --cache=2GiB \
        --max-sql-memory=3GiB \
        --background > "$DATA_DIR/crdb-$n/startup.log" 2>&1; then
        log_fail "crdb-$n 启动失败 (查看: $DATA_DIR/crdb-$n/startup.log)"
        tail -3 "$DATA_DIR/crdb-$n/startup.log" 2>/dev/null | while read -r line; do log_info "  $line"; done
        exit 1
    fi
    log_ok "crdb-$n  RPC=:$rpc_port  SQL=:$sql_port  HTTP=:$http_port"
done

# 初始化集群
log_info "等待 CockroachDB 节点就绪..."
for attempt in $(seq 1 30); do
    $CRDB_BIN init --insecure --host=127.0.0.1:26457 > /dev/null 2>&1 && { log_ok "集群初始化完成"; break; } || true
    $CRDB_BIN sql --insecure --host=127.0.0.1:26557 -e "SELECT 1;" > /dev/null 2>&1 && { log_ok "集群已初始化 (跳过)"; break; }
    sleep 2
done

# 轮询等待所有节点 SQL 可达
for attempt in $(seq 1 30); do
    crdb_ok=0
    for p in 26557 26558 26559; do
        $CRDB_BIN sql --insecure --host=127.0.0.1:$p -e "SELECT 1;" > /dev/null 2>&1 && crdb_ok=$((crdb_ok + 1))
    done
    [ "$crdb_ok" -eq 3 ] && break
    sleep 2
done
if [ "$crdb_ok" -eq 3 ]; then
    log_ok "3/3 节点 SQL 连通"
else
    log_fail "仅 $crdb_ok/3 节点连通 (查看日志: $DATA_DIR/crdb-*/startup.log)"
    exit 1
fi

# ── 3. 配置 CockroachDB 生产参数 ──
log_step "3/6" "配置 CockroachDB 参数"
$CRDB_BIN sql --insecure --host=127.0.0.1:26557 --format=table -e "
CREATE DATABASE IF NOT EXISTS spicedb;
SET CLUSTER SETTING kv.rangefeed.enabled = true;
SET CLUSTER SETTING kv.range_split.by_load.enabled = true;
SET CLUSTER SETTING kv.allocator.load_based_rebalancing = 'leases and replicas';
SET CLUSTER SETTING sql.stats.automatic_collection.enabled = true;
SET CLUSTER SETTING server.time_until_store_dead = '1m15s';
SET CLUSTER SETTING kv.closed_timestamp.target_duration = '3s';
SET CLUSTER SETTING kv.closed_timestamp.side_transport_interval = '200ms';
SET CLUSTER SETTING kv.transaction.max_intents_bytes = 4194304;
SET CLUSTER SETTING kv.transaction.max_refresh_spans_bytes = 4194304;
ALTER DATABASE spicedb CONFIGURE ZONE USING gc.ttlseconds = 7200;
SET CLUSTER SETTING sql.defaults.idle_in_transaction_session_timeout = '60s';
" > /dev/null 2>&1

log_ok "rangefeed=on  load_rebalancing=leases+replicas"
log_ok "closed_ts=3s  gc.ttlseconds=7200"
log_ok "idle_in_tx_timeout=60s  store_dead=1m15s"

# ── 4. SpiceDB 数据库迁移 ──
log_step "4/6" "SpiceDB 数据库迁移"
migrate_output=$($SPICEDB_BIN migrate head \
    --datastore-engine cockroachdb \
    --datastore-conn-uri "postgresql://root@127.0.0.1:26557/spicedb?sslmode=disable" 2>&1)

migrate_count=$(echo "$migrate_output" | grep -c '"message":"migrating"' || true)
if [ "$migrate_count" -gt 0 ]; then
    log_ok "执行了 $migrate_count 个迁移"
    echo "$migrate_output" | grep '"message":"migrating"' | while read -r line; do
        from=$(echo "$line" | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['from'])" 2>/dev/null || echo "?")
        to=$(echo "$line" | python3 -c "import sys,json; print(json.loads(sys.stdin.read())['to'])" 2>/dev/null || echo "?")
        log_info "  $from → $to"
    done
else
    log_ok "数据库已是最新版本 (无需迁移)"
fi

# ── 5. 启动 3 节点 SpiceDB ──
log_step "5/6" "启动 SpiceDB (3 节点, dispatch-cache=512MiB)"

COMMON_FLAGS=(
    --datastore-engine cockroachdb
    --datastore-conn-uri "postgresql://root@127.0.0.1:26557,127.0.0.1:26558,127.0.0.1:26559/spicedb?sslmode=disable"
    --grpc-preshared-key "$PSK"

    # ── 连接池 (缩水) ──
    --datastore-conn-pool-read-max-open 60
    --datastore-conn-pool-read-min-open 20
    --datastore-conn-pool-write-max-open 30
    --datastore-conn-pool-write-min-open 10
    --datastore-conn-pool-read-healthcheck-interval "30s"
    --datastore-conn-pool-write-healthcheck-interval "30s"
    --datastore-conn-pool-read-max-lifetime "30m"
    --datastore-conn-pool-write-max-lifetime "30m"

    # ── Dispatch 缓存 (缩水) ──
    --dispatch-cache-enabled true
    --dispatch-cache-max-cost "512MiB"
    --ns-cache-enabled true
    --ns-cache-max-cost "64MiB"

    # ── 一致性与版本控制 ──
    --datastore-revision-quantization-interval "5s"
    --datastore-gc-window "1h30m"
    --datastore-follower-read-delay-duration "4.8s"

    # ── Dispatch 并发控制 (缩水) ──
    --dispatch-concurrency-limit 50

    # ── gRPC ──
    --grpc-max-conn-age "30s"

    # ── Schema 缓存 ──
    --enable-experimental-watchable-schema-cache

    # ── 事务策略 ──
    --datastore-tx-overlap-strategy insecure

    # ── 其他 ──
    --telemetry-endpoint ""
    --log-level info
)

for n in 1 2 3; do
    grpc_port=$((50050 + n))
    http_port=$((8442 + n))
    metrics_port=$((9089 + n))
    $SPICEDB_BIN serve \
        "${COMMON_FLAGS[@]}" \
        --grpc-addr ":$grpc_port" \
        --http-addr ":$http_port" \
        --metrics-addr ":$metrics_port" \
        > "$DATA_DIR/spicedb-$n/spicedb.log" 2>&1 &
done

log_info "等待 SpiceDB 就绪..."
spicedb_ok=0
for attempt in $(seq 1 30); do
    spicedb_ok=0
    for p in 50051 50052 50053; do
        grpcurl -plaintext -H "authorization: Bearer $PSK" "127.0.0.1:$p" grpc.health.v1.Health/Check 2>&1 | grep -q SERVING && spicedb_ok=$((spicedb_ok + 1))
    done
    [ "$spicedb_ok" -eq 3 ] && break
    sleep 1
done

for n in 1 2 3; do
    port=$((50050 + n))
    if grpcurl -plaintext -H "authorization: Bearer $PSK" "127.0.0.1:$port" grpc.health.v1.Health/Check 2>&1 | grep -q SERVING; then
        dispatch_cost=$(grep 'dispatch cache' "$DATA_DIR/spicedb-$n/spicedb.log" | python3 -c "import sys,json; print(json.loads(sys.stdin.read()).get('maxCost','?'))" 2>/dev/null || echo "?")
        log_ok "spicedb-$n  gRPC=:$port  dispatch-cache=$dispatch_cost"
    else
        log_fail "spicedb-$n  gRPC=:$port  (查看日志: $DATA_DIR/spicedb-$n/spicedb.log)"
    fi
done

# ── 6. 写入 Schema ──
log_step "6/6" "写入 Schema"
schema_output=$($ZED_BIN schema write "$SCHEMA_FILE" --insecure --endpoint 127.0.0.1:50051 --token "$PSK" 2>&1)
type_count=$(grep -c '^definition ' "$SCHEMA_FILE" || true)
perm_count=$(grep -c 'permission ' "$SCHEMA_FILE" || true)
log_ok "$(basename "$SCHEMA_FILE")  →  $type_count 个类型, $perm_count 个权限"

# ── 完成 ──
echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║   ${GREEN}集群就绪${NC}${BOLD} — 3×CRDB + 3×SpiceDB (缩水版 v2 ~19.5 GB)         ║${NC}"
echo -e "${BOLD}╠══════════════════════════════════════════════════════════════╣${NC}"
echo -e "${BOLD}║${NC}                                                              ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}   ${CYAN}CockroachDB${NC}                                               ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     RPC:   localhost:26457, :26458, :26459                   ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     SQL:   localhost:26557, :26558, :26559                   ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     Admin: ${CYAN}http://localhost:8280${NC}                              ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     cache=2GiB × 3  sql-mem=3GiB × 3  gc.ttl=7200s          ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}                                                              ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}   ${CYAN}SpiceDB${NC}                                                    ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     gRPC:    localhost:50051, :50052, :50053                 ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     HTTP:    localhost:8443,  :8444,  :8445                  ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     Metrics: localhost:9090,  :9091,  :9092                  ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}                                                              ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}   ${CYAN}配置 (缩水)${NC}                                                ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     dispatch-cache=512MiB  ns-cache=64MiB  concurrency=50   ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     conn-pool: read=20~60  write=10~30                      ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     quantization=5s  follower-delay=4.8s  gc-window=1h30m   ${BOLD}║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
