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

### 关闭客户端

```java
client.close();  // 实现了 AutoCloseable
// 或在 builder 里设 .features(f -> f.shutdownHook(true)) 自动注册 JVM 关闭钩子
```

---

## 进阶用法

### 缓存配置

```java
AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("localhost:50051").presharedKey("my-key"))
    .cache(c -> c
        .enabled(true)            // 启用 L1 内存缓存
        .maxSize(50_000)          // 最多缓存条目数
        .watchInvalidation(true)) // 订阅 Watch 流，关系变更时自动失效
    .build();

// 手动失效
client.cache().invalidateResource("document", "doc-1");
```

### 策略分层（per-resource-type）

```java
PolicyRegistry policies = PolicyRegistry.builder()
    .defaultPolicy(ResourcePolicy.builder()
        .cache(CachePolicy.of(Duration.ofMinutes(5)))
        .retry(RetryPolicy.defaults())
        .build())
    .forResource("document", ResourcePolicy.builder()
        .cache(CachePolicy.of(Duration.ofSeconds(30)))   // 文档权限缓存 30s
        .circuitBreaker(CircuitBreakerPolicy.defaults())
        .build())
    .forResource("folder", ResourcePolicy.builder()
        .cache(CachePolicy.of(Duration.ofMinutes(10)))   // 目录权限缓存更久
        .build())
    .build();

AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("localhost:50051").presharedKey("my-key"))
    .extend(e -> e.policies(policies))
    .build();
```

### Watch 实时变更监听

```java
// 需要先开启 cache + watchInvalidation
client.onRelationshipChange(change -> {
    System.out.println(change.resourceType() + ":" + change.resourceId()
        + " " + change.operation() + " " + change.relation()
        + " -> " + change.subjectType() + ":" + change.subjectId());

    // 审计场景：transaction metadata 由写入方注入，自动透传给监听器
    String actor = change.transactionMetadata().get("actor");
    String traceId = change.transactionMetadata().get("trace_id");

    // 临时权限：可以读 caveat 名称和过期时间
    String caveat = change.caveatName();          // null 如果没有 caveat
    Instant expiresAt = change.expiresAt();       // null 如果没有过期时间
});
```

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

### `DuplicateDetector` —— Watch 重连去重

SpiceDB 的 Watch 流在重连时可能在 cursor 边界附近**重放**事件。默认情况下 SDK 不去重（向后兼容），如果你的 listener 有副作用且无法接受重复，开启 LRU 去重：

```java
SdkComponents.builder()
    .watchDuplicateDetector(
        DuplicateDetector.lru(10_000, Duration.ofMinutes(5)))  // 需要 Caffeine
    .build();
```

去重 key 是 `zedToken`（SpiceDB 单调递增的事务标识符），自然唯一。

**重要**：dedup 只挡 listener，**不挡缓存失效**——每个 pod 的本地缓存仍然会被每次事件清掉，这是设计目标。

如果 Caffeine 不在 classpath，会自动 fallback 到 noop 并打 WARNING（同 `CachedTransport` 的行为），不会崩溃。

### `watchListenerExecutor` —— 自定义 listener dispatch 线程池

默认：单线程 + 10 000 队列 + 满时丢弃（计入 `droppedListenerEvents` 指标）。
适用：低频 listener、要求严格顺序、不想引入新线程池。

如果你的 listener 慢或者要并行处理，自己提供 executor：

```java
import java.util.concurrent.Executors;

ExecutorService myExecutor = Executors.newFixedThreadPool(8);
// 或者 JDK 21+ 虚拟线程：
ExecutorService myExecutor = Executors.newVirtualThreadPerTaskExecutor();

SdkComponents.builder()
    .watchListenerExecutor(myExecutor)
    .build();

// 注意：你提供的 executor 由你管理生命周期。
// SDK 在 close() 时不会关掉它。
```

---

## 多实例部署指南

如果你的服务跑多个实例（K8s deployment > 1 副本），重要事情：

### 缓存失效是**正确的多次执行**

每个 pod 都有自己的 Caffeine 缓存，每个 pod 都需要清自己的缓存。N 个 pod 收到 Watch 事件时各自失效——这是正确行为，不要去优化它。

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

### Listener 副作用是**可能错的多次执行**

如果你注册的 listener 会做副作用（写审计日志、发通知、调外部 API），N 个 pod 都会执行同一个事件。三种解决方式：

#### 方式 1: 让下游目的地自己幂等（推荐）

