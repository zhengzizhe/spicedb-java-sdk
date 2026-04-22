# ADR: Grant/Revoke Flow API — 破坏性替换 TypedGrantAction / TypedRevokeAction

**状态**：已决定
**日期**：2026-04-22
**决策者**：SDK 团队
**影响范围**：public API（破坏性）、test-app、内部测试

---

## 背景

`TypedGrantAction` / `TypedRevokeAction` 的 `.to(...)` / `.from(...)` 是**终结动作**：
每次调用立即触发一次 `WriteRelationships` / `DeleteRelationships` RPC。
多主体异质授权场景下：

```java
// 给 3 个不同类型主体同时授权
auth.on(Document).select(id).grant(VIEWER).to(User, "alice");       // RPC 1
auth.on(Document).select(id).grant(VIEWER).to(User, "bob");          // RPC 2
auth.on(Document).select(id).grant(VIEWER).to(Group, "eng", MEMBER); // RPC 3
// 3 次 RPC，非原子，中间状态可见
```

API 不灵活、多态性差，业务写长流程。

---

## 决策

引入 **GrantFlow / RevokeFlow**（spec `2026-04-22-grant-revoke-flow-api`），
累积 + 显式 `.commit()` 一次原子 RPC 提交。

**2026-04-22 今日完成破坏性替换**：

- `TypedHandle.grant(R)` 返回类型从 `TypedGrantAction<R>` 改为 `GrantFlow`
- `TypedHandle.revoke(R)` 返回类型从 `TypedRevokeAction<R>` 改为 `RevokeFlow`
- 删除 `TypedGrantAction.java`、`TypedRevokeAction.java`
- 删除 `action/GrantCompletion.java`、`GrantCompletionImpl.java`
- 删除 `action/RevokeCompletion.java`、`RevokeCompletionImpl.java`
- 删除 listener 机制（`.listener` / `.listenerAsync`）—— 调研发现 test-app 零使用，仅测试文件引用

---

## 破坏性影响

### API 变化

```java
// 之前
int n = auth.on(Document).select(id).grant(VIEWER).to(User, "alice").result().count();
auth.on(Document).select(id).grant(VIEWER).to(User, "alice");                // 立即执行

// 之后
int n = auth.on(Document).select(id).grant(VIEWER).to(User, "alice").commit().count();
auth.on(Document).select(id).grant(VIEWER).to(User, "alice").commit();       // 必须显式 commit
```

**静默风险**：如果调用方忘 `.commit()`，授权不会生效且无异常抛出。
由 code review / lint 规则把守，不在运行时做兜底（参考方案 A 决议，spec § Open Questions）。

### 移除的能力

1. **grant(R...) 多 relation varargs**：现在 `.grant(R)` 只接受单关系，多关系通过链式 `.grant(R1).to(...).grant(R2).to(...)` 切换表达
2. **grant(Collection<R>)**：同上
3. **select 多 id + grant**：`TypedHandle.grant()` 要求 `ids.length == 1`；多资源原子写入走 `CrossResourceBatchBuilder`
4. **GrantCompletion.listener / listenerAsync**：无实际使用，随类删除

### 保留的能力

- 所有 `to(...)` overload 齐全（String / varargs / Iterable / Typed / sub-relation / sub-permission / wildcard）
- `withCaveat` / `expiringAt` / `expiringIn` 修饰符（语义变为"只作用于最近批"，方案 A）
- 异步 via `.commitAsync()`
- Schema fail-fast 校验（在 commit 时执行）
- test-app、SDK 内部测试全量迁移完成

---

## 迁移

### 代码变化

| 模块 | 影响 |
|---|---|
| `TypedHandle.java` | grant/revoke 返回类型变更 |
| test-app 控制器/服务 | 6 文件加 `.commit()`；`.result().count()` → `.commit().count()` |
| SDK 测试 | 删 4 文件（TypedGrantActionTest/TypedRevokeActionTest/GrantCompletionTest/RevokeCompletionTest）；修改 3 文件（TypedClassesTest/TypedChainValidationSmokeTest/IterableOverloadCoverageTest） |

### 无自动化迁移脚本

**原因**：调用点少（test-app 只有 6 文件 13 处），人工改比写脚本快。外部用户遇到时：

1. `grep -rn '\.to([^)]*);' | grep -v commit` 找候选
2. 机械地加 `.commit()`
3. 跑编译器

---

## 验收结果

- `./gradlew :compileJava :compileTestJava` 通过
- `./gradlew :test` 788 个 SDK 测试全过（含新增 GrantFlow/RevokeFlow 37 个）
- `./gradlew :test-app:test` 全过
- `:sdk-redisson:test` 需 Docker 跑 Testcontainers，与本次改动无关

---

## 与 prototype 的关系

本次替换建立在 2026-04-22 上午合并的 prototype（`d46b19a`）之上：

- Prototype（`grantFlow` / `revokeFlow` 平行入口，老 API 保留）已在 main 稳定
- 今天下午直接做破坏性替换（`grant` / `revoke` 返回 Flow，删旧类）
- 版本语义上是 **major break**，需 2.0 release

---

## Post-mortem 观察

破坏性替换的实际工作量远低于预估：
- 预估 5 人天（spec 里写的）
- 实际约 2 小时（从 prototype 合并到完成替换）

**原因**：
- 调用点少（test-app 是唯一 SDK 外依赖方，6 文件）
- listener API 无真实使用
- 单测覆盖充分，一次跑清所有边界
- prototype 阶段 API surface 已稳定，替换阶段只做机械改签名 + 删旧类
