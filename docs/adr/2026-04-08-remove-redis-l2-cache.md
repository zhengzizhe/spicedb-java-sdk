# ADR: 移除 Redis L2 缓存，回归 L1 Caffeine + Watch 架构

**状态**：已决定
**日期**：2026-04-08
**决策者**：SDK 团队
**影响范围**：缓存架构、配置 API、运维依赖

---

## 背景

AuthX SDK 为 SpiceDB 提供 Java 客户端封装。权限检查（check）是最高频的操作——每个业务请求可能触发多次 check。为降低延迟，SDK 实现了客户端缓存。

本文档记录了从"L1+L2 双级缓存"回退到"L1 单级缓存"的决策过程和理由。

---

## 三级缓存全景

一次 `check("view").by("alice")` 请求从用户代码到数据库要穿过四层：

```
用户代码
  │
  ▼
L1 — SDK Caffeine（进程内，~0.003ms）
  │ miss
  ▼
L2 — SDK Redis（分布式，~0.5ms）  ← 本次移除
  │ miss
  ▼
L3 — SpiceDB dispatch cache（服务端进程内，~1-5ms）
  │ miss
  ▼
L4 — CockroachDB（磁盘/共识读，~5-20ms）
```

### L1：SDK Caffeine

| 属性 | 值 |
|---|---|
| 位置 | SDK 进程内存 |
| 延迟 | ~0.003ms（HashMap 查找） |
| TTL | 5s 默认，支持 per-资源类型/per-权限覆盖，±10% jitter |
| 容量 | 100,000 条（LRU 淘汰） |
| 失效 | TTL 过期 / 写前悲观失效 / Watch 实时清除 |
| 跨实例 | 不共享 |
| 命中率 | 85-99%（压测实测） |

CaffeineCache 内部维护 `ConcurrentHashMap<String, Set<CheckKey>>` 二级索引，按资源维度 O(k) 精确失效。Caffeine 原生 `cache.get(key, loader)` 保证 single-flight——同一 key 并发 miss 只加载一次。

### L2：SDK Redis（已移除）

| 属性 | 值 |
|---|---|
| 位置 | 独立 Redis 进程 |
| 延迟 | ~0.5ms（网络 RTT） |
| TTL | 10s，整个 Hash key 共享一个 EXPIRE |
| 失效 | TTL 过期 / Watch DEL / 写前悲观 DEL |
| 跨实例 | 共享 |

使用 Redis Hash-per-resource 结构：
```
Key:   authx:check:document:doc-1       ← EXPIRE 10s
Field: view:user:alice                   → HAS_PERMISSION|token|
Field: edit:user:bob                     → NO_PERMISSION|token|
```

Watch 失效通过 `DEL authx:check:document:doc-1` 实现 O(1) 清除。

### L3：SpiceDB dispatch cache

| 属性 | 值 |
|---|---|
| 位置 | SpiceDB 进程内存 |
| 延迟 | ~1-5ms（省掉 CockroachDB 查询） |
| 失效 | 量子化窗口（默认 5s）后自动过期 |
| 跨实例 | 一致性哈希分配子问题到固定节点，集群级分布式缓存 |

SpiceDB 计算权限时递归展开关系图（document → folder → space → organization），每个子问题的结果被缓存。一致性哈希确保相同子问题总是路由到相同节点，整个 SpiceDB 集群形成一个分布式缓存，总容量随节点数线性扩展。

### L4：CockroachDB

| 属性 | 值 |
|---|---|
| 位置 | CockroachDB 集群 |
| 延迟 | ~5-20ms（Raft 共识读） |
| 一致性 | 线性一致性 |

权威数据源。所有关系元组和 schema 存储于此。

---

## 失效路径

写入（grant/revoke）触发的失效从近到远：

```
SDK grant("editor").to("alice")
  │
  ├── 同步 L1：Caffeine invalidateByIndex("document:doc-1") → O(k)
  ├── 同步 L2：Redis DEL authx:check:document:doc-1 → O(1)（已移除）
  │
  ▼ gRPC → SpiceDB → CockroachDB 写入
  │
  ├── L4：CockroachDB 立即更新
  ├── L3：SpiceDB dispatch cache → 量子化窗口后（~5s）过期
  │
  └── SpiceDB Watch 推送变更 → 所有 SDK 实例
        → 各实例清自己的 L1（+ 曾经的 L2）
```

