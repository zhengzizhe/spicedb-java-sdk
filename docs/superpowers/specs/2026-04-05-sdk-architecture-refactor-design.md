# SDK 内部架构重构设计

## 背景

SDK 当前 81 个文件，接口 8 个，泛型 0 个，抽象类 1 个。核心问题：
- 方法参数大量裸 String（SdkTransport.check 6 个 String 参数）
- 类字段过多（AuthCsesClient 18 字段、Builder 24 字段）
- 缓存接口绑死具体类型（CheckCache 5 个 String 参数）
- 拦截器只能观察不能修改/短路（SdkInterceptor before/after）
- 事件系统无类型安全（单一 SdkEventData 塞所有事件）
- 属性传递靠 Object 强转（OperationContext.getAttribute 返回 Object）

## 约束

- **业务 API 不动**：client.check()、client.on().grant()、client.batch() 等签名不变
- **不照搬 Netty**：借鉴设计原则，不复制不适合同步请求-响应场景的 Pipeline/Handler 模式
- **借鉴来源**：OkHttp Chain、gRPC CallOptions.Key\<T\>、Caffeine Cache\<K,V\>、AWS ExecutionAttribute\<T\>、Lettuce 接口拆分

## 改动概览

| 优先级 | 改动 | 影响范围 | 借鉴 |
|--------|------|---------|------|
| P0 | 值对象体系 | ~100 个方法签名 | Java record |
| P0 | 类字段聚合 | AuthCsesClient, Builder, ResourceHandle 等 | 组合优于平铺 |
| P1 | 泛型缓存 Cache\<K,V\> | CheckCache + 3 个实现 | Caffeine |
| P1 | 类型安全属性 AttributeKey\<T\> | OperationContext | gRPC CallOptions.Key, AWS ExecutionAttribute |
| P1 | 拦截器 Chain 模式 | SdkInterceptor + InterceptorTransport | OkHttp Interceptor.Chain |
| P2 | Transport 接口拆分 | SdkTransport + ForwardingTransport + 7 装饰器 | Lettuce 16 子接口 |
| P2 | 类型安全事件 | SdkEventBus + SdkEvent + 所有 listener | Java 17 sealed |

---

## P0：值对象体系

### 新增值对象

```java
package com.authcses.sdk.model;

// 资源引用 — 替代 (String resourceType, String resourceId) 散装参数
public record ResourceRef<T>(String type, String id) {
    public static <T> ResourceRef<T> of(String type, String id) {
        Objects.requireNonNull(type); Objects.requireNonNull(id);
        return new ResourceRef<>(type, id);
    }
}

// 主体引用 — 替代 (String subjectType, String subjectId, String subjectRelation)
public record SubjectRef(String type, String id, @Nullable String relation) {
    public static SubjectRef user(String id) { return new SubjectRef("user", id, null); }
    public static SubjectRef wildcard(String type) { return new SubjectRef(type, "*", null); }
    public static SubjectRef of(String type, String id, @Nullable String relation) { ... }
    public static SubjectRef parse(String ref) { ... } // "department:eng#all_members"
}

// 权限 — 替代 String permission
public record Permission(String name) {
    public static Permission of(String name) { return new Permission(name); }
}

// 关系 — 替代 String relation（语义区分于 Permission）
public record Relation(String name) {
    public static Relation of(String name) { return new Relation(name); }
}

// 缓存键 — 替代 5 个 String 拼接
public record CheckKey(ResourceRef<?> resource, Permission permission, SubjectRef subject) {
    public static CheckKey of(ResourceRef<?> resource, Permission permission, SubjectRef subject) {
        return new CheckKey(resource, permission, subject);
    }
    // 供 IndexedCache 使用的资源索引键
    public String resourceIndex() { return resource.type() + ":" + resource.id(); }
}
```

### 请求对象

