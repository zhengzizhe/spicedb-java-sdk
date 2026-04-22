# ADR: 移除 L1 Caffeine 缓存 + Watch 失效基础设施，SDK 回归纯直连模式

**状态**：已决定
**日期**：2026-04-18
**决策者**：SDK 团队 + 基础设施团队
**影响范围**：缓存架构、Watch 流、public API（破坏性）、部署运维依赖

---

## 背景

2026-04-08 的 ADR（[移除 Redis L2 缓存](2026-04-08-remove-redis-l2-cache.md)）已把 L2 去掉，当时的结论是"L1 Caffeine + Watch 失效"是正确的层次，并列了 L1 的 6 条优势。

**两周的运行暴露了一个 L1 无法修复的正确性问题**，团队据此决定把 L1 也一起移除，SDK 回归"纯直连 SpiceDB"的最简架构。

---

## 触发问题：继承链失效

### 场景

SpiceDB schema 常见的继承模式：

```zed
definition folder {
    relation editor: user
}
definition document {
    relation parent: folder
    permission view = parent->editor    // 文档的 view 继承自文件夹的 editor
}
```

### 失效不全的时序

| 时间 | 事件 | L1 缓存状态 |
|---|---|---|
| T0 | `folder:f-1#editor = user:alice`（直接授权） | — |
| T1 | `document:d-1#parent = folder:f-1`（挂到文件夹下） | — |
| T2 | `check("document:d-1", "view", "alice")` → HAS | 缓存 `(doc:d-1, view, alice) = HAS` |
| T3 | `revoke("folder:f-1", "editor", "alice")`（取消协作者）| — |
| T4 | Watch 事件：`{resource:folder:f-1, relation:editor, subject:user:alice, DELETE}` | — |
| T5 | `WatchCacheInvalidator.invalidateByIndex("folder:f-1")` | 只清 folder 键，**doc:d-1 未清** |
| T6 | `check("document:d-1", "view", "alice")` | **错误返回 HAS**，最多 TTL 5s 后才过期 |

加上 SpiceDB dispatch cache 的 ~5s 量子化窗口，**继承权限的最坏 staleness ≈ 10s**。

### 为什么 L1 架构修不好

可选方案都有不可接受的副作用：

| 方案 | 副作用 |
|---|---|
| Subject 维度二级索引（按主体失效） | 过度失效：用户 alice 所有权限缓存全清，包括与变更无关的 |
| Schema-aware 依赖图 | 需要反向关系索引（谁的 parent 指向 folder:f-1），相当于在 SDK 本地复制一份关系图，违背"失效比读快"原则 |
| 强制 `FullyConsistent` 一致性 | 绕过所有缓存层，正确但延迟回归 5-20ms，且是调用方要做的事 |
| 全量 `invalidateAll()` on Watch 事件 | 缓存废掉 |

**本质**：资源键缓存不知道 schema 级的依赖关系。要在客户端正确处理，等于把 SpiceDB 的 dispatch cache 半复制一遍——这是服务端应该做的事。

---

## 业界对照

调研所有主流 Zanzibar-style 授权系统的客户端决策缓存策略：

| 系统 | 客户端决策缓存 | 一致性手段 |
|---|---|---|
| Google Zanzibar | **不做** | zookie 协议 + 服务端一致性哈希 |
| SpiceDB（Authzed 官方） | **不推荐** | ZedToken + 4 种一致性级别 + 服务端 dispatch cache |
| OpenFGA（Auth0） | **提案搁置** | 2 种一致性模式 + 服务端缓存 |
| Cerbos | **永不缓存** | sidecar 亚毫秒评估 |
| Ory Keto | **无** | 无（未实现） |
| Cedar / AVP（AWS） | 由用户自建，无内置 | 纯 TTL |

**共识**：**没有一个主流实现在客户端缓存权限决策**。它们把缓存下沉到服务端——schema-aware、一致性哈希分片、由服务端控制失效——因为只有服务端知道依赖关系。

AuthX 之前的 L1 + Watch 架构是少数派，两周实测证明少数派的代价（~10s eventual + 继承失效盲区）高于收益（3μs hot-path）。

---

## 决策

**移除 L1 Caffeine 决策缓存 + 相关的 Watch 失效基础设施。SDK 的 check() 每次都直连 SpiceDB，依赖服务端 dispatch cache 做性能优化。**

