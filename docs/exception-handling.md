# 异常处理与优雅降级（强制遵守）

本文件中的所有规则为强制规范，任何涉及异常处理、gRPC 调用、降级逻辑的代码变更必须遵守。

---

## 异常体系

```
AuthCsesException (base, RuntimeException)
  ├─ AuthCsesConnectionException     ← UNAVAILABLE / CANCELLED / ABORTED (可重试)
  ├─ AuthCsesTimeoutException        ← DEADLINE_EXCEEDED (可重试)
  ├─ AuthCsesAuthException           ← UNAUTHENTICATED / PERMISSION_DENIED (不重试)
  ├─ AuthCsesResourceExhaustedException ← RESOURCE_EXHAUSTED (不重试)
  ├─ AuthCsesInvalidArgumentException   ← INVALID_ARGUMENT / NOT_FOUND / ALREADY_EXISTS / OUT_OF_RANGE (不重试)
  ├─ AuthCsesUnimplementedException     ← UNIMPLEMENTED (不重试)
  ├─ AuthCsesPreconditionException      ← FAILED_PRECONDITION (不重试)
  ├─ CircuitBreakerOpenException     ← 熔断打开 (不重试)
  ├─ InvalidResourceException        ← Schema 校验失败
  ├─ InvalidRelationException        ← Schema 校验失败
  └─ InvalidPermissionException      ← Schema 校验失败
```

## gRPC 状态码映射

所有 gRPC 调用（包括流式迭代器的遍历过程）统一经过 `GrpcTransport.mapGrpcException` 转换。

| gRPC 状态码 | SDK 异常 | 可重试 | 计入熔断 |
|---|---|:---:|:---:|
| DEADLINE_EXCEEDED | TimeoutException | ✓ | ✓ |
| UNAVAILABLE, CANCELLED, ABORTED | ConnectionException | ✓ | ✓ |
| UNAUTHENTICATED, PERMISSION_DENIED | AuthException | ✗ | ✗ |
| RESOURCE_EXHAUSTED | ResourceExhaustedException | ✗ | ✗ |
| INVALID_ARGUMENT, NOT_FOUND, ALREADY_EXISTS, OUT_OF_RANGE | InvalidArgumentException | ✗ | ✗ |
| UNIMPLEMENTED | UnimplementedException | ✗ | ✗ |
| FAILED_PRECONDITION | PreconditionException | ✗ | ✗ |
| 其他 | AuthCsesException | ✓ | ✓ |

### 规则

1. **不可重试异常**必须在 `RetryPolicy.defaults().nonRetryableExceptions` 中
2. **客户端错误**必须在 `CircuitBreakerConfig.ignoreExceptions` 中
3. **新增 gRPC 调用点**必须经过 `mapGrpcException` 或 `withErrorHandling`

## 后端能力检测

SDK 不知道 SpiceDB 后端用什么数据库，通过运行时探测处理：

| 功能 | 不支持时的表现 | SDK 行为 |
|---|---|---|
| Watch | UNIMPLEMENTED | 停止 Watch 线程，退化到 TTL 缓存过期 |
| Schema 校验 | UNIMPLEMENTED | 设标志位禁用校验，后续不再调用 |
| 认证 | UNAUTHENTICATED / PERMISSION_DENIED | 停止对应功能 |

### 降级原则

1. **永久错误只报一次** — volatile boolean 或 AtomicBoolean 防日志轰炸
2. **恢复后自动重置** — 如 TokenStore 恢复，distributedAvailable 重置为 true
3. **连续瞬态失败有上限** — Watch 连续 20 次失败后停止
4. **新增外部依赖必须有降级路径**

### 降级清单

| 组件 | 触发 | 降级行为 | 日志 |
|---|---|---|---|
| Watch | UNIMPLEMENTED | 退化到 TTL | WARNING 一次 |
| Watch | 认证失败 | 停止 | WARNING 一次 |
| Watch | 连续 20 次失败 | 停止 | ERROR 一次 |
| SchemaLoader | UNIMPLEMENTED | 禁用校验 | INFO 一次 |
| DistributedTokenStore | 任何异常 | 本地 ConcurrentHashMap | WARNING 一次 |
| TelemetrySink | 任何异常 | 丢弃事件 | WARNING 前 3 次 |
