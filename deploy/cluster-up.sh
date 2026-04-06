#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
#  生产级本地集群：3 CockroachDB + 2 SpiceDB
# ═══════════════════════════════════════════════════════════════

DATA_DIR="$HOME/spicedb-cluster"
CRDB_BIN="cockroach"
SPICEDB_BIN="spicedb"
ZED_BIN="zed"
SCHEMA_FILE="$(cd "$(dirname "$0")" && pwd)/schema-v2.zed"
PSK="testkey"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║   生产级集群启动: 3×CockroachDB + 2×SpiceDB                   ║"
echo "╚══════════════════════════════════════════════════════════════╝"

# ── 0. 停止旧进程 ──
echo "[0/6] 停止旧进程..."
pkill -f 'cockroach start' 2>/dev/null && sleep 2 || true
pkill -f 'spicedb serve' 2>/dev/null && sleep 1 || true
echo "  旧进程已停止"

# ── 1. 创建数据目录 ──
echo "[1/6] 准备数据目录..."
rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR"/{crdb-1,crdb-2,crdb-3,spicedb-1,spicedb-2}

# ── 2. 启动 3 节点 CockroachDB 集群 ──
echo "[2/6] 启动 CockroachDB 集群 (3 节点)..."

# 节点 1 — 种子节点
$CRDB_BIN start \
    --insecure \
    --store="$DATA_DIR/crdb-1" \
    --listen-addr=localhost:26257 \
    --http-addr=localhost:8080 \
    --sql-addr=localhost:26257 \
    --join=localhost:26257,localhost:26258,localhost:26259 \
    --cache=2GiB \
    --max-sql-memory=2GiB \
    --background

# 节点 2
$CRDB_BIN start \
    --insecure \
    --store="$DATA_DIR/crdb-2" \
    --listen-addr=localhost:26258 \
    --http-addr=localhost:8081 \
    --sql-addr=localhost:26258 \
    --join=localhost:26257,localhost:26258,localhost:26259 \
    --cache=2GiB \
    --max-sql-memory=2GiB \
    --background

# 节点 3
$CRDB_BIN start \
    --insecure \
    --store="$DATA_DIR/crdb-3" \
    --listen-addr=localhost:26259 \
    --http-addr=localhost:8082 \
    --sql-addr=localhost:26259 \
    --join=localhost:26257,localhost:26258,localhost:26259 \
    --cache=2GiB \
    --max-sql-memory=2GiB \
    --background

sleep 3

# 初始化集群
echo "  初始化集群..."
$CRDB_BIN init --insecure --host=localhost:26257 2>/dev/null || true
sleep 2

# 验证集群状态
echo "  验证集群..."
$CRDB_BIN sql --insecure --host=localhost:26257 -e "SELECT node_id, address, is_live FROM crdb_internal.gossip_nodes ORDER BY node_id;"
echo ""

# ── 3. 配置 CockroachDB 生产参数 ──
echo "[3/6] 配置 CockroachDB 生产参数..."
$CRDB_BIN sql --insecure --host=localhost:26257 -e "
-- 创建数据库
CREATE DATABASE IF NOT EXISTS spicedb;

-- 生产级调优
SET CLUSTER SETTING kv.range_split.by_load.enabled = true;
SET CLUSTER SETTING kv.range_merge.queue_interval = '1s';
SET CLUSTER SETTING sql.stats.automatic_collection.enabled = true;
SET CLUSTER SETTING server.time_until_store_dead = '1m15s';
SET CLUSTER SETTING kv.allocator.load_based_rebalancing = 'leases and replicas';
"
echo "  数据库和集群参数已配置"

# ── 4. SpiceDB 数据库迁移 ──
echo "[4/6] SpiceDB 数据库迁移..."
$SPICEDB_BIN migrate head \
    --datastore-engine cockroachdb \
    --datastore-conn-uri "postgresql://root@localhost:26257/spicedb?sslmode=disable" \
    2>&1 | tail -3

# ── 5. 启动 2 节点 SpiceDB 集群 ──
echo "[5/6] 启动 SpiceDB 集群 (2 节点)..."

# 公共配置
COMMON_FLAGS=(
    --datastore-engine cockroachdb
    --datastore-conn-uri "postgresql://root@localhost:26257,localhost:26258,localhost:26259/spicedb?sslmode=disable"
    --grpc-preshared-key "$PSK"

    # ── 连接池 ──
    --datastore-conn-pool-read-max-open 100
    --datastore-conn-pool-read-min-open 50
    --datastore-conn-pool-write-max-open 50
    --datastore-conn-pool-write-min-open 20

    # ── 缓存 (生产级) ──
    --dispatch-cache-enabled true
    --dispatch-cache-max-cost "128MiB"
    --ns-cache-enabled true
    --ns-cache-max-cost "32MiB"

    # ── 一致性 ──
    --datastore-revision-quantization-interval "5s"
    --datastore-gc-window "1h"
    --datastore-follower-read-delay-duration "4.8s"

    # ── Dispatch 集群 (SpiceDB 节点间并行 dispatch) ──
    --dispatch-cluster-enabled true
    --dispatch-upstream-addr "localhost:50051,localhost:50052"
    --dispatch-upstream-timeout "10s"

    # ── 其他 ──
    --datastore-tx-overlap-strategy insecure
    --telemetry-endpoint ""
)

# SpiceDB 节点 1
$SPICEDB_BIN serve \
    "${COMMON_FLAGS[@]}" \
    --grpc-addr :50051 \
    --dispatch-cluster-addr :50071 \
    --http-addr :8443 \
    --metrics-addr :9090 \
    > "$DATA_DIR/spicedb-1/spicedb.log" 2>&1 &

# SpiceDB 节点 2
$SPICEDB_BIN serve \
    "${COMMON_FLAGS[@]}" \
    --grpc-addr :50052 \
    --dispatch-cluster-addr :50072 \
    --http-addr :8444 \
    --metrics-addr :9091 \
    > "$DATA_DIR/spicedb-2/spicedb.log" 2>&1 &

echo "  等待 SpiceDB 启动..."
sleep 5

# 验证两个节点
echo "  节点 1:"
grpcurl -plaintext -H "authorization: Bearer $PSK" localhost:50051 grpc.health.v1.Health/Check 2>&1 | grep status
echo "  节点 2:"
grpcurl -plaintext -H "authorization: Bearer $PSK" localhost:50052 grpc.health.v1.Health/Check 2>&1 | grep status

# ── 6. 写入 Schema ──
echo "[6/6] 写入 Schema (V2 ancestor 模型)..."
$ZED_BIN schema write "$SCHEMA_FILE" --insecure --endpoint localhost:50051 --token "$PSK" 2>&1 | grep -v "warn"

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║   集群就绪                                                    ║"
echo "╠══════════════════════════════════════════════════════════════╣"
echo "║   CockroachDB:  localhost:26257, :26258, :26259             ║"
echo "║   SpiceDB gRPC: localhost:50051, :50052                     ║"
echo "║   SpiceDB HTTP: localhost:8443,  :8444                      ║"
echo "║   CRDB Console: http://localhost:8080                       ║"
echo "║                                                              ║"
echo "║   dispatch-cluster: 已启用 (节点间并行 dispatch)               ║"
echo "║   revision-quantization: 5s (缓存窗口)                       ║"
echo "║   follower-read-delay: 4.8s (CRDB follower read)            ║"
echo "║   dispatch-cache: 128MB | ns-cache: 32MB                    ║"
echo "╚══════════════════════════════════════════════════════════════╝"
