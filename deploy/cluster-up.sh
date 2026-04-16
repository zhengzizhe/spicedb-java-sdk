#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
#  生产级本地集群：3 CockroachDB + 3 SpiceDB
#
#  硬件：64 GB RAM
#  内存分配：
#    CRDB:    6GB cache + 6GB SQL memory × 3 = 36 GB
#    SpiceDB: ~2.5 GB (1G dispatch cache + 128M ns cache + runtime) × 3 = ~7.5 GB
#    OS/监控: ~6 GB
#    剩余:    ~14 GB (Go runtime, page cache)
#
#  Dispatch cluster 说明：
#    本地部署不启用 dispatch cluster (--dispatch-cluster-enabled)。
#    原因: SpiceDB dispatch-upstream-addr 不支持逗号分隔多地址格式，
#    需要 DNS 服务发现 (kubernetes:///svc:port) 或单地址负载均衡器。
#    本地 3 节点共享同一 CRDB 后端，dispatch cluster 收益有限。
#    每个节点仍有独立的 dispatch cache (1 GiB)，本地 dispatch 性能优秀。
#
#  参数来源：
#    - SpiceDB: https://authzed.com/docs/spicedb/ops/performance
#    - CRDB:    https://www.cockroachlabs.com/docs/stable/recommended-production-settings
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
echo -e "${BOLD}║   生产级集群启动: 3×CockroachDB + 3×SpiceDB                   ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# ── 0. 停止旧进程 ──
log_step "0/6" "停止旧进程"
if pgrep -f 'cockroach start' > /dev/null 2>&1 || pgrep -f 'spicedb serve' > /dev/null 2>&1; then
    pkill -f 'cockroach start' 2>/dev/null || true
    pkill -f 'spicedb serve' 2>/dev/null || true
    for i in $(seq 1 30); do
        # 检查进程是否还在（不只检查端口）
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
    # 额外等待端口释放 (进程退出后 OS 可能还需要回收端口)
    sleep 2
fi
log_ok "旧进程已停止"

# ── 1. 创建数据目录 ──
log_step "1/6" "准备数据目录"
rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR"/{crdb-1,crdb-2,crdb-3,spicedb-1,spicedb-2,spicedb-3}
log_ok "$DATA_DIR"

# ── 2. 启动 3 节点 CockroachDB 集群 ──
log_step "2/6" "启动 CockroachDB 集群 (3 节点, cache=6GB, sql-mem=6GB)"

# CockroachDB v26 要求 --listen-addr (RPC) 和 --sql-addr (SQL) 使用不同端口
#   RPC:  26457 / 26458 / 26459  (节点间通信 + join)
#   SQL:  26557 / 26558 / 26559  (客户端 SQL 连接)
#   HTTP: 8280  / 8281  / 8282   (Admin UI, 避开 ToDesk 占用的 8081)

for n in 1 2 3; do
    rpc_port=$((26456 + n))
    sql_port=$((26556 + n))
    http_port=$((8279 + n))
    if ! $CRDB_BIN start \
        --insecure \
        --store="$DATA_DIR/crdb-$n" \
        --listen-addr="localhost:$rpc_port" \
        --sql-addr="localhost:$sql_port" \
        --http-addr="localhost:$http_port" \
        --join=localhost:26457,localhost:26458,localhost:26459 \
        --cache=6GiB \
        --max-sql-memory=6GiB \
        --background > "$DATA_DIR/crdb-$n/startup.log" 2>&1; then
        log_fail "crdb-$n 启动失败 (查看: $DATA_DIR/crdb-$n/startup.log)"
        tail -3 "$DATA_DIR/crdb-$n/startup.log" 2>/dev/null | while read -r line; do log_info "  $line"; done
        exit 1
    fi
    log_ok "crdb-$n  RPC=:$rpc_port  SQL=:$sql_port  HTTP=:$http_port"
done

# 初始化集群 (init 用 RPC 端口，需等节点 listen 后才能执行)
log_info "等待 CockroachDB 节点就绪..."
for attempt in $(seq 1 30); do
    $CRDB_BIN init --insecure --host=localhost:26457 > /dev/null 2>&1 && { log_ok "集群初始化完成"; break; } || true
    # init 失败可能是「已初始化」或「未就绪」，检查 SQL 是否通
    $CRDB_BIN sql --insecure --host=localhost:26557 -e "SELECT 1;" > /dev/null 2>&1 && { log_ok "集群已初始化 (跳过)"; break; }
    sleep 2
done

# 轮询等待所有节点 SQL 可达 (最多 60 秒)
for attempt in $(seq 1 30); do
    crdb_ok=0
    for p in 26557 26558 26559; do
        $CRDB_BIN sql --insecure --host=localhost:$p -e "SELECT 1;" > /dev/null 2>&1 && crdb_ok=$((crdb_ok + 1))
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
log_step "3/6" "配置 CockroachDB 生产参数"
$CRDB_BIN sql --insecure --host=localhost:26557 --format=table -e "
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
    --datastore-conn-uri "postgresql://root@localhost:26557/spicedb?sslmode=disable" 2>&1)

