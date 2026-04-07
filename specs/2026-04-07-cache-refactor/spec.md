# 缓存层重构：L1 Caffeine + L2 Redis，移除自定义缓存 SPI

**日期**：2026-04-07
**动机**：当前缓存层存在三个问题——(1) TieredCache 不实现 IndexedCache 导致 Watch 失效走 O(n) 全量扫描；(2) 通用 Cache SPI 导致 L2 实现质量不可控（如 Redis 的 invalidateAll(Predicate) 需拉全量 key 回 JVM）；(3) 配置路径过于间接（SdkComponents.l2Cache()）。

---

## req-1: 移除通用 L2 缓存 SPI

移除 `SdkComponents.l2Cache()` 字段。SDK 不再接受任意 `Cache<CheckKey, CheckResult>` 作为 L2。

**验证标准**：`SdkComponents` 中无 `l2Cache` 相关字段和方法，编译通过。

## req-2: 直接依赖 Lettuce

添加 `io.lettuce:lettuce-core` 作为 `compileOnly` 依赖（与 Caffeine 同等待遇）。SDK 内部直接使用 Lettuce 的 `RedisCommands<String, String>` API。

**验证标准**：`build.gradle` 包含 Lettuce compileOnly 依赖；不引入 Lettuce 时 SDK 正常工作（L1 only）。

## req-3: Redis Hash 数据结构

Redis 中使用 Hash 结构存储 check 结果：

```
Key:   authx:check:{resourceType}:{resourceId}
Field: {permission}:{subjectType}:{subjectId}
Value: {permissionship}|{zedToken}|{expiresAt}
```

- `put`: `HSET key field value` + `EXPIRE key ttlSeconds`
- `get`: `HGET key field` → 反序列化
- 按资源失效: `DEL key` → O(1) 清除该资源所有条目
- TTL 由 `EXPIRE` 设置在整个 Hash key 上，所有同资源条目共享同一过期时间

**验证标准**：Redis 中的数据结构符合上述格式；单个 DEL 命令删除资源下所有缓存条目。

## req-4: RedisCacheAdapter 实现 IndexedCache

新建 `RedisCacheAdapter implements IndexedCache<CheckKey, CheckResult>`：

| 方法 | Redis 操作 |
|---|---|
| `get(key)` | `HGET authx:check:{resType}:{resId} {perm}:{subjType}:{subjId}` |
| `getIfPresent(key)` | 同上 |
| `put(key, value)` | `HSET` + `EXPIRE` |
| `getOrLoad(key, loader)` | `HGET`，miss 时调 loader 并 `HSET` |
| `invalidate(key)` | `HDEL authx:check:{resType}:{resId} {field}` |
| `invalidateByIndex(indexKey)` | `DEL authx:check:{indexKey}` → O(1) |
| `invalidateAll(Predicate)` | 不支持，抛 `UnsupportedOperationException` |
| `invalidateAll()` | 不实现（Redis 全量删除不安全） |
| `stats()` | 本地 LongAdder 计数 hit/miss/eviction |
| `size()` | 返回 -1（Redis 无法高效统计） |

**验证标准**：所有方法有对应单元测试；`invalidateByIndex` 调用单条 `DEL` 命令。

## req-5: TieredCache 实现 IndexedCache

`TieredCache<K,V>` 从 `implements Cache<K,V>` 改为 `implements IndexedCache<K,V>`：

```java
@Override
public void invalidateByIndex(String indexKey) {
    if (l1 instanceof IndexedCache<K,V> idx) idx.invalidateByIndex(indexKey);
    if (l2 instanceof IndexedCache<K,V> idx) idx.invalidateByIndex(indexKey);
}
```

**验证标准**：`TieredCache implements IndexedCache`；Watch 失效路径不再走 `invalidateAll(Predicate)`。

## req-6: Watch 失效路径修复

WatchCacheInvalidator 的失效逻辑保持不变（已有 `instanceof IndexedCache` 检查）。修复后：

