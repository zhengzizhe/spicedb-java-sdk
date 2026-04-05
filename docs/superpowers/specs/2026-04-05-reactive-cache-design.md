# SDK 反应式缓存 + 权限事件流 设计文档

> **状态**: 草案，待后续迭代完善

**Goal:** 为 SpiceDB Java SDK 设计生产级缓存 + 权限变更事件流系统，覆盖单 JVM / 多 Pod / 多区域部署。

---

## 问题域

### 已验证的事实（来自压测）

1. SpiceDB dispatch cache 按 `(resource, permission, subject, revision)` 缓存，低 QPS 命中率 ≈ 0%
2. SDK L1 缓存在真实访问模式下命中率 99%+，QPS 130 万
3. 三个硬问题：冷启动、跨实例失效、权限变更扩散
4. Google Leopard 只解决组成员关系预计算，SpiceDB 开源版不实现
5. AuthZed Materialize 是闭源商业产品

### 职责边界

- **SDK**: 快速 check（L1+L2 缓存）+ 可靠的权限变更事件流 + 批量工具
- **业务**: 维护 ES 索引 + 列表查询 + UI

---

## 架构：三个子系统

```
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                         │
│  ┌─────────────────┐   ┌──────────────────┐   ┌─────────────────────┐  │
│  │  Reactive Cache  │   │  Impact Analyzer  │   │  Permission Stream  │  │
│  │                  │   │                   │   │                     │  │
│  │  L1 → L2 → SpDB │◄──│  关系变更 → 权限影响 │──►│  typed events → 业务 │  │
│  │  + InvalidBus    │   │  + 展开策略        │   │  + 背压 + 重试       │  │
│  │  + Warmup        │   │  + 深度控制        │   │  + 投影注册          │  │
│  └─────────────────┘   └──────────────────┘   └─────────────────────┘  │
│           │                      │                       │              │
│           └──────────────────────┴───────────────────────┘              │
│                          InvalidationBus (内部事件总线)                   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 子系统 1：Reactive Cache

### 读路径

```
check(doc-1, view, alice)
  → L1 Caffeine (per-JVM, 1µs)
  → L2 Redis/pluggable (<1ms)
  → SpiceDB (1-5ms)
  → write-back L1 + L2
```

### 新增组件

#### InvalidationBus — 跨实例失效总线

```java
sealed interface InvalidationEvent {
    Instant timestamp();
    record ResourceChanged(String resourceType, String resourceId, Instant timestamp)
        implements InvalidationEvent {}
    record SubjectChanged(String subjectType, String subjectId, Instant timestamp)
        implements InvalidationEvent {}
    record ScopeInvalidated(String scope, String reason, Instant timestamp)
        implements InvalidationEvent {}
}

interface InvalidationBus {
    void publish(InvalidationEvent event);
    Registration subscribe(InvalidationListener listener);
    // 实现：LocalBus（单 JVM）、RedisBus（跨实例）、CompositeBus
}
```

#### DualIndexedCache — 资源 + 主体双索引

现有 CaffeineCache 只有资源索引。新增主体索引，支持 "alice 加入组 → 失效 alice 所有 entries"。

```java
class DualIndexedCache<K, V> implements IndexedCache<K, V> {
    void invalidateByResourceIndex(String resourceKey);  // O(k)
    void invalidateBySubjectIndex(String subjectKey);    // O(k)
}
```

#### WarmupEngine — 批量预热

```java
client.cache().warmup(warmup -> warmup
    .forUser("alice")
    .resources("document", List.of("doc-1", "doc-2", ..., "doc-20"))
    .permissions("view", "edit", "manage")
    .execute());
// 内部用 checkBulkMulti，1 次 gRPC 调用填充 L1+L2
```

---

## 子系统 2：Impact Analyzer

### 三种变更场景

| 变更类型 | 例子 | 影响范围 | 计算方式 |
|---------|------|---------|---------|
| 直接授权 | grant alice viewer on doc-1 | 1:1 | 无需展开 |
| 容器权限 | grant editors on folder-1 | 子资源 × 成员 | LookupResources 展开 |
| 组成员变更 | add alice to editors | 组关联资源 × alice | 反向查询 |

### 分析结果（sealed interface）

```java
sealed interface PermissionImpact {
    // 精确：明确知道哪些 (user, resource, permission) 变了
    record Precise(List<PermissionDelta> deltas) implements PermissionImpact {}
    // 有界：展开到上限，可能不完整
    record Bounded(List<PermissionDelta> deltas, boolean truncated) implements PermissionImpact {}
    // 不可展开：影响太广，只给范围
    record Scope(String resourceType, String scopeId, Set<String> permissions)
        implements PermissionImpact {}
}

record PermissionDelta(SubjectRef subject, ResourceRef resource,
                       Permission permission, DeltaType type) {}
enum DeltaType { GAINED, REVOKED }
```

### 有界展开配置

```java
record AnalysisConfig(
    int maxResourceExpansion,   // 默认 1000
    int maxSubjectExpansion,    // 默认 1000
    Set<String> permissions,    // 只分析指定权限
    boolean async               // 异步（不阻塞写入）
) {}
```

---

## 子系统 3：Permission Stream

### 投影注册

```java
client.permissions().project(ProjectionConfig.builder()
    .resourceType("document")
    .permissions("view", "edit")
    .handler(new ProjectionHandler() {
        void onPermissionChanged(PermissionDelta delta) {
            // 精确变更 → 更新 ES 索引
        }
        void onScopeChanged(PermissionImpact.Scope scope) {
            // 范围变更 → 触发 re-index
        }
    })
    .build());
```

### 事件流水线

```
SpiceDB Watch → RelationshipChange
  → ImpactAnalyzer.analyze() → 展开权限影响
  → PermissionStream.dispatch() → 推送给注册的投影
  → InvalidationBus.publish() → 同时失效缓存
```

### 背压策略

- 有界队列（默认 10000）
- 满了 → 降级为 Scope 事件
- 不丢事件也不阻塞 Watch 线程

---

## 大数据量处理策略

| 场景 | 行为 |
|------|------|
| 10 万用户登录高峰 | WarmupEngine 限流 + L2 跨实例共享 |
| space 级权限变更（5 万文档） | 返回 Scope，业务自己 re-index |
| 大组成员变更（1 万人） | SubjectChanged 失效 + Bounded 截断 |
| 日常直接授权 | Precise 精确推送，1:1，零开销 |

---

## 不做什么

- 不做全量权限物化（存储爆炸）
- 不做 Leopard skip list（SpiceDB 架构不支持）
- 不替代业务 ES（SDK 只推事件）
- ImpactAnalyzer 不保证 100% 精确（超限返回 Scope）

---

## 待细化

- Redis L2 Cache 具体实现（序列化格式、key 命名、TTL 策略）
- InvalidationBus 的 Redis pub/sub channel 设计
- ImpactAnalyzer 的展开策略具体算法
- 多区域部署下的 L2 一致性
- WarmupEngine 的限流和分批策略
- 完整 API 设计和类图