# 解析迁移输出
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
log_step "5/6" "启动 SpiceDB (3 节点, dispatch-cache=1GiB)"

# 公共配置 — 生产级参数
COMMON_FLAGS=(
    --datastore-engine cockroachdb
    --datastore-conn-uri "postgresql://root@localhost:26557,localhost:26558,localhost:26559/spicedb?sslmode=disable"
    --grpc-preshared-key "$PSK"

    # ── 连接池 (生产级) ──
    --datastore-conn-pool-read-max-open 200
    --datastore-conn-pool-read-min-open 100
    --datastore-conn-pool-write-max-open 100
    --datastore-conn-pool-write-min-open 50
    --datastore-conn-pool-read-healthcheck-interval "30s"
    --datastore-conn-pool-write-healthcheck-interval "30s"
    --datastore-conn-pool-read-max-lifetime "30m"
    --datastore-conn-pool-write-max-lifetime "30m"

    # ── Dispatch 缓存 (生产级) ──
    --dispatch-cache-enabled true
    --dispatch-cache-max-cost "1GiB"
    --ns-cache-enabled true
    --ns-cache-max-cost "128MiB"

    # ── 一致性与版本控制 ──
    --datastore-revision-quantization-interval "5s"
    --datastore-gc-window "1h30m"
    --datastore-follower-read-delay-duration "4.8s"

    # ── Dispatch 并发控制 ──
    --dispatch-concurrency-limit 100

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

# 等待所有节点就绪 (轮询，最多 30 秒)
log_info "等待 SpiceDB 就绪..."
spicedb_ok=0
for attempt in $(seq 1 30); do
    spicedb_ok=0
    for p in 50051 50052 50053; do
        grpcurl -plaintext -H "authorization: Bearer $PSK" "localhost:$p" grpc.health.v1.Health/Check 2>&1 | grep -q SERVING && spicedb_ok=$((spicedb_ok + 1))
    done
    [ "$spicedb_ok" -eq 3 ] && break
    sleep 1
done

for n in 1 2 3; do
    port=$((50050 + n))
    if grpcurl -plaintext -H "authorization: Bearer $PSK" "localhost:$port" grpc.health.v1.Health/Check 2>&1 | grep -q SERVING; then
        # 从日志提取关键配置
        dispatch_cost=$(grep 'dispatch cache' "$DATA_DIR/spicedb-$n/spicedb.log" | python3 -c "import sys,json; print(json.loads(sys.stdin.read()).get('maxCost','?'))" 2>/dev/null || echo "?")
        log_ok "spicedb-$n  gRPC=:$port  dispatch-cache=$dispatch_cost"
    else
        log_fail "spicedb-$n  gRPC=:$port  (查看日志: $DATA_DIR/spicedb-$n/spicedb.log)"
    fi
done

# ── 6. 写入 Schema ──
log_step "6/6" "写入 Schema"
schema_output=$($ZED_BIN schema write "$SCHEMA_FILE" --insecure --endpoint localhost:50051 --token "$PSK" 2>&1)
type_count=$(grep -c '^definition ' "$SCHEMA_FILE" || true)
perm_count=$(grep -c 'permission ' "$SCHEMA_FILE" || true)
log_ok "$(basename "$SCHEMA_FILE")  →  $type_count 个类型, $perm_count 个权限"

# ── 完成 ──
echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║   ${GREEN}集群就绪${NC}${BOLD} — 3×CRDB + 3×SpiceDB (生产级)                      ║${NC}"
echo -e "${BOLD}╠══════════════════════════════════════════════════════════════╣${NC}"
echo -e "${BOLD}║${NC}                                                              ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}   ${CYAN}CockroachDB${NC}                                               ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     RPC:   localhost:26457, :26458, :26459                   ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     SQL:   localhost:26557, :26558, :26559                   ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     Admin: ${CYAN}http://localhost:8280${NC}                              ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     cache=6GB × 3  sql-mem=6GB × 3  gc.ttl=7200s            ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}                                                              ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}   ${CYAN}SpiceDB${NC}                                                    ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     gRPC:    localhost:50051, :50052, :50053                 ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     HTTP:    localhost:8443,  :8444,  :8445                  ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     Metrics: localhost:9090,  :9091,  :9092                  ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}                                                              ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}   ${CYAN}配置${NC}                                                       ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     dispatch-cache=1GiB  ns-cache=128MiB  concurrency=100   ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     conn-pool: read=100~200  write=50~100                   ${BOLD}║${NC}"
echo -e "${BOLD}║${NC}     quantization=5s  follower-delay=4.8s  gc-window=1h30m   ${BOLD}║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
