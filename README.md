[English](README_en.md)

# AuthX SpiceDB SDK

直连 SpiceDB 的高性能 Java 权限检查客户端。无平台依赖，每秒支持万级权限检查，分层 SPI 让缓存、健康检查、Watch 流监听都可插拔扩展。

[![Maven Central](https://img.shields.io/maven-central/v/io.github.authxkit/authx-spicedb-sdk.svg)](https://central.sonatype.com/artifact/io.github.authxkit/authx-spicedb-sdk)

## 特性

- **直连 gRPC**：绕过中间层，通过 gRPC 直连 SpiceDB，支持 DNS / 静态多地址
- **每秒万级检查**：缓存命中 < 1µs，缓存未命中（单节点）< 10ms
- **智能缓存**：Caffeine 内存缓存 + Watch 实时失效，按 resource type 独立 TTL
- **Watch 实时失效**：基于 `ClientCall` 的低级 gRPC 实现，正确捕捉 onHeaders/onMessage/onClose 事件，断点续传 + cursor 过期自动恢复
- **分层 SPI 全可扩展**：`HealthProbe` / `DuplicateDetector` / `watchListenerExecutor` / `TelemetrySink` / `SdkInterceptor` 都支持用户注入
- **per-resource-type 策略**：每个资源类型可独立配置缓存 TTL、一致性、重试、熔断、超时
- **Resilience4j 弹性**：CircuitBreaker + Retry + RateLimiter + Bulkhead，开箱即用
- **HdrHistogram 指标**：无锁延迟百分位追踪（p50/p99/p999），微秒级精度
- **请求合并（Coalescing）**：并发相同请求自动合并，减少 SpiceDB 负载
- **优雅降级**：Caffeine 缺失时自动 fallback 到 noop 缓存，永远不会因为缺依赖导致崩溃

## 快速开始

### 添加依赖

```groovy
// build.gradle
dependencies {
    implementation("io.github.authxkit:authx-spicedb-sdk:1.0.0")
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>io.github.authxkit</groupId>
    <artifactId>authx-spicedb-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

**要求**：Java 21+

### 初始化客户端

```java
// 最小配置
AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("dns:///spicedb.prod:50051").presharedKey("my-key"))
    .build();

// 推荐：分组配置 + 缓存 + Watch 失效
AuthxClient client = AuthxClient.builder()
    .connection(c -> c
        .target("dns:///spicedb.prod:50051")
        .presharedKey("my-preshared-key")
        .tls(true))
    .cache(c -> c
        .enabled(true)
        .maxSize(100_000)
        .watchInvalidation(true))
    .features(f -> f
        .shutdownHook(true)
        .telemetry(true))
    .build();
```

### 权限检查

```java
// 简洁写法
boolean canView = client.check("document", "doc-1", "view", "alice");

// 链式写法（推荐复用 factory）
ResourceFactory doc = client.on("document");
boolean canEdit = doc.check("doc-1", "edit", "alice");

// 一次检查多个权限
Map<String, Boolean> perms = client.checkAll("document", "doc-1", "alice", "view", "edit", "delete");
```

### 授权 / 撤权

```java
client.grant("document", "doc-1", "editor", "bob");
client.grantToSubjects("document", "doc-1", "viewer", "department:eng#member", "user:*");
client.revoke("document", "doc-1", "editor", "bob");
client.revokeAll("document", "doc-1", "bob");
```

### 写入完成监听器（grant / revoke）

类型化链式 API 的 grant / revoke 终端方法会返回一个 completion 句柄，支持
在链末尾挂载一个或多个完成监听器。**写入本身仍是同步的**，只有监听器的执行方式
可选：

```java
// 同步监听器 —— 回调在当前线程运行，本调用等它返回后才继续
client.on(Document.TYPE).select("doc-1")
    .grant(Document.Rel.EDITOR)
    .toUser("bob")
    .listener(r -> log.info("granted, zedToken={}", r.zedToken()));

// 异步监听器 —— 投递到你提供的 executor，立即返回
client.on(Document.TYPE).select("doc-1")
    .grant(Document.Rel.EDITOR)
    .toUser("bob")
    .listenerAsync(r -> audit.write(r), auditExecutor);

// 多个监听器可链式挂载
client.on(Document.TYPE).select("doc-1")
    .grant(Document.Rel.EDITOR)
    .toUser("bob")
    .listener(r -> localLog(r))
    .listenerAsync(r -> remoteAudit(r), auditExecutor);

// 忽略返回值（语句形式）仍然可用 —— 老代码 0 改动
client.on(Document.TYPE).select("doc-1")
    .grant(Document.Rel.EDITOR)
    .toUser("bob");
```

**语义要点**：
- 写入失败（`AuthxException` 子类）在终端调用处直接抛出，监听器**根本不会被注册**，也**不会触发**。
- 异步监听器回调抛出的异常会被捕获并以 `WARNING` 日志记录（logger name
  `com.authx.sdk.action.GrantCompletion` / `RevokeCompletion`），**不会**影响调用方、写入结果、或其他已投递的异步监听器。
- 多个监听器按链式注册顺序执行（异步场景下提交顺序一致，真正执行顺序由 executor 决定）。
- 链式写入跨多个内部 RPC 时（如 `select("d1","d2").grant(R1,R2).toUser("a","b")` 触发 4 次 RPC），`result()` 返回的 `GrantResult` 聚合为 `zedToken =` 最后一次写入的 token，`count =` 所有 RPC 的计数之和。

### 关闭客户端

```java
client.close();  // 实现了 AutoCloseable
// 或在 builder 里设 .features(f -> f.shutdownHook(true)) 自动注册 JVM 关闭钩子
```

---

## 进阶用法

### 策略分层（per-resource-type）

```java
PolicyRegistry policies = PolicyRegistry.builder()
    .defaultPolicy(ResourcePolicy.builder()
        .retry(RetryPolicy.defaults())
        .readConsistency(ReadConsistency.session())
        .build())
    .forResource("document", ResourcePolicy.builder()
        .circuitBreaker(CircuitBreakerPolicy.defaults())
        .readConsistency(ReadConsistency.strong())
        .build())
    .forResource("folder", ResourcePolicy.builder()
        .retry(RetryPolicy.disabled())
        .build())
    .build();

AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("localhost:50051").presharedKey("my-key"))
    .extend(e -> e.policies(policies))
    .build();
