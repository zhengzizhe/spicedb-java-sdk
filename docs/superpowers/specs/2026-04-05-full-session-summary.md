# SDK 全量工作总结

---

## 一、架构审查（已完成）

对 SDK 103 个 Java 文件做了全量代码审查，发现 10 个问题并全部修复。

### 修复清单

| # | 优先级 | 问题 | 修复 | Commit |
|---|--------|------|------|--------|
| 1 | P0 | `CheckAllAction.byAll()` N 个用户 = N 次 gRPC | 全部打包成 1 次 `checkBulkMulti` | `38c1d05` |
| 2 | P0 | L0 快速路径绕过整个 transport 链（interceptor/metrics/telemetry 全跳过） | 删除 L0，CachedTransport 做唯一缓存入口 | `73de430` |
| 3 | P0 | `GrpcTransport.stub()` 每次调用创建新 Metadata + Stub | 构造函数缓存 baseStub，每次只加 deadline | `066af95` |
| 4 | P1 | `DefaultTypedEventBus.publish()` 静默吞异常 | 加 `System.Logger.WARNING` | `15d6f62` |
| 5 | P1 | `SubjectQuery.fetchExists()` 拉取全部数据再判断 | 用 `limit(1)` | `3e5d6a7` |
| 6 | P1 | `SdkMetrics` 每个 percentile 方法独立加锁 | 标记 `@Deprecated`，引导用 `snapshot()` | `3e5d6a7` |
| 7 | P2 | `WatchCacheInvalidator` 构造函数启动线程 | 抽取 `start()` 方法 | `9333549` |
| 8 | P2 | Builder.build() 5 个单元素数组 hack | 提取 `BuildContext` 内部类 | `611461c` |
| 9 | P2 | `fetchGroupByRelationSubjectIds()` 冗余方法 | 删除，更新 3 个调用点 | `423c42b` |
| 10 | P2 | AuthCsesClient 20+ 处全限定类名 | 替换为 import | `d7fdc5d` |

**结果**: 141 个单元测试全部通过，0 回归。

---

## 二、压测（已完成）

### 基础设施

搭建了本地生产级集群：
- 3 × CockroachDB（2GB cache/节点）
- 2 × SpiceDB（512MB dispatch cache，30s quantization）
- benchmark 模块：`/benchmark/src/main/java/com/authcses/benchmark/`

### Schema 优化

发现 V1 schema 的 `parent->view` 递归导致每个 check 50+ 次 CRDB 查询。

```
V1 (递归): folder#view = viewer + parent->view + space->view
           20 级嵌套 = 20 次串行查询

V2 (并行): folder#view = view_local + ancestor->view_local + space->view
           20 级嵌套 = 20 个祖先并行查询，延迟 ≈ 1 次
```

写入时展平所有祖先关系，SpiceDB 并行 dispatch。

### 关键数据

| 测试 | 结果 |
|------|------|
| SDK 中间件穿透开销（7 层装饰器） | < 5µs |
| SDK 缓存命中 P50 | **1µs** |
| SpiceDB 单线程 check (minimize_latency, warm dispatch cache) | **352µs** |
| SpiceDB 单线程 check (full consistency) | 1-3ms |
| **真实访问模式 200 在线用户** | **1,316,814 QPS**，99.1% 缓存命中 |
| 纯随机访问 200 线程 | 1,063 QPS，0.1% 缓存命中 |
| 20 级文件夹嵌套 check (V2 schema) | P50 = 3ms |

### 为什么随机模式只有 1K QPS

- SpiceDB dispatch cache key 包含 subject —— 不同用户查同一文档 = 不同 cache entry
- 200 文档 × 1000 用户 × 3 权限 = 60 万种组合，5s 窗口内 4000 请求，命中率 0.67%
- **AuthZed 官方数据对得上**: 1K QPS 时 dispatch cache 命中率 = 0%，10K QPS = 65%，1M QPS = 96%

---

## 三、调研发现

### Google Zanzibar Leopard

- 预计算组成员关系的传递闭包（`MEMBER2GROUP` ∩ `GROUP2GROUP`）
- 用 skip list 做有序集合交集，O(min(|A|,|B|))
- 性能: P50 < 150µs，1.56M QPS
- 代价: 1 次 tuple 变更 → 上万次索引更新（写放大）
- **SpiceDB 不实现**

### AuthZed Materialize