```java
// 替代 check 方法的 6-7 个参数
public record CheckRequest(
    ResourceRef<?> resource,
    Permission permission,
    SubjectRef subject,
    Consistency consistency,
    @Nullable Map<String, Object> caveatContext
) {
    // 便捷构造
    public static CheckRequest of(ResourceRef<?> r, Permission p, SubjectRef s, Consistency c) {
        return new CheckRequest(r, p, s, c, null);
    }
    // 从当前参数构造（内部桥接用）
    public static CheckRequest from(String resType, String resId, String perm,
                                     String subType, String subId, Consistency c) {
        return new CheckRequest(ResourceRef.of(resType, resId), Permission.of(perm),
                                SubjectRef.of(subType, subId, null), c, null);
    }
    public CheckKey toKey() { return new CheckKey(resource, permission, subject); }
}

// 替代 writeRelationships 的 RelationshipUpdate 散装字段
public record WriteRequest(List<RelationshipUpdate> updates) {}

// 替代 lookup 方法的 5 个参数
public record LookupRequest(
    String resourceType,       // lookup 不针对具体资源 ID
    Permission permission,
    @Nullable SubjectRef subject, // lookupResources 需要, lookupSubjects 不需要
    @Nullable ResourceRef<?> resource, // lookupSubjects 需要
    int limit
) {}
```

### RelationshipUpdate 字段聚合

```java
// 现在 10 个字段 → 改后 6 个
public record RelationshipUpdate(
    Operation operation,
    ResourceRef<?> resource,        // 替代 resourceType + resourceId
    Relation relation,              // 替代 String relation
    SubjectRef subject,             // 替代 subjectType + subjectId + subjectRelation
    @Nullable CaveatRef caveat,     // 替代 caveatName + caveatContext
    @Nullable Instant expiresAt
) {
    public enum Operation { TOUCH, DELETE, CREATE }
}

public record CaveatRef(String name, @Nullable Map<String, Object> context) {}
```

### SdkTransport 方法签名变化

```java
// 现在
CheckResult check(String resourceType, String resourceId, String permission,
                  String subjectType, String subjectId, Consistency consistency);

// 改后
CheckResult check(CheckRequest request);
```

7 个装饰器的 ~84 个覆写方法全部跟着简化。

### 桥接层

业务 API 不变，在 AuthCsesClient / ResourceFactory 内部做转换：

```java
// AuthCsesClient.check() — 对外签名不变
public boolean check(String type, String id, String permission, String userId,
                     Consistency consistency) {
    // L0 缓存快速路径
    if (consistency instanceof Consistency.MinimizeLatency && caching.checkCache() != null) {
        var key = CheckKey.of(ResourceRef.of(type, id), Permission.of(permission),
                              SubjectRef.user(userId));
        var cached = caching.checkCache().getIfPresent(key);
        if (cached != null) return cached.hasPermission();
    }
    // 转换为内部请求对象
    var request = CheckRequest.from(type, id, permission, defaultSubjectType, userId, consistency);
    return transport.check(request).hasPermission();
}
```

---

## P0：类字段聚合

### AuthCsesClient：18 字段 → 6 字段

```java
public class AuthCsesClient implements AutoCloseable {
    private final SdkTransport transport;
    private final SdkInfrastructure infra;
    private final SdkObservability observability;
    private final SdkCaching caching;
    private final SdkConfig config;
    private final ConcurrentHashMap<String, ResourceFactory> factories = new ConcurrentHashMap<>();
}
```

#### 组件定义

```java
// 基础设施 — 生命周期资源
public record SdkInfrastructure(
    ManagedChannel channel,
    ScheduledExecutorService scheduler,
    Executor asyncExecutor,
    LifecycleManager lifecycle,
    AtomicBoolean closed
) implements AutoCloseable {
    public boolean isClosed() { return closed.get(); }
    public boolean markClosed() { return closed.compareAndSet(false, true); }

    @Override
    public void close() {
        if (markClosed()) {
            scheduler.shutdown();
            // ... 有序关闭
        }
    }
}

// 可观测性 — 指标 + 事件 + 遥测
public record SdkObservability(
    SdkMetrics metrics,
    SdkEventBus eventBus,
    @Nullable TelemetryReporter telemetry
) {
    public void publishEvent(SdkEvent event) { eventBus.publish(event); }
}

// 缓存体系 — 缓存 + Watch + Schema
public record SdkCaching(
    @Nullable Cache<CheckKey, CheckResult> checkCache,
    SchemaCache schemaCache,
    @Nullable WatchCacheInvalidator watchInvalidator,
    @Nullable WatchDispatcher watchDispatcher
) {
    public CacheHandle handle() { return new CacheHandle(checkCache); }
}

// 不可变配置
public record SdkConfig(
    String defaultSubjectType,
    PolicyRegistry policies,
    boolean coalescingEnabled,
    boolean virtualThreads
) {}
```

### Builder：24 字段 → 4 个配置组