- 无 L2：cache = CaffeineCache (IndexedCache) → `invalidateByIndex` → O(k)
- 有 L2：cache = TieredCache (IndexedCache) → `invalidateByIndex` → L1 O(k) + L2 O(1)

**验证标准**：Watch 收到变更后，L1 和 L2 均通过 `invalidateByIndex` 失效；无 `invalidateAll(Predicate)` 调用。

## req-7: 配置 API 简化

Builder 的 CacheConfig 增加 Redis 配置：

```java
AuthxClient.builder()
    .cache(c -> c
        .enabled(true)
        .maxSize(10_000)                        // L1 Caffeine max size
        .redis(redisClient)                     // io.lettuce.core.RedisClient
        .redisTtl(Duration.ofSeconds(30)))      // L2 Redis TTL
```

不传 `redis()` 则只有 L1。传了 `redis()` 但 Lettuce 不在 classpath → `NoClassDefFoundError` 捕获 → 明确 WARNING 日志 + 降级为 L1 only。

**验证标准**：Builder API 编译通过；无 redis 参数时仅 L1；有 redis 参数时 L1+L2。

## req-8: 运行时检测与降级

| 场景 | 行为 |
|---|---|
| 未启用缓存 | NoopCache，无 L1/L2 |
| 启用缓存，Caffeine 在 classpath | L1 CaffeineCache |
| 启用缓存，Caffeine 不在 classpath | WARNING 日志，降级 NoopCache |
| 启用缓存 + redis()，Lettuce 在 classpath | L1 CaffeineCache + L2 RedisCacheAdapter → TieredCache |
| 启用缓存 + redis()，Lettuce 不在 classpath | WARNING 日志，降级 L1 only |
| 启用缓存 + redis()，Caffeine 不在 classpath | WARNING 日志，降级 NoopCache（L2 alone 无意义） |

**验证标准**：每种场景有对应测试。

## req-9: 多实例 Watch 失效正确性

多 SDK 实例共享同一 Redis L2 时：

1. 实例 A 写入 → SpiceDB 通知所有 Watch 订阅者
2. 实例 A 的 Watch → 清 A 的 L1 + DEL Redis Hash key
3. 实例 B 的 Watch → 清 B 的 L1 + DEL Redis Hash key（幂等，key 已被 A 删除则 DEL 返回 0）

不存在"L2 旧数据回填 L1"的问题，因为 L2 和 L1 同时被清除。

**验证标准**：测试模拟两个 WatchCacheInvalidator 共享同一 RedisCacheAdapter，验证两次 invalidateByIndex 均不报错。

## req-10: 清理废弃代码

- 删除 `SdkComponents.l2Cache()` 字段及 Builder 方法
- 删除 `SdkComponents.Builder.l2Cache()` 方法
- 更新 `AuthxClient.Builder.buildTransportStack()` 中 TieredCache 构建逻辑
- 更新文档（`docs/cache-consistency-guide.md`、`README.md`、`README_en.md`）

**验证标准**：代码中无 `SdkComponents.l2Cache` 引用；所有测试通过。

---

## 文件变更清单

| 文件 | 操作 |
|---|---|
| `build.gradle` | 添加 `compileOnly("io.lettuce:lettuce-core:6.3.2.RELEASE")` |
| `src/.../cache/TieredCache.java` | `implements Cache` → `implements IndexedCache`，添加 `invalidateByIndex` |
| `src/.../cache/RedisCacheAdapter.java` | **新建** — Lettuce-based Redis Hash 缓存 |
| `src/.../spi/SdkComponents.java` | 移除 `l2Cache` 字段 |
| `src/.../AuthxClient.java` (CacheConfig + buildTransportStack) | 添加 `redis()`/`redisTtl()`，重写 L2 构建逻辑 |
| `src/test/.../cache/RedisCacheAdapterTest.java` | **新建** — Mock Lettuce 测试 |
| `src/test/.../cache/TieredCacheIndexedTest.java` | **新建** — TieredCache IndexedCache 测试 |
| `docs/cache-consistency-guide.md` | 更新 L2 配置说明 |