关键问题：L3（SpiceDB dispatch cache）的量子化窗口导致 MinimizeLatency 读可能在写入后 5s 内返回旧数据。SDK 清了 L1，但 SpiceDB 返回旧结果又被写入 L1。这个问题通过 Session 一致性（ZedToken）解决，不通过缓存层解决。

---

## 业界调研

对所有主流 Zanzibar 实现和大规模分布式缓存系统的调研：

### Zanzibar 生态

| 系统 | 客户端缓存决策 | 失效机制 | 分布式缓存 |
|---|---|---|---|
| **Google Zanzibar** | 不缓存 | zookie token 协议 | 服务端一致性哈希 |
| **SpiceDB (Authzed)** | 不推荐 | ZedToken + 4 种一致性级别 | 服务端 dispatch cache |
| **OpenFGA (Auth0)** | 提案搁置 | 2 种一致性模式 | 服务端 check/iterator cache |
| **Ory Keto** | 无 | 未实现 | 无 |
| **Cedar/AVP (AWS)** | 客户自建 | 纯 TTL，无内置失效 | 无 |
| **Cerbos** | **永不缓存决策** | N/A（sidecar 亚毫秒评估） | 无 |

**核心共识**：没有一个主流 Zanzibar 实现在客户端缓存权限决策并做分布式失效。它们的策略是：
1. 服务端做缓存（dispatch cache、一致性哈希）
2. 客户端通过 token 协议控制一致性（zookie/ZedToken）
3. 客户端不持有权限决策的分布式缓存

### 大规模分布式缓存

| 系统 | 失效机制 | 多级缓存 |
|---|---|---|
| **Meta TAO** | 版本号 + 广播失效 + TTL 兜底 | 边缘缓存 + 后端区域缓存 |
| **Netflix EVCache** | Kafka CDC 管道 | L1 本地 + L2 Memcached + SSD |

这些系统的 L2 是为了解决**数据量大、读写比极高（>1000:1）**的场景。权限检查的特点不同：结果只有 true/false，数据量小，但正确性要求高、失效要求实时。用数据缓存的模式来缓存权限决策是不匹配的。

---

## Redis L2 带来的问题

### 1. per-field TTL 不可实现

Redis Hash 只支持 per-key EXPIRE，不支持 per-field TTL。SDK 的 PolicyRegistry 支持 per-资源类型/per-权限的精细 TTL（如 delete=500ms, view=10s），但 Redis 只能给整个 Hash 设一个 EXPIRE。

导致：L1 中 500ms 过期的敏感权限，在 L2 中可能存活 10 秒。L1 过期后查 L2 命中旧数据，违反用户配置的 TTL 语义。

### 2. 多实例 EXPIRE 互相重置

N 个实例往同一个 Hash 写入不同 field 时，每次 `HSET` + `EXPIRE` 都会重置整个 key 的过期时间。只要有实例不停写入，Hash 永不过期。

### 3. Watch 流 N 倍放大

每个 SDK 实例独立订阅 Watch 流，收到变更后各自 DEL 同一个 Redis key。N 个实例 = N 次重复 DEL。虽然幂等，但浪费网络和 Redis CPU。

### 4. 与 SpiceDB 内部缓存功能重叠

SpiceDB 自身已有分布式 dispatch cache（一致性哈希跨节点），解决了"跨实例缓存共享"的问题。在它前面再加一层 Redis 分布式缓存，是功能重复。

### 5. 运维依赖增加

引入 Redis 意味着：
- 多一个服务要部署、监控、运维
- Redis 不可用时需要降级逻辑
- 网络分区时的行为需要考虑
- Lettuce 客户端的连接管理

### 6. 实际收益有限

| 场景 | 无 L2 | 有 L2 | 差距 |
|---|---|---|---|
| L1 命中 | 0.003ms | 0.003ms | 无 |
| L1 miss | 1-5ms（SpiceDB 内部缓存） | 0.5ms（Redis） | 0.5-4.5ms |
| 冷启动 | 1-5ms | 0.5ms | 0.5-4.5ms |