```

> 本地决策缓存已于 2026-04-18 移除（见 [ADR](docs/adr/2026-04-18-remove-l1-cache.md)）。
> 如需低延迟读，使用 `Consistency.minimizeLatency()` 命中 SpiceDB 服务端的
> schema-aware dispatch cache。

---

## 可插拔 SPI（生产环境推荐）

所有 SPI 组件都通过 `SdkComponents` 注入：

```java
AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("localhost:50051").presharedKey("my-key"))
    .cache(c -> c.enabled(true).watchInvalidation(true))
    .extend(e -> e.components(SdkComponents.builder()
        .healthProbe(myProbe)
        .watchDuplicateDetector(myDedup)
        .watchListenerExecutor(myExecutor)
        .build()))
    .build();
```

### `HealthProbe` —— 健康检查

默认会装配一个组合探针（`ChannelStateHealthProbe` + `SchemaReadHealthProbe`），无需配置即可工作：

```java
// 默认行为：
//   client.health() → 检查 gRPC channel 状态 + ReadSchema RPC
HealthResult result = client.health();
result.isHealthy();         // 整体状态
result.spicedbLatencyMs();  // 端到端延迟（毫秒）
result.probe();             // 完整的 ProbeResult 树（含每个子探针的细节）
```

**自定义探针**：

```java
// 1. 内置探针组合
SdkComponents.builder()
    .healthProbe(HealthProbe.all(
        new ChannelStateHealthProbe(channel),       // 微秒级，零 RPC
        new SchemaReadHealthProbe(channel, key,     // 端到端 RPC
            Duration.ofMillis(500))))               // 自定义超时
    .build();

