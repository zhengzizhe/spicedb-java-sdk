# 编码规范（强制遵守）

本文件中的所有规则为强制规范，任何代码变更必须遵守。违反即视为未完成。

---

## 常量必须用枚举

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

---

## 线程安全

SDK 在高并发环境下运行，所有公开类必须线程安全。

| 场景 | 正确做法 | 禁止 |
|------|---------|------|
| 计数器 | `LongAdder` | `AtomicLong`（高竞争下 LongAdder 更快） |
| 并发 Map | `ConcurrentHashMap` | `Collections.synchronizedMap` |
| 标志位 | `AtomicBoolean` | `volatile boolean`（需要 CAS 时） |
| 滑动窗口重置 | `AtomicLong` + `compareAndSet` | `volatile long`（竞态） |
| 单次降级日志 | `AtomicBoolean.compareAndSet(true, false)` | 每次都 log（轰炸） |
| 索引增删 | `ConcurrentHashMap.compute()` | get → modify → put（竞态） |

关键类的线程安全保证：
- `AuthCsesClient` — 全应用共享单例，构造后不可变
- `ResourceFactory` — volatile 字段，可安全跨线程共享
- `ResourceHandle` — 无状态，每次操作独立
- Transport 装饰器 — ConcurrentHashMap / AtomicReference / LongAdder
- `CircuitBreaker` — Resilience4j 管理，computeIfAbsent 创建

---

## 性能红线

| 维度 | 红线 |
|------|------|
| check 缓存命中 | < 1µs |
| check 缓存未命中（单节点 SpiceDB） | < 10ms |
| Transport 链穿透开销（7 层装饰器） | < 5µs |
| 缓存失效 invalidateResource() | O(k) 不是 O(n)（二级索引） |
| CoalescingTransport 并发合并 | putIfAbsent + remove(key, myFuture) 防删错 |
| TelemetryReporter | 非阻塞 offer，满了直接丢 |

### 热路径禁止事项

- `cache.size()` / `cache.cleanUp()` — 用定时采样（每 5s）
- `new Metadata()` / `MetadataUtils.newAttachHeadersInterceptor()` — 用 channel 级 ClientInterceptor
- 字符串拼接做 Map key — 用 record 自动 equals/hashCode

---

## 缓存规则

- **只缓存 MinimizeLatency + 无 caveat 的 check** — SESSION/Full 一致性必须穿透到 SpiceDB
- **singleFlight** — 用 Caffeine `cache.get(key, loader)` 保证同 key 只有一个线程加载
- **TTL ±10% jitter** — 防止缓存雪崩（同类 key 同时过期）
- **写前 + 写后双重失效** — 写前悲观失效防脏读，写后确认失效
- **索引操作用 `compute()` 原子操作** — 防止 put/invalidateByIndex/removeFromIndex 竞态
- **先加索引再写缓存** — 顺序保证 invalidateByIndex 不会遗漏

---

## 批量操作

- 写操作（writeRelationships / deleteRelationships）**直接透传 gRPC，不分批、不限大小**
- 读操作（checkBulkMulti）自动分批 500/批（对调用方透明，无原子性问题）
- gRPC 超 4MB 自然返回 RESOURCE_EXHAUSTED，SDK 不替用户限制
- **禁止替用户做限制** — 像 JDBC/Redis 一样，协议层自己管

---

## 依赖边界

SDK 依赖：authzed-grpc + Jackson + Resilience4j + HdrHistogram + OpenTelemetry API

**禁止依赖 platform 代码。**

Caffeine 是 compileOnly，用户不引入时自动降级为 NoopCache。
