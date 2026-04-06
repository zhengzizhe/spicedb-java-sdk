# SDK 架构

## 定位

直连 SpiceDB 的高性能权限检查 Java SDK，无平台依赖，面向每秒万级并发场景。

## 规模

- 108 源文件 / 28 测试文件
- Java 21+，Gradle 构建
- 依赖：authzed-grpc + Resilience4j + Caffeine + HdrHistogram + OpenTelemetry API

## Transport 装饰器链

SDK 的核心是 7 层 Transport 装饰器链，每层只做一件事：

```
请求进入
  │
  ▼
InterceptorTransport        限流、隔仓、校验（OkHttp 式 Chain + before/afterOperation）
  │
CoalescingTransport         并发相同 check 共享一次 gRPC 调用
  │
PolicyAwareConsistencyTransport  per-resource-type 一致性策略 + TokenTracker
  │
CachedTransport             singleFlight + 写前写后双重失效
  │
InstrumentedTransport       OTel span + TelemetryReporter + HdrHistogram
  │
ResilientTransport          per-resource-type CircuitBreaker + Retry + 重试预算
  │
GrpcTransport               真正的 gRPC 调用
```

穿透开销 < 5µs，缓存命中 P50 = 1µs。

### 关键约束

- PolicyAwareConsistency 必须在 Cache 上面 — SESSION 一致性升级后的请求必须绕过缓存
- CB 包 Retry — 一个请求在熔断器里只记 1 次成败
- Interceptor 在最外层 — 限流、隔仓对所有操作生效

## gRPC 连接模型

**一个 ManagedChannel 就够。** HTTP/2 多路复用天生支持高并发。

- `dns:///` 解析出 N 个后端 → gRPC 自动建 N 条 TCP → round_robin 分发
- K8s 环境用 Headless Service → DNS 返回所有 Pod IP → gRPC 自动均衡
- 不需要连接池 — HTTP/2 单连接支持数千并发流
- Netty EventLoop 上限约 160 万 QPS（8 核），远高于实际需求

### K8s 部署

```yaml
# Headless Service（gRPC round_robin 需要）
apiVersion: v1
kind: Service
metadata:
  name: spicedb-headless
spec:
  clusterIP: None      # 关键：无虚拟 IP，DNS 直接返回 Pod IP
  selector:
    app: spicedb
  ports:
  - port: 50051
```

```java
// SDK 配置
.target("dns:///spicedb-headless.spicedb.svc.cluster.local:50051")
```

普通 ClusterIP Service 不行 — gRPC 长连接建好后就不换 Pod 了。

## 缓存系统

```
CachedTransport
  │ cache.getOrLoad(key, loader)  ← Caffeine singleFlight
  ▼
TieredCache
  ├─ L1: CaffeineCache (IndexedCache)
  │   ├─ variable Expiry + ±10% TTL jitter 防雪崩
  │   ├─ 二级索引 (resource → keys) O(k) 失效
  │   └─ compute() 原子操作防竞态
  └─ L2: 用户提供 (Redis 等)
```

缓存规则：
- 只缓存 MinimizeLatency + 无 caveat 的 check
- 写前 + 写后双重失效
- 索引先加后写缓存（顺序保证一致性）

## 容错三层

```
第一层：Resilience4jInterceptor（最外层拦截器）
  RateLimiter + Bulkhead

第二层：ResilientTransport（内层装饰器）
  per-resource-type CircuitBreaker + Retry
  全局重试预算：1s 窗口内 < 20%（AtomicLong CAS 保证窗口重置原子性）

第三层：缓存防护
  TTL ±10% jitter 防雪崩
  singleFlight 防穿透
  写前写后双重失效防脏读
```

## 策略分层

```
全局默认（PolicyRegistry.defaultPolicy）
  └── 按 resource type 覆盖（document / folder / group）
        └── 按 permission 覆盖（view / edit / delete 的 cache TTL）
```

PolicyRegistry 构造时预计算合并结果，热路径零分配。

## 生命周期

```
builder.build() 启动：
  CHANNEL    → ManagedChannel (HTTP/2)
  SCHEMA     → 拉取 SpiceDB Schema（3s deadline，失败非致命）
  TRANSPORT  → 组装 7 层装饰器链
  WATCH      → 启动 Watch 流（后台守护线程）
  SCHEDULER  → 定时 Schema 刷新(5min) + 指标轮转(5s) + 缓存大小采样(5s)

close() 关闭：
  scheduler → watch → telemetry flush → transport → channel
```

