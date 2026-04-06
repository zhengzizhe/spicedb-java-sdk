# 变更日志

## 2026-04-07 — 代码质量 Polish（6 项）

| 类型 | 变更 |
|:---:|---|
| DRY | `GrpcTransport`: Permissionship 映射 ×3 提取为 `mapPermissionship()` |
| 可读性 | `ResourceFactory`: ~40 处 FQCN 内联改为 import |
| 可读性 | `WatchCacheInvalidator` / `ResilientTransport` / `SdkInterceptor.OperationContext`: FQCN 清理 |
| 清理 | `CaffeineCache`: 删除未使用的 `import java.util.Objects` |
| DRY | `CheckRequest` / `LookupSubjectsRequest` / `LookupResourcesRequest`: 新增 `withConsistency()` wither 方法 |
| DRY | `PolicyAwareConsistencyTransport`: 4 处手动构造新 request 改为 `withConsistency()` |

## 2026-04-07 — Code Review 修复（3 项）

| 优先级 | 修复项 |
|:---:|---|
| Critical | `SdkMetrics` 缓存命中/未命中计数器永远为 0 — 改为从 `Cache.stats()` 读取，不再维护独立计数器 |
| Important | `InterceptorTransport.expand()` 使用了 `SdkAction.CHECK`，应为 `SdkAction.EXPAND` |
| Important | `SchemaLoader.reflectSchemaSupported` 从 `static volatile` 改为实例字段，避免同 JVM 多客户端共享状态 |

## 2026-04-07 — SdkMetrics 方法补全

| 变更项 | 说明 |
|---|---|
| `SdkMetrics.rotateHistogram()` | 新增公开方法，定时器每 5 秒调用一次，使 `snapshot()` 读到固定窗口的延迟分布 |
| `SdkMetrics.recordWatchReconnect()` | 补全实现（调用方已存在，方法体缺失） |
| `SdkMetrics.Snapshot` | 新增 `watchReconnects` 字段 + `toString()` 输出 |

## 2026-04-06 — Watch 重连计数指标

| 变更项 | 说明 |
|---|---|
| `SdkMetrics.watchReconnects` | 新增 `LongAdder` 计数器 + `recordWatchReconnect()` 记录方法 + `watchReconnects()` 查询方法 |
| `SdkMetrics.Snapshot` | 新增 `watchReconnects` 字段，`toString()` 输出包含该值 |
| `WatchCacheInvalidator` | 构造函数新增 `SdkMetrics` 参数，每次 Watch 流重连时调用 `metrics.recordWatchReconnect()` |

## 2026-04-07 — 异常处理专项修复

**14 项修复**，覆盖 gRPC 状态码映射、优雅降级、熔断器误统计。

| 优先级 | 修复项 |
|:---:|---|
| P0 | gRPC 状态码全覆盖（UNIMPLEMENTED/FAILED_PRECONDITION/ABORTED 等），新增 4 个异常类 |
| P0 | RetryPolicy 不可重试列表补全 |
| P1 | 流式迭代器异常绕过 SDK 异常体系 → 统一经过 mapGrpcException |
| P1 | Watch UNIMPLEMENTED/认证失败 → 停止重连，退化到 TTL |
| P1 | Watch 连续 20 次失败 → 停止 |
| P1 | SchemaLoader UNIMPLEMENTED → 标志位禁用，不再重试 |
| P1 | BulkCheck 单项错误从静默吞掉改为 log WARNING |
| P1 | 熔断器 ignoreExceptions 排除 5 种客户端错误 |
| P2 | TokenStore 降级日志 AtomicBoolean 防轰炸 |
| P2 | TelemetryReporter droppedEvents 计数 + 连续失败静默 |
| P2 | SchemaLoader deadline 10s → 3s |
| P2 | CachedTransport 缓存条件加设计说明注释 |
| P2 | RateLimiter 异常保留 cause |
| P2 | RetryBudget 窗口重置 AtomicLong CAS |

## 2026-04-06 — 三高修复 + 生产就绪

**26 项修复**，解决高并发、高数据量、高压力场景下的问题。

### 高压力

