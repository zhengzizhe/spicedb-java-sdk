#!/usr/bin/env bash
set -euo pipefail

# ═══════════════════════════════════════════════════════════════
#  10M 关系数据快速导入
#
#  方案：Python 生成 CSV → CockroachDB IMPORT INTO
#  速度：~200K rels/sec，预计 < 1 分钟完成 10M
#
#  数据分布（按 schema-v2.zed 飞书文档模型）：
#    user:           100,000
#    department:     1,000   (50/org × 20 org)
#    group:          500     (25/org × 20 org)
#    organization:   20
#    space:          2,000   (100/org)
#    folder:         100,000 (50/space, 10 层深度)
#    document:       800,000 (8/folder)
#
#  关系分布：
#    org members:        100,000 (user→org)
#    dept members:       200,000 (user→dept, ~200/dept)
#    dept hierarchy:       1,000 (dept→parent dept)
#    group members:      100,000 (user→group, ~200/group)
#    space relations:    400,000 (org, owner, admin, member, viewer)
#    folder relations:   900,000 (space, ancestor chains, editors, viewers)
#    folder ancestors: 2,500,000 (ancestor 展平，avg 5 ancestors/folder)
#    document rels:    8,800,000 (folder, space, owner, editor, commenter, 5×viewer)
#    ──────────────────────────
#    total:          ~13,000,000 (超出部分在 target 处截断)
# ═══════════════════════════════════════════════════════════════

CRDB_HOST="127.0.0.1:26557"
CRDB_BIN="cockroach"
DATA_DIR="$HOME/spicedb-cluster/import"
TARGET_COUNT=${1:-10000000}

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
log_step()  { echo -e "${BOLD}${CYAN}[$1]${NC} $2"; }
log_ok()    { echo -e "  ${GREEN}✓${NC} $1"; }
log_fail()  { echo -e "  ${RED}✗${NC} $1"; }
log_info()  { echo -e "  ${NC}$1"; }

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║   10M 关系数据导入 (CockroachDB IMPORT INTO)                  ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# ── 1. 生成 CSV 数据 ──
log_step "1/3" "生成 ${TARGET_COUNT} 条关系数据 (CSV)"
mkdir -p "$DATA_DIR"

python3 - "$DATA_DIR" "$TARGET_COUNT" << 'PYTHON_SCRIPT'
import sys, os, csv, time, random

out_dir = sys.argv[1]
target = int(sys.argv[2])

random.seed(42)  # 可复现

# ── 规模参数 ──
NUM_ORGS = 20
NUM_USERS = 100_000
NUM_DEPTS = 1_000        # 50/org
NUM_GROUPS = 500          # 25/org
NUM_SPACES = 2_000        # 100/org
NUM_FOLDERS = 100_000     # 50/space
NUM_DOCS = 800_000        # 8/folder
MAX_DEPTH = 10            # 文件夹最大深度

print(f"  生成参数: {NUM_USERS} users, {NUM_DEPTS} depts, {NUM_GROUPS} groups")
print(f"            {NUM_SPACES} spaces, {NUM_FOLDERS} folders, {NUM_DOCS} docs")

start = time.time()
count = 0
out_file = os.path.join(out_dir, "relations.csv")
f = open(out_file, "w", newline="")
w = csv.writer(f)

def emit(ns, oid, rel, uns, uoid, urel="..."):
    global count
    w.writerow([ns, oid, rel, uns, uoid, urel])
    count += 1
    if count % 1_000_000 == 0:
        elapsed = time.time() - start
        rate = count / elapsed
        print(f"  ... {count:,} rows ({rate:,.0f}/s)")

# ── 1. Organization members ──
for u in range(NUM_USERS):
    org_id = u % NUM_ORGS
    emit("organization", f"org-{org_id}", "member", "user", f"user-{u}")

# ── 2. Department members + hierarchy ──
for d in range(NUM_DEPTS):
    org_id = d // (NUM_DEPTS // NUM_ORGS)
    # 每个 department 200 个 member
    for i in range(200):
        uid = (d * 200 + i) % NUM_USERS
        emit("department", f"dept-{d}", "member", "user", f"user-{uid}")
    # parent hierarchy (5 层)
    if d % 5 != 0:
        parent = d - 1
        emit("department", f"dept-{d}", "parent", "department", f"dept-{parent}")

# ── 3. Group members ──
for g in range(NUM_GROUPS):
    for i in range(200):
        uid = (g * 200 + i) % NUM_USERS
        emit("group", f"group-{g}", "member", "user", f"user-{uid}")

# ── 4. Space relations ──
for s in range(NUM_SPACES):
    org_id = s // (NUM_SPACES // NUM_ORGS)
    emit("space", f"space-{s}", "org", "organization", f"org-{org_id}")
    emit("space", f"space-{s}", "owner", "user", f"user-{s % NUM_USERS}")
    # 5 admins
    for i in range(5):
        uid = (s * 5 + i) % NUM_USERS
        emit("space", f"space-{s}", "admin", "user", f"user-{uid}")
    # 50 members
    for i in range(50):
        uid = (s * 50 + i) % NUM_USERS
        emit("space", f"space-{s}", "member", "user", f"user-{uid}")

