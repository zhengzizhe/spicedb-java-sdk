# 分布式集群部署 + 千万级压测

**日期**：2026-04-08
**范围**：3 CockroachDB + 2 SpiceDB 集群、Prometheus+Grafana 监控、3 实例 Spring Boot 集群、1000 万关系数据全面测试

---

## req-1: Docker Compose 扩展 — 监控栈

在现有 `deploy/docker-compose.yml` 基础上添加 Prometheus + Grafana：

| 服务 | 端口 | 说明 |
|------|------|------|
| prometheus | :9092 | 抓取 SpiceDB(:9090/:9091) + CockroachDB(:8080/:8081/:8082) + app(:8091/:8092/:8093) |
| grafana | :3000 | 预置 3 个 dashboard，匿名访问 |

**新增文件：**
- `deploy/prometheus/prometheus.yml` — scrape 配置
- `deploy/grafana/provisioning/datasources/prometheus.yml`
- `deploy/grafana/provisioning/dashboards/dashboard.yml` — dashboard 加载配置
- `deploy/grafana/dashboards/spicedb.json` — SpiceDB 总览（gRPC 延迟、dispatch 缓存、datastore 查询）
- `deploy/grafana/dashboards/cockroachdb.json` — CockroachDB 集群（SQL 延迟、range 分布、副本健康）
- `deploy/grafana/dashboards/sdk-app.json` — SDK/应用层（check p99、cache hit rate、TPS）

**验证标准：**
- `docker compose up` 后 Grafana http://localhost:3000 可访问
- 3 个 dashboard 自动加载，数据源自动配置
- Prometheus targets 全部 UP

## req-2: cluster-test Gradle 子模块

新建 `cluster-test/` 子模块，Spring Boot 3.x + Java 21。

**依赖：**
- `project(":")` (authcses-sdk)
- `spring-boot-starter-web`
- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`
- `caffeine`
- `grpc-netty-shaded` + `authzed` (BulkImport 直接 gRPC)

**配置（application.yml）：**
```yaml
server:
  port: ${SERVER_PORT:8091}

cluster:
  node-index: ${NODE_INDEX:0}
  node-count: 3
  spicedb:
    targets: "localhost:50061,localhost:50062"
    preshared-key: "testkey"
    cache-enabled: true
    cache-max-size: 200000
    watch-invalidation: true
    virtual-threads: true
```

**SDK 配置：**
- `targets()` round-robin 到两个 SpiceDB
- 缓存 20 万条，Watch 失效
- 虚拟线程启用
- 熔断器禁用（压测场景）
- requestTimeout 30s

**验证标准：**
- `./gradlew :cluster-test:compileJava` 通过
- `settings.gradle` 包含 `cluster-test`

## req-3: 数据生成与批量导入

**数据规模：**

| 实体 | 数量 |
|------|------|
| user | 10,000 |
| department | 500（5 层嵌套） |
| group | 200 |
| organization | 10 |
| space | 1,000 |
| folder | 50,000（最深 20 层） |
| document | 500,000 |
| **关系总量** | **~1000 万** |

**关系分布：**

| 关系类型 | 数量 |
|----------|------|
| department#member | 10,000 |
| department#parent | 400 |
| group#member | 4,000 |
| organization#member/admin | 10,100 |
| space#member/viewer/admin | 30,000 |
| folder#ancestor | 1,500,000 |
| folder#editor/viewer | 200,000 |
| document#folder + document#space | 1,000,000 |
| document#viewer/editor/owner | 4,000,000 |
| document#link_viewer | 250,000 |

**生成方式：**
- `RelationshipFileGenerator.java` — Java 程序生成关系文件，格式 `resource:id#relation@subject_type:subject_id`
- 生成分 3 个阶段：基础实体 → 空间结构(含 ancestor 展平) → 文档+权限

