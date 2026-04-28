[English](README_en.md)

# AuthX SpiceDB SDK

直连 SpiceDB 的高性能 Java 权限检查客户端。无平台依赖，分层 SPI 让健康检查、遥测、跨 JVM SESSION 一致性都可插拔扩展。

[![Maven Central](https://img.shields.io/maven-central/v/io.github.authxkit/authx-spicedb-sdk.svg)](https://central.sonatype.com/artifact/io.github.authxkit/authx-spicedb-sdk)

> **破坏性变更 — 2026-04-18**：L1 本地缓存和 Watch 流失效基础设施已整体移除。
> 见 [ADR 2026-04-18](docs/adr/2026-04-18-remove-l1-cache.md)（继承链失效正确性问题）。
> 升级时需删除所有 `.cache(...)`、`CacheHandle`、`onRelationshipChange`、
> 相关的 Watch / `DuplicateDetector` / `QueueFullPolicy` 使用。
>
> **破坏性变更 — 2026-04-22**：`TypedHandle.grant(R)` / `.revoke(R)` 返回类型
> 改为 `WriteFlow`，必须以 `.commit()` 结尾，否则写入静默失败。见
> [ADR 2026-04-22](docs/adr/2026-04-22-grant-revoke-flow-api.md) 和
> [CHANGELOG.md](CHANGELOG.md)。

## 特性

- **直连 gRPC**：绕过中间层，通过 gRPC 直连 SpiceDB，支持 DNS / 静态多地址
- **分层 SPI 全可扩展**：`HealthProbe` / `TelemetrySink` / `SdkInterceptor` / `DistributedTokenStore` / `PolicyCustomizer` 都支持用户注入
- **per-resource-type 策略**：每个资源类型可独立配置一致性、重试、熔断、超时
- **Resilience4j 弹性**：CircuitBreaker + Retry + RateLimiter + Bulkhead，开箱即用
- **HdrHistogram 指标**：无锁延迟百分位追踪（p50/p99/p999），微秒级精度
- **请求合并（Coalescing）**：并发相同请求自动合并，减少 SpiceDB 负载
- **跨 JVM SESSION 一致性 SPI**：提供 `DistributedTokenStore` 扩展点，存储实现由业务方自行接入和运维

### 2026-04 新特性 — 扁平描述符（Schema flat descriptors）

业务代码只需加一行 `import static`，就能从链式调用里去掉所有 `.TYPE` / `.class`：

```java
import static com.your.app.schema.Schema.*;

client.on(Document).select(docId)
      .check(Document.Perm.VIEW)
      .by(User, userId);
```

详见 [`docs/migration-schema-flat-descriptors.md`](docs/migration-schema-flat-descriptors.md)。

## 快速开始

### 添加依赖

```groovy
// build.gradle
dependencies {
    implementation("io.github.authxkit:authx-spicedb-sdk:2.0.1")
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>io.github.authxkit</groupId>
    <artifactId>authx-spicedb-sdk</artifactId>
    <version>2.0.1</version>
</dependency>
```

**要求**：Java 21+

### 初始化客户端

```java
// 最小配置
AuthxClient client = AuthxClient.builder()
    .connection(c -> c.target("dns:///spicedb.prod:50051").presharedKey("my-key"))
    .build();

// 推荐：分组配置 + 生产特性
AuthxClient client = AuthxClient.builder()
    .connection(c -> c
        .target("dns:///spicedb.prod:50051")
        .presharedKey("my-preshared-key")
        .tls(true))
    .features(f -> f
        .shutdownHook(true)
        .telemetry(true))
    .build();
```

### 权限检查

两套链式 API，选一套。主体必须是 canonical 形式 `type:id`（无默认主体类型）。

```java
// Untyped（动态字符串）
ResourceHandle doc = client.on("document").resource("doc-1");

boolean canView = doc.check("view").by("user:alice").hasPermission();

// 一次检查多个权限 → PermissionSet
PermissionSet perms = doc.checkAll("view", "edit", "delete").by("user:alice");
perms.can("edit");   // boolean
perms.allowed();     // Set<String>

// Typed（使用 codegen 的枚举，推荐）
boolean canView = client.on(Document).select("doc-1")
    .check(Document.Perm.VIEW).by(User, "alice");
```

### 授权 / 撤权

**Untyped 路径**：`.to(...)` / `.from(...)` 是终结方法，立即写入。

```java
ResourceHandle doc = client.on("document").resource("doc-1");

// grant —— 立即写入
doc.grant("editor").to("user:bob");
doc.grant("viewer").to("group:engineering#member", "user:*");

// revoke —— 立即写入
doc.revoke("editor").from("user:bob");

// revokeAll —— 过滤式删除该主体在此资源上的所有关系
doc.revokeAll().from("user:bob");
doc.revokeAll("editor", "viewer").from("user:bob");  // 限定 relation
```

**Typed 路径**：返回 `WriteFlow`，**必须以 `.commit()` 结尾**，否则静默不写入。

```java
client.on(Document).select("doc-1")
    .grant(Document.Rel.EDITOR).to(User, "bob")
    .commit();

// 混合 grant + revoke 原子写入
client.on(Document).select("doc-1")
    .revoke(Document.Rel.EDITOR).from(User, "alice")
    .grant(Document.Rel.VIEWER).to(User, "alice")
    .commit();
```

### 写入（grant / revoke / 混合原子写）

从 2.0 起，`TypedHandle.grant(R)` / `.revoke(R)` 返回 `WriteFlow`：
累积多条 `.to(...)` / `.from(...)`，一次 `.commit()` 原子提交，对应 SpiceDB
的单个 `WriteRelationships` RPC。

```java
// 单类型多主体
client.on(Document).select("doc-1")
    .grant(Document.Rel.VIEWER)
    .to(User, "alice")
    .to(User, "bob")
    .to(Group, "eng", Group.Rel.MEMBER)
    .commit();

// 混合 grant + revoke —— 一次原子提交，中间状态不可见
client.on(Document).select("doc-1")
    .revoke(Document.Rel.EDITOR).from(User, "alice")
    .grant(Document.Rel.VIEWER).to(User, "alice")
    .commit();

// 异步提交
CompletableFuture<WriteCompletion> f = client.on(Document).select("doc-1")
    .grant(Document.Rel.VIEWER).to(User, "alice")
    .commitAsync();
```

`commit()` 返回 `WriteCompletion`，支持挂载 `.listener(...)` / `.listenerAsync(..., executor)`
做审计日志等副作用；写入本身已完成（listener 只看结果）。

**必须显式 `.commit()`** — 忘调不会报错也不会写入，由 code review 把关
（详情见 [ADR 2026-04-22](docs/adr/2026-04-22-grant-revoke-flow-api.md)）。

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
    .extend(e -> e.components(SdkComponents.builder()
        .healthProbe(myProbe)
        .telemetrySink(mySink)
        .tokenStore(myTokenStore)
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

跨实例 SESSION 一致性要求 zedtoken 在 pod 之间共享。默认 `tokenStore=null` 时只在单 JVM 内有效（启动会有警告日志）。SDK 只提供 `DistributedTokenStore` SPI，不再内置或发布具体 token-store 实现；多实例部署时需要业务方用自己的 Redis、数据库或其他共享存储实现并运维该接口：

```java
DistributedTokenStore store = new DistributedTokenStore() {
    @Override
    public void set(String key, String token) {
        mySharedStorage.setWithTtl("authx:token:" + key, token, Duration.ofSeconds(60));
    }

    @Override
    public String get(String key) {
        return mySharedStorage.get("authx:token:" + key);
    }
};

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
| `tokenStore` | null | 跨实例 SESSION 一致性的 zedtoken 存储；SDK 只提供 SPI，具体存储由业务方自行提供 |
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

版本与变更历史见 [CHANGELOG.md](CHANGELOG.md)。