```java
public static class Builder {
    // 4 个配置组替代 24 个散装字段
    private final ConnectionConfig connection = new ConnectionConfig();
    private final CacheConfig cache = new CacheConfig();
    private final FeatureConfig features = new FeatureConfig();
    private final ExtensionConfig extensions = new ExtensionConfig();

    // ConnectionConfig 内聚连接相关
    public static class ConnectionConfig {
        private String target;
        private List<String> targets;
        private String presharedKey;
        private boolean useTls = false;
        private String loadBalancing = "round_robin";
        private Duration keepAliveTime = Duration.ofSeconds(30);
        private Duration requestTimeout = Duration.ofSeconds(5);
    }

    // CacheConfig 内聚缓存相关
    public static class CacheConfig {
        private boolean enabled = false;
        private long maxSize = 100_000;
        private boolean watchInvalidation = false;
    }

    // FeatureConfig 内聚功能开关
    public static class FeatureConfig {
        private boolean coalescingEnabled = true;
        private boolean useVirtualThreads = false;
        private boolean registerShutdownHook = false;
        private boolean telemetryEnabled = false;
        private String defaultSubjectType = "user";
    }

    // ExtensionConfig 内聚扩展点
    public static class ExtensionConfig {
        private PolicyRegistry policyRegistry;
        private SdkEventBus eventBus;
        private SdkComponents components;
        private final List<SdkInterceptor> interceptors = new ArrayList<>();
        private final List<WatchStrategy> watchStrategies = new ArrayList<>();
    }

    // 向后兼容的 flat setter 内部委托给配置组
    public Builder target(String t) { connection.target = t; return this; }
    public Builder cacheEnabled(boolean e) { cache.enabled = e; return this; }
    // ...
}
```

### ResourceHandle：字段聚合

```java
// 现在 ~10 个字段
// 改后
public class ResourceHandle {
    private final ResourceRef<?> ref;          // 替代 resourceType + resourceId
    private final SdkTransport transport;
    private final SdkConfig config;            // 替代 defaultSubjectType 等散装配置
    private final Executor asyncExecutor;
}
```

### ResilientTransport：字段聚合

```java
// 现在 11 个字段
// 改后
public class ResilientTransport extends ForwardingTransport {
    private final SdkTransport delegate;
    private final ResilienceRegistry registry;  // 聚合 breakers + retries 的 ConcurrentHashMap
    private final SdkObservability observability;

    // ResilienceRegistry 封装了 per-resource-type 的熔断器和重试器创建逻辑
    static class ResilienceRegistry {
        private final PolicyRegistry policies;
        private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Retry> retries = new ConcurrentHashMap<>();

        CircuitBreaker breakerFor(String resourceType) { ... }
        Retry retryFor(String resourceType) { ... }
    }
}
```

---

## P1：泛型缓存 Cache\<K,V\>

```java
package com.authcses.sdk.cache;

// 通用缓存接口
public interface Cache<K, V> {
    Optional<V> get(K key);
    @Nullable V getIfPresent(K key);  // 高性能路径，无 Optional 开销
    void put(K key, V value);
    void invalidate(K key);
    void invalidateAll(Predicate<K> filter);
    void invalidateAll();
    long size();
    CacheStats stats();
}

public record CacheStats(long hitCount, long missCount, long evictionCount) {
    public double hitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 1.0 : (double) hitCount / total;
    }
}

// 带二级索引的缓存 — 支持按资源 O(k) 失效
public interface IndexedCache<K, V> extends Cache<K, V> {
    void invalidateByIndex(String indexKey);
}

// Caffeine 实现
public class CaffeineCache<K, V> implements IndexedCache<K, V> {
    private final com.github.benmanes.caffeine.cache.Cache<K, V> delegate;
    private final ConcurrentHashMap<String, Set<K>> index;
    private final Function<K, String> indexKeyExtractor;  // CheckKey → "document:doc-1"
    // ...
}

// 空实现
public class NoopCache<K, V> implements Cache<K, V> { ... }

// 两级缓存
public class TieredCache<K, V> implements Cache<K, V> {
    private final Cache<K, V> l1;
    private final Cache<K, V> l2;
    // get: l1 → l2 → miss; put/invalidate: both
}
```

CheckCache 接口删除，改为 `Cache<CheckKey, CheckResult>`。

---

## P1：类型安全属性 AttributeKey\<T\>

借鉴 gRPC `CallOptions.Key<T>` + AWS `ExecutionAttribute<T>`：