`zedToken` 是 SpiceDB 全局唯一的事务 ID，所有 pod 收到的同一个事件 zedToken 一样。利用这一点：

```java
// Elasticsearch: 用 zedToken 当文档 ID
client.onRelationshipChange(change -> {
    es.put("audit-index", change.zedToken(), toJson(change));
});

// PostgreSQL: 加 UNIQUE 约束 + ON CONFLICT DO NOTHING
jdbc.update(
    "INSERT INTO audit(zed_token, resource, action, actor) " +
    "VALUES (?, ?, ?, ?) ON CONFLICT (zed_token) DO NOTHING",
    change.zedToken(), ..., change.transactionMetadata().get("actor"));

// Kafka: enable.idempotence=true + message key = zedToken
kafka.send("events", change.zedToken(), serialize(change));
```

3 个 pod 各发一次写入，下游的去重机制保证最终只有 1 条记录。

#### 方式 2: 走消息总线 + consumer group

更解耦的方案：app 实例只把 Watch 事件 publish 到 Kafka，独立的 consumer service 通过 consumer group 保证 exactly-once 处理：

```
App pods (N) → Watch → publish 到 Kafka → 独立 consumer → 真正的副作用
```

Kafka consumer group 天然保证每条消息只被组内一个 consumer 处理。

#### 方式 3: 用 Watch 事件 zedToken 的 LRU dedup（仅同 pod 内）

```java
SdkComponents.builder()
    .watchDuplicateDetector(DuplicateDetector.lru(10_000, Duration.ofMinutes(5)))
    .build();
```

**注意**：这只挡**同一 pod 内**的重复（如 Watch 流重连补发时的重放），**不挡跨 pod 的重复**。跨 pod 还得靠方式 1 或 2。

---

## Watch 流的可观测性

```java
// 当前 Watch 连接状态
client.cache().watchInvalidator().state();
//   NOT_STARTED / CONNECTING / CONNECTED / RECONNECTING / STOPPED

// 累计重连次数（监控异常断开）
client.metrics().snapshot().watchReconnects();

// listener 队列丢弃的事件数（监控背压）
client.cache().watchInvalidator().droppedListenerEvents();
```

`CONNECTED` 状态触发条件（任一）：
1. SpiceDB 通过 gRPC HEADERS 帧明确响应
2. 底层 gRPC channel 是 READY 状态

所以即使是纯读系统（SpiceDB 没有事件推送），只要 channel 健康，state 也会显示 CONNECTED。

### Watch 自动恢复

SDK 内部处理三种异常情况：

| 情况 | 处理 |
|---|---|
| 临时网络断开 | 指数退避重试（1s → 2s → 4s → … → 30s 上限），保留 cursor 续传 |
| `--grpc-max-conn-age` 触发的连接轮换 | 自动重连，完全透明 |
| **Cursor 过期**（断连超过 SpiceDB `--datastore-gc-window`） | 自动检测 → 重置 cursor → 从 HEAD 重新订阅，**这段时间的事件会丢失**（不可恢复） |
| 永久错误（UNIMPLEMENTED / UNAUTHENTICATED / PERMISSION_DENIED） | 停止重试，缓存失效降级到纯 TTL 过期 |

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
| `cacheEnabled` | `false` | 是否启用 L1 内存缓存 |
| `cacheMaxSize` | `100000` | L1 缓存最大条目数 |
| `watchInvalidation` | `false` | 是否通过 Watch 流实时失效缓存 |
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
| `watchDuplicateDetector` | `noop()` | Watch 事件去重（默认不去重） |
| `watchListenerExecutor` | 默认单线程 + 10K 队列 | 自定义 listener 调度线程池 |

---

## 核心依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `com.authzed.api:authzed` | 1.5.4 | SpiceDB gRPC 协议 |
| `io.grpc:grpc-netty-shaded` | 1.80.0 | gRPC 传输层（CVE-2025-55163 已修） |
| `io.github.resilience4j:*` | 2.4.0 | 熔断 / 重试 / 限流 / 隔仓 |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | 延迟百分位追踪 |
| `io.opentelemetry:opentelemetry-api` | 1.40.0 | 可观测性 API（无 SDK 时为 no-op） |
| `com.github.ben-manes.caffeine:caffeine` | 3.1.8 | L1 缓存（**可选**，缺失时自动 fallback noop） |

> **要求**：Java 21+
>
> **不附属于 Authzed 公司**。这是一个独立的 Java SDK，依赖 SpiceDB 官方的 `authzed-api` protobuf 定义。