# ── 5. Folder relations + ancestors ──
folders_per_space = NUM_FOLDERS // NUM_SPACES  # 50
for fld in range(NUM_FOLDERS):
    space_id = fld // folders_per_space
    depth = fld % MAX_DEPTH  # 0-9
    local_idx = fld % folders_per_space

    emit("folder", f"folder-{fld}", "space", "space", f"space-{space_id}")

    # ancestor 展平 (avg ~5 ancestors per folder)
    if depth > 0:
        # 写入从当前层到第 0 层的所有祖先
        base = fld - depth  # depth-0 folder in this chain
        for anc_depth in range(depth):
            anc_id = base + anc_depth
            if anc_id >= 0 and anc_id < NUM_FOLDERS:
                emit("folder", f"folder-{fld}", "ancestor", "folder", f"folder-{anc_id}")

    # 2 editors + 3 viewers per folder
    for i in range(2):
        uid = (fld * 2 + i) % NUM_USERS
        emit("folder", f"folder-{fld}", "editor", "user", f"user-{uid}")
    for i in range(3):
        uid = (fld * 3 + i + NUM_USERS // 2) % NUM_USERS
        emit("folder", f"folder-{fld}", "viewer", "user", f"user-{uid}")

    if count >= target:
        break

# ── 6. Document relations ──
if count < target:
    docs_per_folder = NUM_DOCS // NUM_FOLDERS  # 8
    for doc in range(NUM_DOCS):
        folder_id = doc // docs_per_folder
        space_id = folder_id // folders_per_space

        emit("document", f"doc-{doc}", "folder", "folder", f"folder-{folder_id}")
        emit("document", f"doc-{doc}", "space", "space", f"space-{space_id}")
        emit("document", f"doc-{doc}", "owner", "user", f"user-{doc % NUM_USERS}")

        # 2 editors
        for i in range(2):
            uid = (doc * 2 + i + 1000) % NUM_USERS
            emit("document", f"doc-{doc}", "editor", "user", f"user-{uid}")
        # 1 commenter
        uid = (doc * 3 + 5000) % NUM_USERS
        emit("document", f"doc-{doc}", "commenter", "user", f"user-{uid}")
        # 5 viewers
        for i in range(5):
            uid = (doc * 5 + i + 10000) % NUM_USERS
            emit("document", f"doc-{doc}", "viewer", "user", f"user-{uid}")

        if count >= target:
            break

f.close()
elapsed = time.time() - start
size_mb = os.path.getsize(out_file) / 1024 / 1024
print(f"  完成: {count:,} 条关系, {size_mb:.1f} MB, 耗时 {elapsed:.1f}s ({count/elapsed:,.0f}/s)")
PYTHON_SCRIPT

log_ok "CSV 文件已生成: $DATA_DIR/relations.csv"

# ── 2. 导入 CockroachDB ──
log_step "2/3" "导入 CockroachDB (IMPORT INTO)"

# 先清空旧数据
$CRDB_BIN sql --insecure --host=$CRDB_HOST --database=spicedb -e "
DELETE FROM relation_tuple WHERE true;
" > /dev/null 2>&1
log_info "已清空 relation_tuple 表"

# 使用 nodelocal 方式导入 (最快)
# 先把 CSV 复制到 CRDB 的 extern 目录
CRDB_EXTERN="$HOME/spicedb-cluster/crdb-1/extern"
mkdir -p "$CRDB_EXTERN"
cp "$DATA_DIR/relations.csv" "$CRDB_EXTERN/relations.csv"
log_info "CSV 已复制到 CRDB extern 目录"

import_start=$(date +%s)

$CRDB_BIN sql --insecure --host=$CRDB_HOST --database=spicedb -e "
SET CLUSTER SETTING jobs.debug.pausepoints = '';
IMPORT INTO relation_tuple (namespace, object_id, relation, userset_namespace, userset_object_id, userset_relation)
CSV DATA ('nodelocal://1/relations.csv');
" 2>&1 | grep -v "^$" | tail -5

import_end=$(date +%s)
import_time=$((import_end - import_start))

# 验证行数
row_count=$($CRDB_BIN sql --insecure --host=$CRDB_HOST --database=spicedb --format=csv -e "SELECT count(*) FROM relation_tuple;" 2>&1 | tail -1)
log_ok "导入完成: ${row_count} 条关系, 耗时 ${import_time}s"

# ── 3. 写入 transaction 记录 (SpiceDB 需要) ──
log_step "3/3" "写入 SpiceDB transaction 记录"

$CRDB_BIN sql --insecure --host=$CRDB_HOST --database=spicedb -e "
INSERT INTO transactions (key) VALUES ('bulk-import-$(date +%s)');
" > /dev/null 2>&1
log_ok "transaction 记录已写入"

# 验证 SpiceDB 可读
echo ""
log_info "验证 SpiceDB 可读..."
sleep 3
for p in 50051 50052 50053; do
    result=$(zed permission check document:doc-100 view user:user-42 \
        --insecure --endpoint "127.0.0.1:$p" --token testkey 2>&1 | grep -v warn)
    if [ "$result" = "true" ]; then
        log_ok "SpiceDB :$p → check(doc-100, view, user-42) = true"
    else
        log_fail "SpiceDB :$p → check 返回: $result"
    fi
done

# 清理
rm -rf "$DATA_DIR"

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║   ${GREEN}导入完成${NC}${BOLD}                                                    ║${NC}"
echo -e "${BOLD}║${NC}   ${row_count} 条关系, 耗时 ${import_time}s                              ${BOLD}║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