**导入方式：**
- `BulkImporter.java` — 直接调 SpiceDB 的 `BulkImportRelationships` gRPC streaming API
- 不经过 SDK，直接用 `authzed` gRPC stub
- 流式写入，每批 1000 条

**Schema：** 使用现有 `deploy/schema-v2.zed`（ancestor 展平模型）

**REST API：**
- `POST /test/generate` — 生成关系文件
- `POST /test/import` — 导入到 SpiceDB
- `GET /test/status` — 查看进度和统计

**验证标准：**
- 导入完成后 SpiceDB 中关系数 >= 900 万（允许 10% 浮动）
- 导入耗时 < 5 分钟

## req-4: 压测引擎

**BenchmarkRunner** — 可配置参数：

```yaml
benchmark:
  threads: 100          # 并发线程数
  duration-seconds: 60  # 持续时间
  warmup-seconds: 10    # 预热
  operation-mix:        # 操作比例
    check: 70
    check-all: 10
    lookup-subjects: 10
    lookup-resources: 5
    write: 5
```

**压测场景：**

| ID | 场景 | 说明 |
|----|------|------|
| B1 | 读取压测 | 3 实例并发，混合 check/lookup 负载，输出 TPS/p50/p99/p999 |
| B2 | 写入压测 | 3 实例并发 grant/revoke，测写入 TPS 和冲突率 |
| B3 | 缓存一致性 | 实例 A 写入 → 实例 B/C 轮询 check 直到缓存失效，测 Watch 延迟 |
| B4 | 深层继承 | 专门 check 20 层文件夹的权限，对比 ancestor 并行性能 |
| B5 | 批量操作 | batch grant/revoke 100 条/批，测吞吐和原子性 |
| B6 | 故障恢复 | kill 一个 SpiceDB 节点 → 观察熔断触发 → 恢复后测重连 |

**输出：**
- 控制台实时打印 TPS / p50 / p99 / 错误率
- Micrometer 指标暴露到 Prometheus → Grafana 可视化
- `GET /test/benchmark/report` 返回 JSON 结果

**REST API：**
- `POST /test/benchmark/{scenario}` — 触发指定场景（B1-B6）
- `POST /test/benchmark/all` — 依次执行所有场景
- `GET /test/benchmark/report` — 查看最近结果

**验证标准：**
- B1: 3 实例聚合 TPS > 10,000（缓存命中场景）
- B3: Watch 失效延迟 < 10s
- B6: SpiceDB 节点恢复后 30s 内请求成功率恢复到 > 99%

## req-5: 正确性验证

在压测之外，单独的正确性验证：

| 场景 | 说明 |
|------|------|
| 深层继承正确性 | 20 层文件夹链，验证顶层 viewer 对底层 document 有 view 权限 |
| 部门递归 | 子部门成员通过 all_members 继承访问 space |
| 链接分享 | document#link_viewer@user:* → 任意用户可 view |
| 批量原子性 | batch 中途模拟失败 → 验证全部回滚 |
| 跨实例一致性 | 实例 A 写入 → 实例 B 立即 check（fullConsistency）→ 应通过 |

**REST API：**
- `POST /test/verify` — 执行所有正确性验证
- 返回每个场景 PASS/FAIL + 详情

**验证标准：** 所有场景 PASS

## req-6: 启动脚本

`cluster-test/scripts/start-cluster.sh`：

```bash
# 一键启动 3 个 JVM 实例
NODE_INDEX=0 SERVER_PORT=8091 ./gradlew :cluster-test:bootRun &
NODE_INDEX=1 SERVER_PORT=8092 ./gradlew :cluster-test:bootRun &
NODE_INDEX=2 SERVER_PORT=8093 ./gradlew :cluster-test:bootRun &
```

`cluster-test/scripts/stop-cluster.sh` — 停止所有实例

**验证标准：**
- 3 个实例启动后 `GET /actuator/health` 均返回 UP
- Prometheus 能抓到 3 个实例的 metrics