L1 命中率 85-99%，只有 1-15% 的请求会走到 L2。这 1-15% 中，L2 带来的收益是 0.5-4.5ms，代价是上述所有复杂度。

---

## 决策

**移除 Redis L2 缓存。SDK 缓存架构回归 L1 Caffeine + Watch 失效。**

### 最终架构

```
用户代码
  │
  ▼
L1 — Caffeine（进程内，0.003ms，85-99% 命中率）
  │ miss
  ▼
SpiceDB dispatch cache（服务端，1-5ms，集群级分布式缓存）
  │ miss
  ▼
CockroachDB（5-20ms，权威数据）
```

失效路径：
```
写入 → 同步清 L1 → SpiceDB 写入 CockroachDB
                         │
                         └── Watch 推送 → 所有实例清 L1
```

### 保留的能力

| 能力 | 状态 |
|---|---|
| L1 Caffeine 进程内缓存 | 保留 |
| per-type/per-permission TTL | 保留 |
| ±10% jitter 防惊群 | 保留 |
| Watch 实时失效 | 保留 |
| 写前悲观失效 | 保留 |
| Single-flight（并发 miss 只加载一次） | 保留 |
| 请求合并（CoalescingTransport） | 保留 |
| ZedToken 一致性协议 | 保留 |
| Session/Strong/MinimizeLatency 一致性选择 | 保留 |

### 移除的代码

| 文件 | 操作 |
|---|---|
| `RedisCacheAdapter.java` | 删除 |
| `RedisCacheAdapterTest.java` | 删除 |
| `TieredCacheIndexedTest.java` | 删除 |
| `WatchInvalidationPathTest.java` | 删除 |
| `ColdStartL2Test.java` | 删除 |
| `build.gradle` | 移除 Lettuce 依赖 |
| `AuthxClient.java` | 移除 redis()/redisTtl() 配置 |
| `SdkComponents.java` | 已移除 l2Cache（上一轮） |

### 保留的代码

| 文件 | 原因 |
|---|---|
| `TieredCache.java` | 保留代码但不再使用。未来如有合理的 L2 场景可复用 |
| `IndexedCache.java` | CaffeineCache 实现此接口，Watch 失效依赖它 |
| `Cache.java` | 核心缓存抽象，CaffeineCache 和 NoopCache 实现 |

---

## 配置变更

**移除前**：
```java
AuthxClient.builder()
    .cache(c -> c
        .enabled(true)
        .maxSize(100_000)
        .watchInvalidation(true)
        .redis(redisClient)              // ← 移除
        .redisTtl(Duration.ofSeconds(10))) // ← 移除
```

**移除后**：
```java
AuthxClient.builder()
    .cache(c -> c
        .enabled(true)
        .maxSize(100_000)
        .watchInvalidation(true))
```

---

## 未来方向

如果需要进一步降低延迟或解决冷启动问题，应考虑：

1. **L1 预热**：启动时预加载高频资源的权限（从 SpiceDB 批量 check）
2. **更大的 L1**：增加 Caffeine maxSize，用内存换命中率
3. **SpiceDB 集群扩容**：增加节点扩大服务端 dispatch cache 容量
4. **Sidecar 模式**：参考 Cerbos，在本地跑一个 SpiceDB 实例作为缓存代理

这些方案都不引入分布式缓存失效的复杂度。

---

## 压测数据支撑

### 移除 L2 前（3 实例 × 1000 checks）

```
Instance 1 (L1+L2+Watch): cache=91%, p50=3μs
Instance 2 (L1+L2+Watch): cache=99.9%, p50=3μs
Instance 3 (L1 only):     cache=85%, p50=3μs
```

### 关键观察

- Instance 2 的 99.9% 命中率大部分来自 L1，不是 L2
- Instance 3 没有 L2 也有 85% 命中率
- 所有实例的 p50 都是 3μs（L1 命中），L2 对 p50 无影响
- L2 只影响 1-15% 的 miss 请求的延迟（从 1-5ms 降到 0.5ms）
