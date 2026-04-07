# 代码美化 Phase 1：Model + Exception + Policy 层

**日期**：2026-04-08
**范围**：model/（~15 文件）、exception/（~12 文件）、policy/（~8 文件）、spi/ 包的类型注解
**原则**：功能不变，只改风格。所有现有测试必须通过。

---

## req-1: 引入 JSpecify @Nullable 注解

添加 `org.jspecify:jspecify:1.0.0` 依赖（`implementation`，传递给使用者）。

为以下包创建 `package-info.java`，标注 `@NullMarked`：
- `com.authx.sdk.model`
- `com.authx.sdk.model.enums`
- `com.authx.sdk.exception`
- `com.authx.sdk.policy`
- `com.authx.sdk.spi`
- `com.authx.sdk.cache`

在所有可空的字段、参数、返回值上标注 `@Nullable`。

**已知可空位置**（需标注）：
- `SubjectRef.relation` — 无 subject relation 时为 null
- `CheckResult.zedToken` — check 失败时可能为 null
- `CaveatRef.context` — 无上下文时为 null
- `Tuple.subjectRelation` — 无 subject relation 时为 null
- `ResourcePolicy` 的所有字段（cache、retry、circuitBreaker、timeout）— 未设置时为 null
- `SdkComponents.tokenStore` — 未配置时为 null
- `DistributedTokenStore` 的 `get()` 返回值 — miss 或错误时返回 null

**验证标准**：`@NullMarked` 包内所有非 `@Nullable` 的参数/返回值默认视为非空。编译通过，无新 warning。

## req-2: 命名统一 — getter 属性风格

policy 包的所有 `getX()` 方法改为 `x()` 属性风格，与 model 包的 record 风格一致。

| 类 | 当前 | 改为 |
|---|---|---|
| ResourcePolicy | `getCache()` | `cache()` |
| ResourcePolicy | `getReadConsistency()` | `readConsistency()` |
| ResourcePolicy | `getRetry()` | `retry()` |
| ResourcePolicy | `getCircuitBreaker()` | `circuitBreaker()` |
| ResourcePolicy | `getTimeout()` | `timeout()` |
| CachePolicy | `getTtl()` | `ttl()` |
| CachePolicy | `getMaxIdleTime()` | `maxIdleTime()` |
| CachePolicy | `isEnabled()` | `enabled()`（改为属性风格）|
| RetryPolicy | `getMaxAttempts()` | `maxAttempts()` |
| RetryPolicy | `getBaseDelay()` | `baseDelay()` |
| RetryPolicy | `getMaxDelay()` | `maxDelay()` |
| RetryPolicy | `getMultiplier()` | `multiplier()` |
| RetryPolicy | `getJitterFactor()` | `jitterFactor()` |
| CircuitBreakerPolicy | `getFailureRateThreshold()` | `failureRateThreshold()` |
| CircuitBreakerPolicy | `getSlowCallRateThreshold()` | `slowCallRateThreshold()` |
| CircuitBreakerPolicy | `getSlowCallDuration()` | `slowCallDuration()` |
| CircuitBreakerPolicy | `getSlidingWindowType()` | `slidingWindowType()` |
| CircuitBreakerPolicy | `getSlidingWindowSize()` | `slidingWindowSize()` |
| CircuitBreakerPolicy | `getMinimumNumberOfCalls()` | `minimumNumberOfCalls()` |
| CircuitBreakerPolicy | `getWaitInOpenState()` | `waitInOpenState()` |
| CircuitBreakerPolicy | `getPermittedCallsInHalfOpen()` | `permittedCallsInHalfOpen()` |
| CircuitBreakerPolicy | `getFailOpenPermissions()` | `failOpenPermissions()` |
| CircuitBreakerPolicy | `isEnabled()` | `enabled()` |

所有调用方（transport 层、builtin 层）同步更新。

**验证标准**：代码中无 `getX()` 风格的 getter（`getIfPresent` 除外——这是 Cache 接口的语义方法，不是 getter）。编译通过。

## req-3: 命名统一 — factory 方法

| 当前 | 改为 | 文件 |
|---|---|---|
| `CheckRequest.from(resource, permission, subjectRef)` | `CheckRequest.of(resource, permission, subjectRef)` | CheckRequest.java |
| `CachePolicy.ofTtl(Duration)` | `CachePolicy.of(Duration)` | CachePolicy.java |

保留 `of()` 作为唯一的 factory 方法命名。`defaults()` 和 `disabled()` 语义不同，保持不变。

