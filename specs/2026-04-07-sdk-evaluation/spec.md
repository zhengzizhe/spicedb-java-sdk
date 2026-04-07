# AuthX SDK 全面质量评价报告

**日期**：2026-04-07
**版本**：1.0.0-SNAPSHOT
**评价方法**：8 维度打分 + 改进路线图（P0-P3）
**目标**：内部发布决策 + 改进路线图 + 客观评估

---

## 评价框架

### 评级标准

| 评级 | 含义 |
|---|---|
| **优秀** | 达到或超过业界同类 SDK 水平，无明显改进空间 |
| **良好** | 设计合理、实现完整，有小幅改进空间但不阻塞发布 |
| **合格** | 基本功能就绪，存在明确的改进方向 |
| **待改进** | 存在发布前应解决的问题 |

### 评价范围

- 111 个主源文件，28 个测试文件
- 89 次提交，2 位贡献者
- 技术栈：Java 21, gRPC, Resilience4j, Caffeine, OpenTelemetry, HdrHistogram

---

## 维度 1：架构设计

**评级：良好**

### req-1: 分层传输链

7 层装饰器各司其职，正交组合：GrpcTransport → ResilientTransport → InstrumentedTransport → CachedTransport → PolicyAwareConsistencyTransport → CoalescingTransport → InterceptorTransport。

**验证标准**：每层装饰器可独立启用/禁用且不影响其他层。

### req-2: 接口隔离

`SdkTransport` 拆分为 5 个子接口（SdkCheckTransport, SdkWriteTransport, SdkLookupTransport, SdkReadTransport, SdkExpandTransport），避免胖接口。

**验证标准**：每个子接口方法不超过 3 个，职责单一。

### req-3: SPI 扩展点

SdkInterceptor（OkHttp 风格链）、TelemetrySink、DistributedTokenStore、SdkClock、Cache<K,V> 泛型抽象，第三方可接入。

**验证标准**：每个 SPI 有默认 Noop 实现，用户可替换。

### 发现的问题

| ID | 问题 | 位置 | 严重度 |
|---|---|---|---|
| arch-1 | `Builder.build()` 约 200 行，混合 channel 创建、schema 加载、6 层装饰器组装、Watch 启动、清理逻辑 | AuthxClient.java:366-557 | 中 |
| arch-2 | 装饰器顺序隐式依赖——CachedTransport 必须在 PolicyAwareConsistencyTransport 下方，仅靠注释保证 | AuthxClient.java:410-467 | 中 |
| arch-3 | ResilientTransport MAX_INSTANCES=1000 后新资源类型共享默认 breaker，多租户场景旧状态丢失 | ResilientTransport.java:39,174 | 中 |
| arch-4 | CoalescingTransport.existing.join() 无超时，owner 挂起则所有等待线程一起挂 | CoalescingTransport.java:50 | 低 |
| arch-5 | InterceptorTransport 存在两种拦截模型（check/write 用链式，其他用 hook），API 不一致 | InterceptorTransport.java:40-141 | 低 |

### 改进建议

1. 将 `build()` 拆分为 `buildChannel()`、`buildTransportStack()`、`buildWatch()`、`buildScheduler()` 等子方法
2. 装饰器顺序通过枚举或 builder chain 显式约束
3. ResilientTransport breaker 缓存改为 LRU 淘汰策略

---

## 维度 2：API 设计与易用性

**评级：优秀**

### req-4: Fluent API

资源中心的链式 API：`doc.grant("editor").to("alice")`、`doc.check("view").by("alice").hasPermission()`、`doc.who().withPermission("view").fetch()`。

**验证标准**：常见操作（grant/revoke/check/lookup）均可一行完成。

### req-5: 写后读一致性链

所有写结果（GrantResult/RevokeResult/BatchResult）携带 `zedToken`，可直接 `.asConsistency()` 传递给下游读操作。

**验证标准**：grant 后立即 check 可使用 AtLeast 一致性，无需手动传递 token。

### req-6: 查询终结器丰富

`fetch()`, `fetchSet()`, `fetchFirst()`, `fetchCount()`, `fetchExists()`——`fetchExists()` 自动用 `limit=1` 优化。

**验证标准**：每种终结器返回类型正确且语义清晰。

### req-7: Sealed 类型变体建模

`Consistency`（5 种实现）、`ReadConsistency`（5 种实现）强制穷举匹配。