| 修复项 | 手段 |
|---|---|
| Retry 包在 CB 外面 → 死亡螺旋 | CB 包 Retry（Resilience4j Decorators 正确顺序） |
| 缓存无 TTL 抖动 → 雪崩 | Caffeine Expiry ±10% jitter |
| 无全局重试预算 → 流量放大 | LongAdder 滑动窗口，20% 上限 |
| RESOURCE_EXHAUSTED 被重试 | 完善异常分类 + nonRetryableExceptions |
| slowCallRateThreshold=100% | 改为 80% |
| Bulkhead maxWaitDuration=0 | 改为 100ms |

### 高并发

| 修复项 | 手段 |
|---|---|
| CaffeineCache 索引竞态 | compute() 原子操作 + 先索引后缓存 |
| check 热路径触发 cleanUp() | 移除，改为定时采样 |
| 缓存无 singleFlight | Caffeine cache.get(key, loader) |
| TokenTracker 全局单 token | per-resource-type ConcurrentHashMap |
| CoalescingTransport 字符串 key | record 自动 equals/hashCode |
| 写后缓存失效窗口 | 写前 + 写后双重失效 |
| stub() 每次 new Metadata | TraceParentInterceptor channel 级注册 |
| Watch listener 无界队列 | ArrayBlockingQueue(10K) + DiscardOldestPolicy |
| Watch 重连无 jitter | 指数退避 + jitter |
| TelemetryReporter flush 风暴 | AtomicBoolean 门控 |
| SdkMetrics synchronized | 定时 rotateHistogram + volatile 缓存 |
| InterceptorTransport 只覆盖 check/write | 覆盖所有操作 |
| 熔断器实例无上限 | MAX_INSTANCES = 1000 |
| TieredCache size/stats 只返回 L1 | 合并 L1 + L2 |
| CheckKey.resourceIndex 重复拼接 | record 预计算字段 |

### 高数据量

| 修复项 | 手段 |
|---|---|
| 批量写入有人为限制 | 删除限制，直接透传 gRPC |
| 流式查询有人为截断 | 删除限制，用户传 limit 就限 |
| readRelationships 不传 limit 到 proto | 传入 optionalLimit |
| checkBulkMulti 无分批 | 自动 500/批（读操作，对外透明） |

### 架构优化

| 修复项 | 原因 |
|---|---|
| 删除 PooledTransport + channelCount | HTTP/2 多路复用不需要连接池 |
| getOrLoad hit/miss 计数 bug | boolean[] 标记区分 loader 线程和等待线程 |
| close() 顺序：channel 先于 watch | 改为 scheduler → watch → telemetry → transport → channel |
| 定时 rotateHistogram + cacheSize 采样 | 从热路径移到 scheduler (每 5s) |

### 测试

新增 13 个并发测试：
- CaffeineCacheConcurrencyTest (6 个)：singleFlight、hit/miss 计数、索引竞态、异常不缓存
- TransportConcurrencyTest (7 个)：Coalescing 合并/不合并/异常共享、CachedTransport singleFlight、写失效、TokenTracker 隔离、重试预算

## 2026-04-05 — 架构审查 (V1)

**10 项修复**，详见 git 历史 commit `9fdaad9`。

| 优先级 | 修复项 |
|:---:|---|
| P0 | CheckAllAction.byAll() N 用户 = N 次 gRPC → 1 次 checkBulkMulti |
| P0 | L0 快速路径绕过整个 transport 链 → 删除 L0 |
| P0 | GrpcTransport.stub() 每次创建新 Metadata + Stub → 构造函数缓存 baseStub |
| P1 | DefaultTypedEventBus.publish() 静默吞异常 → log WARNING |
| P1 | SubjectQuery.fetchExists() 全量加载再判断 → limit(1) |
| P1 | SdkMetrics 每个 percentile 独立加锁 → @Deprecated + snapshot() |
| P2 | WatchCacheInvalidator 构造函数启动线程 → 抽取 start() |
| P2 | Builder.build() 5 个单元素数组 hack → BuildContext 内部类 |
| P2 | fetchGroupByRelationSubjectIds() 冗余方法 → 删除 |
| P2 | AuthCsesClient 20+ 处全限定类名 → import |
