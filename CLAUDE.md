# SDK Core — 编码守则

## 核心特征

**高并发、高数据量、高性能。** SDK 运行在业务方的微服务中，直连 SpiceDB，每秒万级权限检查。

## 严禁事项

### 常量必须用枚举

```java
// ✗ 禁止 — 字符串常量散落各处
if (result.equals("HAS_PERMISSION")) { ... }
String action = "CHECK";

// ✓ 正确 — 枚举统一管理
if (result.permissionship() == Permissionship.HAS_PERMISSION) { ... }
SdkAction action = SdkAction.CHECK;
```

已有枚举（`com.authcses.sdk.model.enums`）：
- `Permissionship` — HAS_PERMISSION / NO_PERMISSION / CONDITIONAL_PERMISSION
- `SdkAction` — CHECK / CHECK_BULK / WRITE / DELETE / READ / LOOKUP_SUBJECTS / LOOKUP_RESOURCES
- `OperationResult` — SUCCESS / ERROR
- `RemovalCause` — EXPIRED / EVICTED / EXPLICIT / REPLACED

**新增常量必须先建枚举，禁止用 String 常量。**

### 线程安全

SDK 在高并发环境下运行，所有公开类必须线程安全：
- `AuthCsesClient` — 全应用共享单例
- `ResourceHandle` — 无状态，每次操作独立
- Transport 装饰器 — 通过 ConcurrentHashMap / AtomicReference 保证
- `CircuitBreaker` — 由 Resilience4j 管理，通过 ConcurrentHashMap.computeIfAbsent 为每个 resource type 创建独立实例

### 资源释放

- `Builder.build()` 必须用 try-catch 包裹，失败时释放已创建的 gRPC channel / HttpClient / scheduler
- `close()` 顺序：scheduler → watch → telemetry flush → transport → http
- 禁止 `shutdownNow()`，用 `shutdown()` + `awaitTermination`

### 性能红线

| 维度 | 红线 |
|------|------|
| check 缓存命中 | < 1µs |
| check 缓存未命中（单节点 SpiceDB） | < 10ms |
| Transport 层穿透开销（7 层装饰器） | < 5µs |
| 缓存失效 invalidateResource() | O(k) 不是 O(n)（用二级索引） |
| CoalescingTransport 并发合并 | 用 putIfAbsent，remove(key, myFuture) 防止删错 |
| TelemetryReporter | 非阻塞 offer，满了直接丢，禁止 poll+offer |

### Transport 链顺序（从外到内）

```
Interceptor → Coalescing → PolicyAwareConsistency → Cache(TwoLevel) → Instrumented → Resilient(CircuitBreaker+Retry) → GrpcTransport
```

**关键约束**：
- PolicyAwareConsistency 必须在 Cache 上面，否则写后读被缓存拦截
- Interceptor 在最外层，可以拦截/中止任何操作

### 连接管理

**SDK 直连 SpiceDB，不依赖平台。** 连接信息由业务方通过 Builder 提供。

```java
.target("dns:///spicedb.prod:50051")   // 单地址 / DNS
.targets("h1:50051", "h2:50051")       // 多地址（StaticNameResolver）
.presharedKey("my-key")
```

- gRPC 内置 DNS resolver + round_robin 负载均衡
- keepalive 检测死连接自动重建
- Schema 从 SpiceDB ReflectSchema gRPC API 直接拉取

### 已删除的 V1 代码（禁止重新引入）

- `HttpTransport` — 不再调平台 REST API
- `ConnectionManager` — gRPC keepalive 替代
- `SessionConsistencyTransport` — 被 `PolicyAwareConsistencyTransport` 替代
- `RetryTransport` — 被 `PolicyAwareRetryTransport` 替代
- `CircuitBreaker` (自研) — 被 Resilience4j CircuitBreaker 替代
- `CircuitBreakerTransport` — 被 `ResilientTransport` 替代
- `PolicyAwareRetryTransport` — 被 `ResilientTransport` 替代
- `RateLimiterInterceptor` — 被 `Resilience4jInterceptor` 替代
- `BulkheadInterceptor` — 被 `Resilience4jInterceptor` 替代

### 一致性模型

- 所有 ZedToken 必须通过 `Permissionship` 枚举 + `Consistency` sealed interface 处理
- `SessionConsistencyTransport` 自动追踪 lastWriteToken
- `RevokeAllAction` 读关系时必须用 `Consistency.full()`（保证完整性）
- `ReadConsistency` 只暴露已实现的级别，未实现的标注 "planned"

### 策略分层

每个 resource type 可以有独立的缓存 / 一致性 / 重试 / 熔断 / 超时策略：

```
全局默认（defaultPolicy）
  └── 按 resource type 覆盖（document / folder / group）
        └── 按 permission 覆盖（view / edit / delete 的 cache TTL）
```

通过 `PolicyRegistry` 解析，`mergeWith()` 合并。

### InMemoryTransport

- `writeRelationships()` 必须尊重 `operation` 字段（TOUCH → put，DELETE → remove）
- `check()` 用 relation 名匹配（不做权限递归计算），文档明确说明
- 测试覆盖：正常路径 + 异常路径 + 并发场景

### 批量操作

- `BatchBuilder.execute()` 发送单次 gRPC 调用（混合 TOUCH + DELETE）
- `GrpcTransport.writeRelationships()` 根据每个 update 的 `operation()` 字段映射 gRPC 操作
- 禁止拆成多次调用（非原子 + 丢 token）

## 依赖边界

SDK 依赖 authzed-grpc + Jackson + Resilience4j + HdrHistogram + OpenTelemetry API。

**禁止依赖 platform 代码。**