// 2. 自己写一个 (需 implement HealthProbe 接口)
HealthProbe customProbe = () -> {
    long start = System.nanoTime();
    boolean ok = myExternalCheck();
    return HealthProbe.ProbeResult.up("custom", Duration.ofNanos(System.nanoTime()-start), "ok");
};

// 3. 一切都坏了，强制 maintenance 模式
SdkComponents.builder()
    .healthProbe(HealthProbe.down("maintenance window 02:00-04:00 UTC"))
    .build();
```

`SchemaReadHealthProbe` 默认 500ms 超时（适配 K8s liveness probe），把 `NOT_FOUND` 也视为 healthy（"SpiceDB 在线但没写 schema" 不算故障）。

---

## 多实例部署指南

如果你的服务跑多个实例（K8s deployment > 1 副本），重要事情：

### SESSION 一致性需要共享 tokenStore

跨实例 SESSION 一致性要求 zedtoken 在 pod 之间共享。默认 `tokenStore=null` 时只在单 JVM 内有效（启动会有警告日志）。多实例部署接入 [`sdk-redisson`](sdk-redisson/README.md) 模块即可：

```java
RedissonClient redis = Redisson.create(redissonConfig);
DistributedTokenStore store = new RedissonTokenStore(
        redis, Duration.ofSeconds(60), "authx:token:");

AuthxClient client = AuthxClient.builder()
    .extend(e -> e.components(SdkComponents.builder().tokenStore(store).build()))
    ...
```

---

## 配置参考

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `target` | — | SpiceDB 地址，如 `dns:///spicedb.prod:50051` |
| `targets` | — | 多地址列表（StaticNameResolver + round_robin） |
| `presharedKey` | — | 必填，SpiceDB 预共享密钥 |
| `useTls` | `false` | 是否启用 TLS |
| `loadBalancing` | `round_robin` | gRPC 负载均衡策略 |
| `keepAliveTime` | `30s` | keepalive 探测间隔 |
| `requestTimeout` | `5s` | 单次 gRPC 请求超时 |
| `coalescingEnabled` | `true` | 是否合并并发重复请求 |
| `useVirtualThreads` | `false` | 是否使用 Java 21 虚拟线程 |
| `registerShutdownHook` | `false` | JVM 退出时自动调用 `close()` |
| `telemetryEnabled` | `false` | 是否启用 OpenTelemetry 指标上报 |
| `defaultSubjectType` | `user` | 默认主体类型（用于简写 API） |

### `SdkComponents` SPI

| 字段 | 默认 | 说明 |
|---|---|---|
| `telemetrySink` | NOOP | 自定义 telemetry 上报（Kafka/OTLP/file） |
| `clock` | SYSTEM | 时钟（测试用） |
| `tokenStore` | null | 跨实例 SESSION 一致性的 zedtoken 存储（开箱即用：[`sdk-redisson`](sdk-redisson/README.md)） |
| `healthProbe` | `all(ChannelState, SchemaRead)` | 自定义健康探针 |

---

## 核心依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `com.authzed.api:authzed` | 1.5.4 | SpiceDB gRPC 协议 |
| `io.grpc:grpc-netty-shaded` | 1.80.0 | gRPC 传输层（CVE-2025-55163 已修） |
| `io.github.resilience4j:*` | 2.4.0 | 熔断 / 重试 / 限流 / 隔仓 |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | 延迟百分位追踪 |
| `io.opentelemetry:opentelemetry-api` | 1.40.0 | 可观测性 API（无 SDK 时为 no-op） |
| `org.slf4j:slf4j-api` | 2.0.13 | **可选** `compileOnly`，存在时自动 push 15 个 `authx.*` MDC 字段；不存在则 bridge 整体降级 no-op |

### 日志与溯源

SDK 日志默认走 `java.lang.System.Logger`（JDK 内置，零依赖，默认落到 JUL）。生产推荐接 SLF4J：