```java
package com.authcses.sdk.spi;

public final class AttributeKey<T> {
    private final String name;
    private final Class<T> type;
    private final @Nullable T defaultValue;

    private AttributeKey(String name, Class<T> type, @Nullable T defaultValue) {
        this.name = name; this.type = type; this.defaultValue = defaultValue;
    }

    public static <T> AttributeKey<T> of(String name, Class<T> type) {
        return new AttributeKey<>(name, type, null);
    }

    public static <T> AttributeKey<T> withDefault(String name, Class<T> type, T defaultValue) {
        return new AttributeKey<>(name, type, defaultValue);
    }

    // 常用预定义键
    public static final AttributeKey<String> TRACE_ID = of("traceId", String.class);
    public static final AttributeKey<Instant> START_TIME = of("startTime", Instant.class);
    public static final AttributeKey<Duration> TIMEOUT = withDefault("timeout", Duration.class, Duration.ofSeconds(5));
}
```

OperationContext 改造：

```java
// 现在
public Object getAttribute(String key);
public void setAttribute(String key, Object value);

// 改后
public <T> T attr(AttributeKey<T> key);
public <T> void attr(AttributeKey<T> key, T value);
```

---

## P1：拦截器 Chain 模式

借鉴 OkHttp Interceptor.Chain，同步请求-响应场景的标准解法：

```java
package com.authcses.sdk.spi;

public interface SdkInterceptor {

    // 主方法 — 类似 OkHttp Interceptor.intercept(Chain)
    CheckResult interceptCheck(CheckChain chain);

    // 写操作拦截（默认透传）
    default WriteResult interceptWrite(WriteChain chain) {
        return chain.proceed(chain.request());
    }

    // Check 链
    interface CheckChain {
        CheckRequest request();
        CheckResult proceed(CheckRequest request);  // 可修改 request 再传递
        <T> T attr(AttributeKey<T> key);
        <T> void attr(AttributeKey<T> key, T value);
    }

    // Write 链
    interface WriteChain {
        WriteRequest request();
        WriteResult proceed(WriteRequest request);
        <T> T attr(AttributeKey<T> key);
    }
}
```

**能力对比：**

| 能力 | 现在 (before/after) | 改后 (Chain) |
|------|-------------------|-------------|
| 观察请求 | ✅ | ✅ |
| 修改请求参数 | ❌ | ✅ chain.proceed(modifiedRequest) |
| 短路返回缓存结果 | ❌ | ✅ 直接 return，不调 proceed |
| 异常处理 | ❌ | ✅ try { chain.proceed() } catch |
| 计时 | 需要 before+after 配合 | ✅ proceed 前后直接测 |

InterceptorTransport 内部实现（类似 OkHttp RealInterceptorChain）：

```java
class RealCheckChain implements SdkInterceptor.CheckChain {
    private final List<SdkInterceptor> interceptors;
    private final int index;
    private final CheckRequest request;
    private final SdkTransport finalTransport; // 链末端的实际 transport

    @Override
    public CheckResult proceed(CheckRequest request) {
        if (index >= interceptors.size()) {
            return finalTransport.check(request); // 到达链末端，执行实际调用
        }
        var next = new RealCheckChain(interceptors, index + 1, request, finalTransport);
        return interceptors.get(index).interceptCheck(next);
    }
}
```

---

## P2：Transport 接口拆分

```java
// 按职责拆分为子接口
public interface CheckTransport {
    CheckResult check(CheckRequest request);
    BulkCheckResult checkBulk(CheckRequest request, List<SubjectRef> subjects);
    List<CheckResult> checkBulkMulti(List<CheckRequest> requests);
}

public interface WriteTransport {
    WriteResult writeRelationships(WriteRequest request);
    WriteResult deleteRelationships(WriteRequest request);
    WriteResult deleteByFilter(ResourceRef<?> resource, SubjectRef subject, @Nullable Relation relation);
}

public interface LookupTransport {
    List<SubjectRef> lookupSubjects(LookupRequest request);
    List<ResourceRef<?>> lookupResources(LookupRequest request);
}

public interface ReadTransport {
    List<Tuple> readRelationships(ResourceRef<?> resource, @Nullable Relation relation);
}

public interface ExpandTransport {
    ExpandTree expand(ResourceRef<?> resource, Permission permission);
}

// 组合接口（现有 SdkTransport 的替代）
public interface Transport extends CheckTransport, WriteTransport, LookupTransport,
                                   ReadTransport, ExpandTransport, AutoCloseable {}

// ForwardingTransport 实现 Transport，默认全部委托
public abstract class ForwardingTransport implements Transport {
    protected abstract Transport delegate();
    // 所有方法默认转发...
}
```

