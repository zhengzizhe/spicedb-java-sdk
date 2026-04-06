# 已知错误案例（强制阅读）

本文件记录用户明确指出的**错误决策**——"你不该这么做"的记录。不是 bug 清单，是设计判断的教训。新会话修改相关代码前必读，防止重蹈覆辙。

录入标准：用户说了"这样不对"或"不该这么做"才记录，不是自己发现的代码问题。

---

## 连接模型

### ✗ 错误：给 gRPC 加连接池（PooledTransport）

曾经创建了 `PooledTransport` + `channelCount` 配置，让用户建多个 ManagedChannel 做 round-robin。

**为什么错**：gRPC 用 HTTP/2 多路复用，一条连接支持数千并发流。连接池是 JDBC（一连接一查询）的思路，搬到 gRPC 上是多余的。gRPC 自带 DNS 解析 + round_robin，多后端时自动建多条连接。

**正确做法**：一个 ManagedChannel，K8s 用 Headless Service 让 DNS 返回所有 Pod IP。

---

## 重试与熔断

### ✗ 错误：Retry 包 CircuitBreaker

曾经的顺序：`Retry(CircuitBreaker(call))`。一个请求重试 3 次，熔断器记 3 次失败。

**为什么错**：熔断器应该看"业务请求"的成败，不是看"底层调用"的次数。错误顺序导致熔断器过早打开。

**正确做法**：`CircuitBreaker(Retry(call))`——CB 在外，一个业务请求只记 1 次。

### ✗ 错误：RESOURCE_EXHAUSTED 被重试

SpiceDB 返回"我过载了"，SDK 还重试 3 次。

**为什么错**：过载重试 = 火上浇油。

**正确做法**：RESOURCE_EXHAUSTED 映射为不可重试异常。所有不可重试异常必须在 `RetryPolicy.nonRetryableExceptions` 中。

### ✗ 错误：没有全局重试预算

每个请求独立决定是否重试，SpiceDB 过载时 100% 请求都重试，流量放大 3 倍。

**正确做法**：全局重试预算，1 秒窗口内重试次数不超过请求总量的 20%。

---

## 缓存

### ✗ 错误：热路径调 cache.size() / cleanUp()

每次 check() 都调 `CaffeineCache.size()`，内部触发 `cleanUp()`，百万级缓存下严重卡顿。

**正确做法**：热路径只用 `estimatedSize()`。size 采样放到定时任务（每 5 秒）。

### ✗ 错误：缓存没有 singleFlight

100 个并发请求同一个 key，缓存 miss 时 100 个全穿透到 SpiceDB。

**正确做法**：Caffeine `cache.get(key, loader)` 天生 singleFlight，同一个 key 只有一个线程加载。

### ✗ 错误：TTL 没有抖动

所有同类 key 获得完全相同的 TTL，同时过期，缓存雪崩。

**正确做法**：TTL ±10% 随机抖动。

### ✗ 错误：只在写后失效缓存

写操作返回和缓存失效之间有时间窗口，其他线程读到旧缓存。

**正确做法**：写前 + 写后双重失效。

### ✗ 错误：索引操作用 get → modify → put

`CaffeineCache` 的二级索引 `put` 和 `invalidateByIndex` 之间有竞态，导致缓存条目逃逸索引跟踪。

**正确做法**：索引操作用 `ConcurrentHashMap.compute()` 原子操作。先加索引再写缓存。

---

## 人为限制

### ✗ 错误：给写操作加 MAX_BATCH_SIZE = 500

超过 500 条 update 直接报错。

**为什么错**：JDBC、Redis 都不加这种限制。gRPC 超 4MB 自然报 RESOURCE_EXHAUSTED，SDK 不该替用户操心。

**正确做法**：写操作直接透传，不分批不限大小。读操作（checkBulkMulti）可以自动分批，因为无原子性问题。

### ✗ 错误：给流式查询加 DEFAULT_STREAM_LIMIT = 10000

静默截断结果，用户查 10 万条只拿到 1 万条，数据丢了还不报错。

**正确做法**：用户传 limit 就限，不传就不限。

---

## 异常处理

### ✗ 错误：Watch 收到 UNIMPLEMENTED 无限重连

CockroachDB 2 节点不支持 Watch，SpiceDB 返回 UNIMPLEMENTED，SDK 疯狂重连刷屏日志。

**正确做法**：检测 UNIMPLEMENTED / UNAUTHENTICATED / PERMISSION_DENIED 等永久性错误，停止重连，退化到 TTL 缓存过期，只打一条警告。

### ✗ 错误：SchemaLoader 每 5 分钟重试不支持的 API

SpiceDB 不支持 ExperimentalReflectSchema，每 5 分钟报一次 warning。

**正确做法**：首次收到 UNIMPLEMENTED 设 volatile 标志位，后续不再调用。

### ✗ 错误：流式迭代器异常绕过 SDK 异常体系

`readRelationships` / `lookupSubjects` / `lookupResources` 的 `iterator.next()` 抛出的 `StatusRuntimeException` 没经过 `mapGrpcException`。

**正确做法**：整个迭代循环包 try-catch，统一经过 `mapGrpcException` 转换。

### ✗ 错误：熔断器把客户端错误计入失败率

业务代码传错参数（InvalidArgument），熔断器以为 SpiceDB 挂了。

**正确做法**：`CircuitBreakerConfig.ignoreExceptions` 排除所有客户端错误。

### ✗ 错误：降级日志每次都打

DistributedTokenStore 断了，每次 check 都打 warning，高 QPS 下日志爆炸。

**正确做法**：`AtomicBoolean.compareAndSet(true, false)` 只打一次。恢复后重置为 true。

---

## 并发

### ✗ 错误：滑动窗口重置用 volatile long

```java
volatile long lastResetTime;
// 多个线程可能同时进入 if 块，重复重置
if (now - lastResetTime > 1s) { reset(); lastResetTime = now; }
```

**正确做法**：`AtomicLong` + `compareAndSet`，保证只有一个线程执行重置。

### ✗ 错误：stub() 每次 new Metadata

每次 gRPC 调用创建新 Metadata + Interceptor 对象，万级 QPS 下 GC 风暴。

**正确做法**：TraceParentInterceptor 在 channel 级注册一次，`stub()` 只加 deadline。

