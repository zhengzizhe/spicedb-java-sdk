[English](README_en.md)

# AuthCSES Java SDK

直连 SpiceDB 的高性能权限检查客户端，无平台依赖，每秒支持万级权限检查。

## 特性

- **直连 gRPC**：绕过中间层，通过 gRPC 直连 SpiceDB，支持 DNS / 静态多地址
- **每秒万级检查**：缓存命中 < 1µs，缓存未命中（单节点）< 10ms
- **二级缓存**：L1 内存缓存（Caffeine）+ 可选 L2 分布式缓存，`PolicyAwareCheckCache` 按 resource type 独立 TTL
- **Watch 实时失效**：订阅 SpiceDB Watch 流，关系变更时自动淘汰缓存
- **per-resource-type 策略**：每个资源类型可独立配置缓存 TTL、一致性、重试、熔断、超时
- **Resilience4j 熔断重试**：CircuitBreaker + Retry + RateLimiter + Bulkhead，开箱即用
- **HdrHistogram 指标**：无锁延迟百分位追踪（p50/p99/p999）
- **请求合并（Coalescing）**：并发相同请求自动合并，减少 SpiceDB 负载

## 快速开始

### 添加依赖

```groovy
// build.gradle
dependencies {
    implementation("com.authcses:authcses-sdk:1.0.0-SNAPSHOT")
}
```

### 初始化客户端

```java
// 最小配置
AuthCsesClient client = AuthCsesClient.builder()
    .target("dns:///spicedb.prod:50051")
    .presharedKey("my-preshared-key")
    .build();

// 推荐：分组配置 + 缓存 + Watch 失效
AuthCsesClient client = AuthCsesClient.builder()
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
// 授权
client.grant("document", "doc-1", "editor", "bob");

// 授权给主体引用（组成员、通配符等）
client.grantToSubjects("document", "doc-1", "viewer", "department:eng#member", "user:*");

// 撤权
client.revoke("document", "doc-1", "editor", "bob");

// 撤销某用户在该资源上的所有关系
client.revokeAll("document", "doc-1", "bob");
```

### 关闭客户端

```java
// 实现了 AutoCloseable，推荐 try-with-resources 或显式关闭
client.close();

// Spring Bean 场景：registerShutdownHook(true) 可自动在 JVM 退出时关闭
```

---

## 进阶用法

### 缓存配置

```java
AuthCsesClient client = AuthCsesClient.builder()
    .target("localhost:50051")
    .presharedKey("my-key")
    .cache(c -> c
        .enabled(true)          // 启用 L1 内存缓存
        .maxSize(50_000)        // 最多缓存条目数
        .watchInvalidation(true)) // 订阅 Watch 流，关系变更时自动失效
    .build();

// 手动失效
client.cache().invalidateResource("document", "doc-1");
```

### 批量检查

```java
// 同一资源类型批量检查（单次 gRPC 调用）
Map<String, Boolean> results = client.on("document")
    .checkAll("doc-1", "alice", "view", "edit", "delete");

// 跨资源类型批量（CrossResourceBatchBuilder）
var batch = client.batch();
// 通过各自的 ResourceHandle 添加操作后执行
```

### Lookup 查询

```java
LookupQuery lookup = client.lookup("document");

// 查找有 view 权限的所有用户
List<String> subjects = lookup.subjects("doc-1", "view").list();

// 查找 alice 有 view 权限的所有文档
List<String> resources = lookup.resources("view", "alice").list();
```

### 策略分层（per-resource-type）

```java
PolicyRegistry policies = PolicyRegistry.builder()
    .defaultPolicy(ResourcePolicy.builder()
        .cacheTtl(Duration.ofMinutes(5))
        .retryMaxAttempts(3)
        .build())
    .forResource("document", ResourcePolicy.builder()
        .cacheTtl(Duration.ofSeconds(30))  // 文档权限缓存 30s
        .circuitBreakerEnabled(true)
        .build())
    .forResource("folder", ResourcePolicy.builder()
        .cacheTtl(Duration.ofMinutes(10))  // 目录权限缓存更久
        .build())
    .build();

AuthCsesClient client = AuthCsesClient.builder()
    .target("localhost:50051")
    .presharedKey("my-key")
    .policies(policies)
    .build();
```

### Resilience4j 拦截器（限流 + 隔仓）

```java
AuthCsesClient client = AuthCsesClient.builder()
    .target("localhost:50051")
    .presharedKey("my-key")
    .extend(e -> e
        .addInterceptor(new Resilience4jInterceptor(
            RateLimiterConfig.custom()
                .limitForPeriod(5000)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .build(),
            BulkheadConfig.custom()
                .maxConcurrentCalls(200)
                .build())))
    .build();
```

### Watch 实时变更监听

```java
// 需要先开启 watchInvalidation(true)
client.onRelationshipChange(change -> {
    System.out.println("关系变更: " + change.resourceType()
        + "/" + change.resourceId()
        + " " + change.operation());
});
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
| `keepAliveTime` | `30s` | keepalive 探测间隔，检测死连接 |
| `requestTimeout` | `5s` | 单次 gRPC 请求超时 |
| `cacheEnabled` | `false` | 是否启用本地内存缓存 |
| `cacheMaxSize` | `100000` | L1 缓存最大条目数 |
| `watchInvalidation` | `false` | 是否通过 Watch 流实时失效缓存 |
| `coalescingEnabled` | `true` | 是否合并并发重复请求 |
| `useVirtualThreads` | `false` | 是否使用 Java 21 虚拟线程（异步操作 + scheduler） |
| `registerShutdownHook` | `false` | JVM 退出时自动调用 `close()` |
| `telemetryEnabled` | `false` | 是否启用 OpenTelemetry 指标上报 |
| `defaultSubjectType` | `user` | 默认主体类型（用于简写 API） |
| `policies` | 默认策略 | `PolicyRegistry`，支持 per-resource-type 覆盖 |

---

## 核心依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `com.authzed.api:authzed` | 1.5.4 | SpiceDB gRPC 协议 |
| `io.grpc:grpc-netty-shaded` | 1.72.0 | gRPC 传输层 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.0 | JSON 序列化 |
| `io.github.resilience4j:resilience4j-circuitbreaker` | 2.4.0 | 熔断器 |
| `io.github.resilience4j:resilience4j-retry` | 2.4.0 | 重试 |
| `io.github.resilience4j:resilience4j-ratelimiter` | 2.4.0 | 限流 |
| `io.github.resilience4j:resilience4j-bulkhead` | 2.4.0 | 隔仓 |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | 延迟百分位追踪 |
| `io.opentelemetry:opentelemetry-api` | 1.40.0 | 可观测性 API（无 SDK 时为 no-op） |
| `com.github.ben-manes.caffeine:caffeine` | 3.1.8 | L1 缓存（可选，编译期依赖） |

> **要求**：Java 21+