---

## P2：类型安全事件

```java
package com.authcses.sdk.event;

// 事件基类 — sealed 穷举
public sealed interface SdkEvent permits
        ClientEvent, CacheEvent, CircuitEvent, TransportEvent, WatchEvent {
    Instant timestamp();
}

public record ClientEvent(Instant timestamp, Type type) implements SdkEvent {
    public enum Type { READY, STOPPING, STOPPED, SCHEMA_REFRESHED }
}

public record CacheEvent(Instant timestamp, Type type,
                          @Nullable CheckKey key, @Nullable RemovalCause cause) implements SdkEvent {
    public enum Type { HIT, MISS, EVICTION, INVALIDATION }
}

public record CircuitEvent(Instant timestamp, String resourceType,
                            State from, State to) implements SdkEvent {
    public enum State { CLOSED, OPEN, HALF_OPEN }
}

public record TransportEvent(Instant timestamp, SdkAction action,
                              @Nullable ResourceRef<?> resource, Duration latency,
                              boolean success) implements SdkEvent {}

public record WatchEvent(Instant timestamp,
                          List<RelationshipChange> changes) implements SdkEvent {}

// 类型安全监听
@FunctionalInterface
public interface EventListener<E extends SdkEvent> {
    void onEvent(E event);
}

public interface EventBus {
    <E extends SdkEvent> Registration subscribe(Class<E> type, EventListener<E> listener);
    void publish(SdkEvent event);

    interface Registration extends AutoCloseable {
        void unsubscribe();
        @Override default void close() { unsubscribe(); }
    }
}
```

---

## 实施顺序

分 4 个 phase，每个 phase 独立可交付：

### Phase 1：值对象 + 字段聚合（P0）
1. 新增 model 包值对象：ResourceRef, SubjectRef, Permission, Relation, CheckKey, CaveatRef
2. 新增请求对象：CheckRequest, WriteRequest, LookupRequest
3. 改造 RelationshipUpdate 字段
4. 新增组件聚合：SdkInfrastructure, SdkObservability, SdkCaching, SdkConfig
5. 改造 AuthCsesClient 字段 + 构造函数
6. 改造 Builder 字段
7. 改造 SdkTransport 接口方法签名
8. 改造 ForwardingTransport + 7 个装饰器
9. 改造 GrpcTransport + InMemoryTransport
10. 桥接层：AuthCsesClient/ResourceFactory 对外 API 不变，内部用新值对象

### Phase 2：泛型缓存 + AttributeKey（P1）
1. 新增 Cache\<K,V\> 接口 + CacheStats
2. 新增 IndexedCache\<K,V\> 接口
3. 改造 CaffeineCheckCache → CaffeineCache\<CheckKey, CheckResult\>
4. 改造 TwoLevelCache → TieredCache\<K,V\>
5. 删除旧 CheckCache 接口
6. 新增 AttributeKey\<T\>
7. 改造 OperationContext

### Phase 3：拦截器 Chain（P1）
1. 重新设计 SdkInterceptor 接口（Chain 模式）
2. 实现 RealCheckChain / RealWriteChain
3. 改造 InterceptorTransport
4. 改造内置拦截器：ValidationInterceptor, DebugInterceptor, LogRedactionInterceptor, Resilience4jInterceptor

### Phase 4：Transport 拆分 + 事件（P2）
1. 拆分 SdkTransport → CheckTransport + WriteTransport + LookupTransport + ReadTransport + ExpandTransport + Transport
2. 改造 ForwardingTransport
3. 新增 sealed SdkEvent 层次
4. 新增 EventListener\<E\>, EventBus
5. 改造 SdkEventBus → DefaultEventBus
6. 改造所有事件发布点

## 风险控制

- 每个 phase 完成后跑全量测试（test-app 的所有测试）
- Phase 1 改动最大但最机械（批量改签名），风险可控
- Phase 3 拦截器模式变化影响用户 SPI，需要在 CLAUDE.md 的"已删除 V1 代码"中记录旧接口
- 向后兼容：AuthCsesClient 的 public API 签名不变