```gradle
dependencies {
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.slf4j:jul-to-slf4j:2.0.13")   // System.Logger → SLF4J
}
```

SDK 的 `Slf4jMdcBridge` 会在 RPC 入口自动 push 15 个 `authx.*` MDC key，配合 Logback pattern 即可拿到结构化字段。OTel 存在时每条日志自动带 `[trace=<16hex>]` 前缀。WARN+ 日志追加 ` [type=... res=... perm|rel=... subj=...]` 尾缀，无 SLF4J 也能快速定位。

详细配置、级别语义、完整 MDC 清单见 [`docs/logging-guide.md`](docs/logging-guide.md)。

> **要求**：Java 21+
>
> **不附属于 Authzed 公司**。这是一个独立的 Java SDK，依赖 SpiceDB 官方的 `authzed-api` protobuf 定义。

## Changelog

### 未发布 — 日志与溯源增强 (2026-04-20)

非破坏性增强。详见 [`docs/logging-guide.md`](docs/logging-guide.md) 和 [`specs/2026-04-20-logging-traceability-upgrade/`](specs/2026-04-20-logging-traceability-upgrade/)。

- 每条 SDK 日志在 OTel 活跃时自动带 `[trace=<16hex>]` 前缀
- 新 `com.authx.sdk.trace` 包：`LogCtx` / `Slf4jMdcBridge` / `LogFields`
- 可选 SLF4J 集成（`compileOnly` 2.0.13）—— 存在时自动 push 15 个 `authx.*` MDC 字段
- OTel span 属性补齐：retry 次数、consistency、result、subject、errorType 等
- WARN+ 日志追加 ` [type=... res=... perm|rel=... subj=...]` 尾缀，无 SLF4J 也可定位
- 级别审计：3 条 WARN 降级 DEBUG（`GrpcTransport` bulk-item 错误 × 2、`ResilientTransport` per-retry 日志）

**所有日志消息主干文本保持不变**；基于消息正则的告警规则继续 match。

### 未发布 — 移除 L1 本地缓存 + Watch 基础设施 (2026-04-18, BREAKING)

根据 [ADR 2026-04-18](docs/adr/2026-04-18-remove-l1-cache.md) 移除 SDK 所有客户端决策缓存。

**破坏性变更** —— 以下 API 已删除，升级需调整调用：

- `AuthxClientBuilder#cache(...)` / `CacheConfig` 整块配置
- `AuthxClient#cache()` / `CacheHandle`
- `AuthxClient#schema()` / `SchemaClient`
- `AuthxClient#onRelationshipChange(...)` / `offRelationshipChange(...)`
- `SdkComponents.Builder#watchListenerExecutor(...)` / `watchListenerDropHandler(...)` / `watchDuplicateDetector(...)`
- `ExtendConfig#addWatchStrategy(...)`
- 整个 `com.authx.sdk.cache`、`com.authx.sdk.watch`、`com.authx.sdk.dedup` 包
- `CachePolicy`、`SchemaCache`、`SchemaLoader`、`CacheHandle`、`SdkCaching`、`SchemaClient`、`AuthxCodegen`
- SPI：`DuplicateDetector`、`DroppedListenerEvent`、`QueueFullPolicy`
- `ResourcePolicy#cache(...)`、`PolicyRegistry#resolveCacheTtl(...)` / `isCacheEnabled(...)`
- `SdkMetrics` 中的 `cacheHits/Misses/Evictions/Size`、`watchReconnects`、`recordCache*`、`updateCacheSize`、`setCacheStatsSource`
- `SdkTypedEvent` 中的 Cache 和 Watch 相关 record

**保留**：`CoalescingTransport`（in-flight 去重）、`DistributedTokenStore`（跨 JVM SESSION 一致性）、全部 resilience 机制、typed chain、write-completion listener、所有 resilience/异常/lifecycle/非 Watch SPI。