### 最终架构

**之前**（7 层 transport）：
```
用户代码
  └─ Interceptor → Instrumented → [Cached (L1)] → Coalescing → PolicyConsistency → Resilient → gRPC → SpiceDB
                                                                                                           └─ dispatch cache (5s 量子化)
                                                                                                              └─ CockroachDB
```

**之后**（6 层 transport，砍掉 Cached 层）：
```
用户代码
  └─ Interceptor → Instrumented → Coalescing → PolicyConsistency → Resilient → gRPC → SpiceDB
                                                                                         └─ dispatch cache (服务端，schema-aware)
                                                                                            └─ CockroachDB
```

### 保留能力

| 能力 | 状态 | 备注 |
|---|---|---|
| SpiceDB 服务端 dispatch cache | ✓ | 这才是决策缓存该住的地方 |
| `CoalescingTransport`（in-flight 去重） | ✓ | 同 key 并发 check 合成 1 次 RPC，和缓存无关，仍有价值 |
| `DistributedTokenStore`（Redis 共享 zedToken） | ✓ | 跨 JVM SESSION 一致性 |
| 4 种 Consistency 级别 | ✓ | 调用方选 `minimizeLatency()` 可充分利用服务端缓存 |
| Resilience 全套（CB/retry/budget/rate/bulkhead） | ✓ | 无关 |
| Typed chain + write-completion listener | ✓ | 无关 |
| 可观测性（metrics / events / telemetry） | ✓ | 删除 cache/watch 相关计数器，其他不动 |
| 异常体系、lifecycle、SPI 体系 | ✓ | 删除 Watch-specific SPI，其他不动 |

### 移除的代码

**整个包**：
- `com.authx.sdk.cache/` —— `Cache`, `CaffeineCache`, `CacheStats`, `IndexedCache`, `NoopCache`, `SchemaCache` *(SchemaCache 于 2026-04-21 以 metadata-only 形式恢复，见下文 Addendum)*
- `com.authx.sdk.watch/` —— `WatchDispatcher`, `WatchStrategy`
- `com.authx.sdk.dedup/`

**单独文件**：
- `CacheHandle`
- `internal/SdkCaching`
- `transport/WatchCacheInvalidator`（及其内部 `WatchStreamSession`）
- `transport/WatchConnectionState`
- `transport/CachedTransport`
- `transport/SchemaLoader` *(2026-04-21 作为 schema metadata 读取器恢复，见下文 Addendum)*
- `spi/DuplicateDetector`
- `spi/DroppedListenerEvent`（2026-04-18 刚加的，随 Watch 一起走）
- `spi/QueueFullPolicy`（同上）

**Builder 配置**：
- `.cache(...)` 整块
- `.extend().addWatchStrategy(...)`
- `AuthxClient#onRelationshipChange` / `offRelationshipChange`

---

## 破坏性影响

### API 破坏

```java
// 之前（会编译失败）：
AuthxClient.builder()
    .cache(c -> c.enabled(true).maxSize(100_000).watchInvalidation(true))
    .build();

// 之后（唯一正确写法）：
AuthxClient.builder()
    .connection(c -> c.target("...").presharedKey("..."))
    .build();
```

**下游需要**：
1. 删除所有 `.cache(...)` 调用
2. 删除所有 `CacheHandle` / `WatchStrategy` / `DuplicateDetector` 引用
3. 删除 `client.onRelationshipChange(...)` 订阅

### 性能影响

| 指标 | 移除前 | 移除后 | 倍数 |
|---|---|---|---|
| `check()` p50（缓存命中，95% 场景） | ~3μs | ~1-5ms | **300-1500x** |
| `check()` p50（miss 场景） | ~1-5ms | ~1-5ms | 1x |
| `check()` 平均（95% 命中混合） | ~50-250μs | ~1-5ms | **10-100x** |
| 每次 check 打到 SpiceDB 的比例 | 5% | 100% | 20x |
| SpiceDB 集群 QPS（100k check/s 下） | ~5k | ~100k | **20x** |
| 客户端 JVM 内存 | ~10MB/实例（100k entries） | 降低 | — |
| 客户端 JVM CPU | cache 维护成本 | 略降 | — |

**关键依赖**：**SpiceDB 集群必须能承接 ~20x QPS 放大**。基础设施团队已确认集群容量可覆盖。

### 补偿手段