**验证标准**：switch/pattern matching 无 default 分支即可覆盖所有情况。

### req-8: 不可变 Record 模型层

23+ 个 record（CheckRequest, SubjectRef, ResourceRef, Tuple 等），零可变状态。

**验证标准**：所有 model 类为 record，compact constructor 做非 null 校验。

### req-9: 跨资源批量操作

```java
batch.on(doc1).grant("owner").to("alice")
     .on(doc2).revoke("viewer").from("bob")
     .execute();
```

**验证标准**：多资源操作在单次 gRPC 调用中原子执行。

### req-10: Codegen 支持

Permission.Named + Relation.Named 接口让代码生成的枚举直接参与 fluent API。

**验证标准**：codegen 枚举可直接传入 `check(Permission.Named)` 等方法。

### 发现的问题

| ID | 问题 | 位置 | 严重度 |
|---|---|---|---|
| api-1 | ResourceFactory `grant()`/`revoke()` 返回 void，丢失 zedToken | ResourceFactory.java:98-115 | 中 |
| api-2 | 异步模式不一致：CheckAction 用 `byAsync()` 包装，SubjectQuery 用独立 `fetchAsync()` | ResourceHandle.java:287-289 vs 458 | 低 |
| api-3 | LookupQuery `withPermission()` 和 `by()` 运行时才校验（IllegalStateException） | LookupQuery.java:52-53 | 低 |
| api-4 | LookupQuery 无 SubjectRef 重载 | LookupQuery.java:34-38 | 低 |
| api-5 | Nullable 与 Optional 混用 | SubjectRef.java:10, CheckResult.java:14 | 低 |

### 改进建议

1. ResourceFactory `grant()`/`revoke()` 改为返回 GrantResult/RevokeResult
2. 统一异步模式为独立方法
3. LookupQuery 考虑类型安全的阶段式 builder

---

## 维度 3：错误处理与弹性

**评级：良好**

### req-11: 异常层次与 gRPC 映射

12 个自定义异常继承 AuthxException，GrpcTransport.mapGrpcException() 精确映射每个 gRPC StatusCode。

**验证标准**：每个 gRPC StatusCode 有对应 SDK 异常，无遗漏。

### req-12: 可重试/不可重试分类

可重试：AuthxTimeoutException、AuthxConnectionException。不可重试：AuthxAuthException、AuthxInvalidArgumentException、AuthxResourceExhaustedException。CB ignoreExceptions 正确配置。

**验证标准**：CB 不因不可重试异常打开。

### req-13: Resilience4j 全套集成

每资源类型独立 CB + Retry，重试预算（滑动 1 秒窗口 20%），RateLimiter + Bulkhead。

**验证标准**：单一资源类型 CB 打开不影响其他类型。

### req-14: Fail-Open 模式

CB 打开时可配置特定权限返回 HAS_PERMISSION。

**验证标准**：配置 fail-open 权限后，CB 打开时 check 返回允许而非异常。

### req-15: 降级路径

Watch UNIMPLEMENTED → 仅首次 WARNING；Schema 加载失败 → 校验禁用；DistributedTokenStore null → SESSION 退化单 JVM。

**验证标准**：每个外部依赖不可用时 SDK 仍可正常工作。

### 发现的问题