**性能影响**：`check()` p50 从 ~3μs（缓存命中）变为 ~1-5ms（SpiceDB RTT）。业务可选 `Consistency.minimizeLatency()` 命中 SpiceDB 服务端 dispatch cache（schema-aware，无继承失效问题）。**SpiceDB 集群 QPS 会放大 ~20x，需确认集群容量**。

### 未发布 — Write Listener API (2026-04-18)

类型化链式 API 的 grant/revoke 终端方法新增完成监听器支持，无公共 API 破坏。
详见 [`specs/2026-04-18-write-listener-api/`](specs/2026-04-18-write-listener-api/)。

- **新接口** `com.authx.sdk.action.GrantCompletion` / `RevokeCompletion`（sealed，`of(result)` 工厂）。
- **`TypedGrantAction` / `TypedRevokeAction`** — 所有终端方法（`toUser` / `toGroupMember` / `to(SubjectRef)` / … 以及对应 `from*`）从 `void` 改返回 `GrantCompletion` / `RevokeCompletion`。Java 允许忽略非 void 返回，**老代码 0 改动**。
- **同步 listener** — `.listener(cb)` 在当前线程执行回调，返回后继续。
- **异步 listener** — `.listenerAsync(cb, executor)` 投递到用户提供的 executor，立即返回；回调抛异常被捕获并以 WARN 记录，不影响其他 listener。
- **结果聚合** — 一条链调可能跨多次内部 RPC（`select("d1","d2").grant(R1,R2).toUser(...)` → 4 RPC），`result()` 返回单个聚合的 `GrantResult`（`zedToken` = 最后一次写入的 token，`count` = 所有 RPC 求和）。

### 2026-04-18 — Critical Fixes (SR:C1–C10)

代码审查批次全部落地，无公共 API 破坏。详见 [`specs/2026-04-16-sdk-review-critical-fixes/`](specs/2026-04-16-sdk-review-critical-fixes/)。

- **`CoalescingTransport`** — leader 失败后先从 inflight 摘除再 publish 异常，避免 post-failure 的 newcomer 拿到 ghost 异常 (`SR:C3`)。
- **`RetryPolicy.defaults()`** — `InvalidPermission/Relation/ResourceException` 加入非重试清单；新增 `isPermanent(Throwable)` 便捷谓词 (`SR:C4`)。
- **`AuthxClientBuilder`** — `target` 与 `targets` 互斥；`cache.watchInvalidation(true)` 强制要求 `cache.enabled(true)`；`extend.addWatchStrategy(...)` 同理 (`SR:C6, SR:C7`)。
- **Interceptor chain** — 读路径（`RealCheckChain` / `RealOperationChain`）隔离用户 interceptor 异常并跳过继续执行；写路径（`RealWriteChain`）fail-closed (`SR:C8`)。
- **`SdkMetrics`** — HdrHistogram 上限从 60s 提升到 600s；新增 `latencyOverflowCount()` (`SR:C9`)。
- **`TelemetryReporter`** — `sink.send()` 跑在独立 `sinkExecutor` 上，默认 5s 超时；新增 `sinkTimeoutCount()` (`SR:C10`)。
- **`GrpcTransport`** — 每个 RPC 在 `CancellableContext` 内执行，`effectiveDeadline = min(上游 ctx deadline, 策略超时)`；`CloseableGrpcIterator` 在 `hasNext` / `next` 期间重新 attach，上游取消在 50ms 内传导到 gRPC 调用 (`SR:C1`)。
- **`WatchCacheInvalidator`** — `processResponse` 的 cache-invalidate-before-dispatch happens-before 边 Javadoc 明确化 + 1000 并发对回归测试 (`SR:C2`)。
- **Watch listener 丢失可观测 + 背压** — 新 SPI `DroppedListenerEvent` + `QueueFullPolicy`；`SdkComponents.Builder#watchListenerDropHandler(...)`；`CacheConfig#listenerQueueOnFull(DROP|BLOCK_WITH_BACKPRESSURE)` (`SR:C5`)。