- SpiceDB 的闭源商业版 Leopard
- 独立 sidecar，监听关系变更，推送权限 delta 给用户的 DB/ES
- API 已公开（proto + Java SDK 0.10.0+），服务端闭源
- 仅 Dedicated Cloud 客户可用
- **关闭原因**: Tiger Cache (#207) 方案不够好，Materialize 作为商业壁垒

### 竞品对比

| 系统 | 预计算 | 开源 |
|------|:------:|:----:|
| AuthZed Materialize | 是 | 否 |
| Flowtide (SpiceDB connector) | 是 | 是（但大数据量存储爆炸） |
| Feldera (增量 SQL) | 是 | 是（但存储可能 N²） |
| OpenFGA / Ory Keto / Permify | 否 | 是 |

---

## 四、一致性模型分析

### 四种级别

| 级别 | 含义 | 走缓存 | 延迟 |
|------|------|:------:|------|
| **MinimizeLatency** | 量化窗口快照（可能过时 5-30s） | ✅ L1+L2+dispatch | 最低 |
| **Session** | 本实例写后读一致 | 看情况（见下） | 低 |
| **AtLeast(token)** | 至少跟这个 token 一样新 | ❌ | 中 |
| **Full** | 绝对最新 | ❌ | 最高 |

### 发现的问题：Session + Redis TokenStore 废掉缓存

`TokenTracker` 只存 1 个全局 `last_write` token。任何资源的写入 → 所有资源的 Session 读都变成 `AtLeast(token)` → 跳过缓存。

**应改为按 resource type 粒度**追踪 token。

### Transport 链一致性处理流程

```
用户传 MinimizeLatency (默认)
  → PolicyAwareConsistencyTransport 查策略
    → 策略是 MinimizeLatency → 不改 → CachedTransport 走缓存 ✓
    → 策略是 Session → TokenTracker 查 Redis
      → 有 token → 变成 AtLeast(token) → 跳过缓存 ✗
      → 无 token → 保持 MinimizeLatency → 走缓存 ✓
    → 策略是 Strong → 变成 Full → 跳过缓存
  → CachedTransport: 只有 MinimizeLatency 走缓存
```

---

## 五、三高分析

### 高并发

| 问题 | 严重度 | 现状 |
|------|:------:|------|
| gRPC 单通道瓶颈（HTTP/2 默认 100 并发流） | 🔴 | 需要 channel pool |
| TokenTracker 全局 token（全局写竞争 + 缓存失效） | 🔴 | 需要 resource type 粒度 |
| Watch 单线程处理 | 🟡 | 需要 Reactor 分发模型 |

### 高数据量

| 问题 | 严重度 | 现状 |
|------|:------:|------|
| L1 500K 容量 vs 40 万亿可能的 key 空间 | 正常 | L1 就是 hot working set |
| 批量操作无分批（可能超 gRPC 4MB 限制） | 🟡 | 需要自动分批 |
| per-resource TokenStore key 爆炸 | 🟡 | 改为 per-resource-type |

### 高压力

| 问题 | 严重度 | 现状 |
|------|:------:|------|
| 缓存雪崩（TTL 同时过期 → 全部 miss → 打爆 SpiceDB） | 🔴 | 无防护 |
| 重试放大（SpiceDB 过载 → 重试 → 3 倍流量 → 死亡螺旋） | 🔴 | 无全局预算 |
| 熔断恢复瞬间涌入 | 🟡 | 无渐进放量 |

### 解决方案（借鉴现有框架）

| 问题 | 方案 | 来源 |
|------|------|------|
| 缓存雪崩 | `Caffeine.refreshAfterWrite()` + TTL 抖动 | Caffeine 内置能力 |
| 重试放大 | gRPC 原生 retry policy + retry budget (≤10%) | gRPC + Google SRE |
| 单通道瓶颈 | 多 channel round-robin | gRPC channel pool |
| Watch 单线程 | 1 接收 + N worker 按 key 分区保序 | Reactor 模型 |
| 熔断渐进恢复 | 半开 → 指数放量 → 全开 | Envoy slow start / TCP 拥塞控制 |
| 批量分批 | 自动 1000/批 | gRPC flow control |
| 跨实例失效 | Redis pub/sub | 标准做法 |

---

## 六、新架构设计（草案）

### 三个子系统

```
┌──────────────────┐   ┌──────────────────┐   ┌────────────────────┐
│  Reactive Cache   │   │  Impact Analyzer  │   │  Permission Stream │
│                   │   │                   │   │                    │
│  L1 Caffeine      │   │  关系变更           │   │  typed events      │
│  L2 Redis         │◄──│  → 权限影响展开     │──►│  → 业务回调         │
│  InvalidationBus  │   │  → 有界展开        │   │  → 背压 + 降级      │
│  WarmupEngine     │   │  → Precise/Scope  │   │  → 投影注册         │
└──────────────────┘   └──────────────────┘   └────────────────────┘
```

**Reactive Cache**: L1→L2→SpiceDB 三级 + 跨实例失效总线 + 预热引擎 + 双索引（资源+主体）

**Impact Analyzer**: 关系变更 → 计算权限影响。直接授权=精确推送，容器/组变更=有界展开，超大范围=Scope 降级

**Permission Stream**: 业务注册关心的 (resourceType, permission)，SDK 推 gained/revoked 事件，业务自己更新 ES

### 职责边界

- **SDK**: 快速 check + 可靠事件流 + 批量工具
- **业务**: 维护 ES 索引 + 列表查询

### Transport 链调整

```
之前: 拦截器 → 合并 → 一致性 → 缓存 → 遥测 → 熔断 → gRPC
之后: 拦截器 → 遥测 → 合并 → 一致性 → 缓存 → 熔断 → gRPC
                 ↑
           移到缓存上面，所有请求都有遥测
```

---

## 七、待实现

| 项目 | 状态 |
|------|------|
| 架构审查 10 个修复 | ✅ 已完成已合并 |
| benchmark 模块 | ✅ 已完成 |
| Schema V2 (ancestor 模型) | ✅ 已完成 |
| 集群启动脚本 | ✅ 已完成 |
| Reactive Cache 设计 | 📝 草案 |
| Impact Analyzer 设计 | 📝 草案 |
| Permission Stream 设计 | 📝 草案 |
| 三高问题修复 | 📝 待规划 |
| Transport 链顺序调整 | 📝 待实现 |
| TokenTracker 资源粒度改造 | 📝 待实现 |