对"不能容忍 1-5ms 延迟"的关键热路径，业务可选 `Consistency.minimizeLatency()`，SpiceDB dispatch cache 命中时仍是亚毫秒。这是 schema-aware 的服务端缓存，**没有 L1 的继承失效问题**。

---

## 迁移路径

**无过渡期、无 deprecation 期**。采用硬删策略，对称于 2026-04-08 L2 移除：

1. 本 PR 一次性删除所有相关代码。
2. 本仓库内下游（`test-app/`, `cluster-test/`）在同一 PR 内修复。
3. `sdk-redisson/` 仅依赖 `DistributedTokenStore` SPI，零改动。
4. 外部用户升级时按"迁移清单"调整 `AuthxClientBuilder` 配置和 Watch 订阅代码。

**为什么不软废弃**：L2 移除那次也是硬删，项目 1.0 前做清理的连贯节奏。软废弃要维护 `@Deprecated` 标记 + warn 日志 + 双套文档，且下个主版本还要再来一次，成本 > 收益。

---

## 未来方向

如果未来某天发现"SpiceDB 服务端 dispatch cache 不够快"，可考虑：

1. **扩 SpiceDB 集群**——一致性哈希分片，节点越多 dispatch cache 总容量越大。
2. **SpiceDB Sidecar 模式**——每业务 pod 伴随一个 SpiceDB 实例，本地 5ms → 0.5ms。SpiceDB 官方有相关方案。
3. **只做业务事件失效**——业务写权限时发 MQ 事件，业务自己管缓存；SDK 不插手。
4. **应用级缓存**——业务自己在 Redis 缓存"业务对象 + 权限"，粒度自己定，SDK 不卷入。

**但不会**在 SDK 内部再做决策缓存——这是本次决策里已经穷举过的死路。

---

## 压测数据（待本 PR 合并前补充）

- [ ] 移除前 vs 移除后 cluster-test 6 阶段基准报告对比
- [ ] 客户端 JVM 内存/CPU 变化
- [ ] SpiceDB 集群 QPS 峰值、p99 延迟
- [ ] 业务应用端到端 p50/p99 变化

将作为本 ADR 的 Addendum 回填在合并前，放在 [`docs/benchmark-report-2026-04-18-post-l1-removal.md`](../benchmark-report-2026-04-18-post-l1-removal.md)。

---

## Addendum 2026-04-21：Schema metadata 回归（决策未变）

**背景**：本 ADR 一次性删除了 `com.authx.sdk.cache.SchemaCache` + `transport.SchemaLoader` + `SchemaClient`。但 schema 元信息（resource types / relations / permissions / 每个 relation 允许的 subject types / caveat 定义）在后续工作中又有两条真实需求：

1. **运行时 subject 校验**（fail-fast）——`grant.to("folder:xxx")` 写错 subject 类型应该在 SDK 侧立即报错，不是走到 SpiceDB 再 `INVALID_ARGUMENT` 回来。
2. **Typed codegen**——`AuthxCodegen` 要根据 schema 生成 `Document.Rel.VIEWER.subjectTypes()` 元数据，业务代码才能写 `.to(User, userId)` 这种 typed overload 而不必手写 `"user:"` 前缀。（2026-04-22 之前形态为 `.to(User.TYPE, userId)`；参见 `docs/migration-schema-flat-descriptors.md`。）

两者都只需要 **metadata**，不需要 decision 缓存。

**变更**：2026-04-21 以 **metadata-only** 形式恢复：
- `com.authx.sdk.cache.SchemaCache` —— 只存 `DefinitionCache(relations, permissions, relationSubjectTypes)` + `CaveatDef`。**无** permission 决策缓存、**无** Watch 订阅、**无** 失效逻辑。
- `com.authx.sdk.transport.SchemaLoader` —— 单次 `ExperimentalServiceGrpc.experimentalReflectSchema()` 调用，映射成 `SchemaCache`。`UNIMPLEMENTED` 非致命（老版本 SpiceDB 继续工作）。
- `com.authx.sdk.SchemaClient` —— public wrapper，`AuthxClient.schema()` 暴露查询 API 给 codegen 和业务代码。

**本 ADR 决策未受影响**：继承链失效问题是 **decision 缓存** 的本质缺陷；metadata 不受此影响——它随 schema 变更（不随 relation 变更），频率低到每次 build 重跑一遍都够。