**验证标准**：model 和 policy 包中无 `from()` 或 `ofXxx()` 形式的 factory 方法（`of()` 除外）。

## req-4: import 整理

禁止通配符 import（`import xxx.*`）。所有 import 改为显式。

排序规则：
1. `com.authx.sdk.*`
2. `com.authzed.*`
3. `io.*`（grpc、resilience4j、lettuce）
4. `java.*` / `javax.*`
5. `static` imports

**范围**：model/、exception/、policy/、spi/ 包内所有文件。其他包在后续 Phase 处理。

**验证标准**：scope 内文件无 `import xxx.*`。

## req-5: model record 一致性模式

所有 record 统一遵循以下模式：

```java
/**
 * 一句话描述。
 *
 * @param field1 描述
 * @param field2 描述
 */
public record Xxx(Type field1, @Nullable Type field2) {

    /** Validate non-null fields. */
    public Xxx {
        Objects.requireNonNull(field1, "field1");
    }

    /** Creates a new Xxx. */
    public static Xxx of(Type field1, @Nullable Type field2) {
        return new Xxx(field1, field2);
    }
}
```

需要补齐的：
- Javadoc：所有 record 类和 `@param` 描述
- compact constructor：确保非空字段有 `Objects.requireNonNull`
- factory `of()`：确保每个 record 有 `of()` 静态工厂

**涉及文件**：
- CheckKey.java
- CheckRequest.java
- CheckResult.java
- ResourceRef.java
- SubjectRef.java
- CaveatRef.java
- Permission.java
- Relation.java
- Tuple.java
- GrantResult.java
- RevokeResult.java
- BatchResult.java
- BulkCheckResult.java（非 record，但需补 Javadoc）
- PermissionSet.java（非 record，但需补 Javadoc）
- PermissionMatrix.java（非 record，但需补 Javadoc）
- ExpandTree.java
- LookupResourcesRequest.java
- LookupSubjectsRequest.java
- RelationshipChange.java
- Consistency.java（sealed interface）
- Permissionship.java（enum）
- SdkAction.java（enum）
- OperationResult.java（enum）
- WriteRequest.java

**验证标准**：每个 public record/class 有 Javadoc；每个 public method 有 Javadoc 或 `@Override`。

## req-6: exception 层 Javadoc

每个异常类添加一行 Javadoc 说明触发场景和是否可重试：

```java
/** Thrown when SpiceDB returns DEADLINE_EXCEEDED. {@link #isRetryable()} returns {@code true}. */
public class AuthxTimeoutException extends AuthxException {
```

**涉及文件**（12 个）：
AuthxException、AuthxTimeoutException、AuthxConnectionException、AuthxAuthException、AuthxResourceExhaustedException、AuthxInvalidArgumentException、AuthxUnimplementedException、AuthxPreconditionException、CircuitBreakerOpenException、InvalidResourceException、InvalidRelationException、InvalidPermissionException

**验证标准**：每个异常类有类级 Javadoc。

## req-7: policy 层 Javadoc + 一致性

所有 policy 类补齐 Javadoc：
- PolicyRegistry：类、`resolve()`、`resolveCacheTtl()`、`resolveReadConsistency()`
- ResourcePolicy：类、所有属性方法、`mergeWith()`、`defaults()`
- CachePolicy：类、`resolveTtl()`、`of()`、`disabled()`
- RetryPolicy：类、`shouldRetry()`、`defaults()`、`disabled()`
- CircuitBreakerPolicy：类、`defaults()`
- ReadConsistency：类（已有 Javadoc，确认完整性）

**验证标准**：policy 包 Javadoc 覆盖率 > 90%。

## req-8: spi 包 @Nullable 标注

SdkComponents record 的可空字段标 `@Nullable`：
- `tokenStore` — 可空

SdkInterceptor.OperationContext 的 post-execution 字段标 `@Nullable`：
- `result` — 执行前为 null
- `error` — 无错误时为 null

DistributedTokenStore.get() 返回值标 `@Nullable`。
TelemetrySink：确认无可空字段。
AttributeKey.defaultValue() 返回值标 `@Nullable`。

**验证标准**：spi 包有 `package-info.java` 且 `@NullMarked`；所有可空位置有 `@Nullable`。

---

## 不改的范围

- AuthxClient.java — Phase 2（Builder 重构 + 文件拆分）
- ResourceHandle.java — Phase 2（内部类提取）
- transport/ — Phase 3
- 功能逻辑 — 不变
- 测试文件 — 仅因主代码改名而同步更新调用方，不做风格重构