| ID | 问题 | 位置 | 严重度 |
|---|---|---|---|
| err-1 | 重试预算 LongAdder 高并发下近似值可能短暂超 20% | ResilientTransport.java:248-252 | 低 |
| err-2 | 3 个 Schema 校验异常仅单参构造器（无 cause） | exception/*.java | 低 |
| err-3 | Fail-Open 仅覆盖 CHECK，WRITE/DELETE 无等效策略 | ResilientTransport.java:71-82 | 低 |
| err-4 | 异常类无 `isRetryable()` 方法 | exception/*.java | 低 |
| err-5 | mapGrpcException() 20 行 switch 无法独立测试 | GrpcTransport.java:436-456 | 低 |

### 改进建议

1. AuthxException 添加 `isRetryable()` 方法
2. 补齐 3 个 Schema 校验异常的双参构造器
3. mapGrpcException() 提取为独立 GrpcExceptionMapper 类

---

## 维度 4：缓存与性能

**评级：良好**

### req-16: 泛型 Cache<K,V> 抽象

52 行接口，干净的 SPI：get、put、getOrLoad（single-flight）、invalidate、invalidateAll(Predicate)、stats()。

**验证标准**：接口无 Caffeine 等实现细节泄漏。

### req-17: 一致性感知缓存

CachedTransport 仅缓存 MinimizeLatency 请求，Strong/Session 直接穿透。

**验证标准**：Strong 一致性请求永不命中缓存。

### req-18: Watch 实时失效

WatchCacheInvalidator 监听 SpiceDB Watch 流，关系变更实时清除缓存，指数退避重连。

**验证标准**：关系写入后 Watch 触发缓存失效，下次 check 穿透。

### req-19: 策略驱动 TTL

PolicyRegistry 预计算合并策略，per-资源类型 + per-权限 TTL，±10% jitter 防惊群。

**验证标准**：不同资源类型/权限使用不同 TTL。

### req-20: 请求合并

CoalescingTransport 对并发相同 check 去重，第一个线程执行，其余共享结果。

**验证标准**：50 个并发相同 check 仅产生 1 次 gRPC 调用。

### 发现的问题

| ID | 问题 | 位置 | 严重度 |
|---|---|---|---|
| cache-1 | CachedTransport 写操作 pre+post 双重失效，delegate 失败时 post 误清缓存 | CachedTransport.java:68-70 | 中 |
| cache-2 | TieredCache.stats() L1.hits+L2.hits 简单相加导致命中率偏高 | TieredCache.java:49 | 中 |
| cache-3 | CaffeineCache 索引写入 vs cache.put 之间存在竞态窗口 | CaffeineCache.java:109 | 低 |
| cache-4 | WatchCacheInvalidator listener 队列满时 DiscardOldestPolicy 静默丢弃 | WatchCacheInvalidator.java:52-56 | 中 |
| cache-5 | 流式操作无背压或分页限制，大结果集可能 OOM | GrpcTransport.java:208-220 | 中 |
| cache-6 | MAX_BATCH_SIZE=500 硬编码不可配置 | GrpcTransport.java:48 | 低 |

### 改进建议

1. CachedTransport 写操作改为仅 pre-invalidate
2. TieredCache.stats() 修正命中率计算
3. WatchCacheInvalidator DiscardOldestPolicy → CallerRunsPolicy 或记录丢弃指标
4. 流式操作增加可配置 maxResults 安全阀

---

## 维度 5：可观测性

**评级：良好**

### req-21: HdrHistogram 延迟追踪

微秒精度，60s 最大可追踪，LongAdder 计数器，高吞吐下无竞争。

**验证标准**：snapshot() 返回 p50/p95/p99 百分位延迟。

### req-22: 类型安全事件总线

Sealed SdkTypedEvent 层级，按类型订阅，异常隔离。

**验证标准**：订阅特定事件类型只收到该类型事件。

### req-23: OpenTelemetry 可选集成

InstrumentedTransport 装饰器 + TelemetryReporter 异步批量上报。

**验证标准**：不配置 OTel SDK 时零开销。

### 发现的问题

| ID | 问题 | 位置 | 严重度 |
|---|---|---|---|
| obs-1 | getHistogram()/rotateHistogram() 同步锁，高吞吐下瓶颈 | SdkMetrics.java:176-188 | 中 |
| obs-2 | TelemetryReporter 队列满 offer() 静默丢弃，无丢弃指标 | TelemetryReporter.java:23 | 中 |
| obs-3 | TelemetryReporter flush 触发条件存在竞态 | TelemetryReporter.java:62 | 低 |
| obs-4 | 事件总线同步发布，长耗时监听器阻塞调用线程 | DefaultTypedEventBus.java:40-56 | 低 |
| obs-5 | 3 个 @Deprecated 方法无移除时间表 | SdkMetrics.java:113-133 | 低 |

### 改进建议

1. TelemetryReporter 队列满时记录丢弃计数指标
2. 考虑事件总线异步发布选项
3. 移除或标注 deprecated 方法的移除版本

---

## 维度 6：测试覆盖

**评级：良好**

### req-24: 分层测试策略

28 个测试文件：单元测试（Cache、Policy、Model、EventBus、Metrics、Lifecycle）、传输层测试（Resilient、Interceptor、Cached、Bulk、Expand、Caveat）、并发测试（Caffeine 50 线程、Transport 30+ 线程）、E2E（7 个有序测试）、基准测试（4 个 benchmark）。

**验证标准**：每个核心包至少有对应测试文件。

### req-25: InMemoryTransport 测试基础设施

ConcurrentHashMap 存储关系，无外部依赖即可跑完大部分测试。

**验证标准**：`./gradlew test -x :test-app:test` 无需外部服务。

### req-26: 拦截器链测试详尽

10 个测试覆盖 proceed、短路、请求修改、异常捕获、空链、写链、属性共享、计时、异常传播、默认穿透。

**验证标准**：每种拦截器行为有独立测试用例。

### 发现的问题

| ID | 问题 | 位置 | 严重度 |
|---|---|---|---|
| test-1 | WatchCacheInvalidatorTest 仅 1 个测试（close），核心路径零覆盖 | WatchCacheInvalidatorTest.java:1-27 | 高 |
| test-2 | SdkMetrics 未测试 histogram rotation、并发 recordRequest、snapshot 一致性 | SdkMetricsTest.java:1-55 | 中 |
| test-3 | E2E 测试依赖活 SpiceDB，CI 无法自动运行 | SdkEndToEndTest.java | 中 |
| test-4 | 无 GrpcTransport.mapGrpcException() 独立单元测试 | GrpcTransport.java:436-456 | 中 |
| test-5 | 无 TelemetryReporter 测试 | TelemetryReporter.java | 中 |
| test-6 | InMemoryTransport permission==relation 简化无法测试递归权限 | InMemoryTransport.java | 低 |

### 改进建议

1. **P0**：补全 WatchCacheInvalidator 测试
2. GrpcExceptionMapper 独立单元测试
3. TelemetryReporter 测试
4. E2E 考虑 Testcontainers 集成

---

## 维度 7：文档与可发现性

**评级：合格**

### req-27: README 内容完整

229 行，覆盖特性、快速开始、高级用法、配置表、依赖表。

**验证标准**：新用户可通过 README 完成首次集成。

### req-28: llms.txt AI Agent 指南

62 行，Do's/Don'ts、API 模式、错误处理指南。

**验证标准**：AI Agent 可根据 llms.txt 正确使用 SDK。

### req-29: 错误消息质量

SchemaCache 校验失败提供编辑距离建议（"Did you mean 'viewer'?"）。

**验证标准**：常见拼写错误给出正确建议。

### 发现的问题

| ID | 问题 | 位置 | 严重度 |
|---|---|---|---|
| doc-1 | README 仅中文 | README.md | 中 |
| doc-2 | Javadoc failOnError=false + quiet 隐藏警告 | build.gradle:61-64 | 中 |
| doc-3 | 无缓存一致性指南 | 全局 | 中 |
| doc-4 | 无弹性默认值集中文档 | 全局 | 中 |
| doc-5 | 无 OTel 集成指南 | 全局 | 低 |
| doc-6 | 无虚拟线程使用指南 | 全局 | 低 |
| doc-7 | SchemaCache 权限猜测启发式有误 | SchemaCache.java:207-212 | 低 |

### 改进建议

1. 添加英文 README 或双语 README
2. 编写"弹性配置指南"
3. 编写"缓存与一致性"文档

---

## 维度 8：生产运维就绪度

**评级：待改进**

### req-30: 构建可复现

Gradle Wrapper 已提交，Maven 发布配置就绪。

**验证标准**：`./gradlew build` 在全新环境可成功。

### req-31: 开发环境完整

Docker Compose 提供 CockroachDB 3 节点 + SpiceDB 2 节点 + Jaeger。

**验证标准**：`docker-compose up` 启动完整测试集群。

### 发现的问题

| ID | 问题 | 位置 | 严重度 |
|---|---|---|---|
| ops-1 | 无 CI/CD：无 GitHub Actions、GitLab CI、Jenkinsfile | 项目根目录 | 高 |
| ops-2 | 无发布自动化：版本号手动管理，无 changelog，无 tag-based 发布 | build.gradle | 高 |
| ops-3 | Caffeine compileOnly 无运行时检测，缺失时 ClassNotFoundException | build.gradle:51 | 中 |
| ops-4 | artifact ID `authcses-sdk` 与包名 `com.authx.sdk` 不一致 | build.gradle:67 | 中 |
| ops-5 | 版本号 1.0.0-SNAPSHOT 无 pre-release 策略 | build.gradle | 低 |
| ops-6 | test-app 依赖 mavenLocal() 中的 SDK，构建顺序脆弱 | test-app/build.gradle:22 | 低 |

### 改进建议

1. **P0**：建立 CI pipeline
2. **P0**：建立发布流程
3. Caffeine 运行时检测
4. artifact ID 统一为 `authx-sdk`

---

## 总体评级

| 维度 | 评级 |
|---|---|
| 1. 架构设计 | **良好** |
| 2. API 设计与易用性 | **优秀** |
| 3. 错误处理与弹性 | **良好** |
| 4. 缓存与性能 | **良好** |
| 5. 可观测性 | **良好** |
| 6. 测试覆盖 | **良好** |
| 7. 文档与可发现性 | **合格** |
| 8. 生产运维就绪度 | **待改进** |

**总体评级：良好（接近优秀）**

SDK 在架构设计和 API 质量上达到生产级水准，核心功能完整且设计精良。主要短板集中在工程基础设施（CI/CD、发布流程）和文档国际化，是发布前必须解决的问题，但不涉及核心代码质量。

---

## 改进路线图

### P0 — 发布前必须解决

| # | 改进项 | 维度 | 关联 ID |
|---|---|---|---|
| 1 | 建立 CI pipeline（compile + test + lint） | 运维 | ops-1 |
| 2 | 建立发布流程（tag → build → publish） | 运维 | ops-2 |
| 3 | 补全 WatchCacheInvalidator 测试 | 测试 | test-1 |
| 4 | Caffeine 运行时检测 | 运维 | ops-3 |
| 5 | artifact ID 统一为 `authx-sdk` | 运维 | ops-4 |

### P1 — 发布后应尽快修复

| # | 改进项 | 维度 | 关联 ID |
|---|---|---|---|
| 6 | Builder.build() 拆分为子方法 | 架构 | arch-1 |
| 7 | TieredCache.stats() 命中率修正 | 缓存 | cache-2 |
| 8 | CachedTransport 写操作改为仅 pre-invalidate | 缓存 | cache-1 |
| 9 | ResourceFactory grant/revoke 返回结果对象 | API | api-1 |
| 10 | WatchCacheInvalidator listener 队列丢弃指标 | 可观测 | cache-4 |
| 11 | TelemetryReporter 队列满丢弃指标 | 可观测 | obs-2 |
| 12 | 添加英文 README | 文档 | doc-1 |

### P2 — 发布后优化

| # | 改进项 | 维度 | 关联 ID |
|---|---|---|---|
| 13 | 装饰器顺序显式约束 | 架构 | arch-2 |
| 14 | ResilientTransport breaker 缓存改 LRU | 架构 | arch-3 |
| 15 | AuthxException 添加 isRetryable() | 错误处理 | err-4 |
| 16 | 3 个 Schema 校验异常补齐双参构造器 | 错误处理 | err-2 |
| 17 | GrpcExceptionMapper 提取 + 单元测试 | 错误处理/测试 | err-5, test-4 |
| 18 | TelemetryReporter + SdkMetrics 并发测试 | 测试 | test-5, test-2 |
| 19 | 编写"弹性配置指南" | 文档 | doc-4 |
| 20 | 编写"缓存与一致性"文档 | 文档 | doc-3 |
| 21 | E2E 测试集成 Testcontainers | 测试 | test-3 |

### P3 — 长期演进

| # | 改进项 | 维度 | 关联 ID |
|---|---|---|---|
| 22 | 统一异步模式 | API | api-2 |
| 23 | LookupQuery 类型安全阶段式 builder | API | api-3 |
| 24 | 流式操作背压/分页安全阀 | 缓存/性能 | cache-5 |
| 25 | 事件总线可选异步发布 | 可观测 | obs-4 |
| 26 | CoalescingTransport join 超时保护 | 架构 | arch-4 |
| 27 | InterceptorTransport 统一链式模型 | 架构 | arch-5 |

---

## 发布决策建议

**结论：完成 P0 后可发布**

SDK 核心代码质量达到生产级别。5 项 P0 工作项（CI/CD、发布流程、Watch 测试、Caffeine 检测、artifact ID）均为工程基础设施层面，不涉及核心逻辑变更，预估可在短期内完成。P1 项目建议在首次发布后的第一个迭代中解决。
