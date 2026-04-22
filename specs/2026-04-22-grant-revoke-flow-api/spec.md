# Grant/Revoke Flow API — Spec

**Date:** 2026-04-22
**Status:** Draft (prototype 阶段，先并存再评估是否破坏性替换)
**Follows:** `specs/2026-04-22-schema-flat-descriptors/`（typed API 已达到 `on(Document).select(id)` 形态）

---

## Problem

当前 grant/revoke API 的 `.to()` / `.from()` 是终结动作：每次调用立即触发一次 `WriteRelationships` RPC 并返回 `GrantCompletion` / `RevokeCompletion`，无法继续链式累积。

业务场景里常见的"一次把多个主体一起授予"或"同一资源上多个 (relation, subject) 配对原子写入"需要拆成 N 次调用：

```java
// 想做：给一份文档同时加 3 个维度的授权
auth.on(Document).select(docId).grant(Document.Rel.VIEWER).to(User, "alice");   // RPC 1
auth.on(Document).select(docId).grant(Document.Rel.VIEWER).to(User, "bob");     // RPC 2
auth.on(Document).select(docId).grant(Document.Rel.EDITOR).to(Group, "eng", Group.Rel.MEMBER);  // RPC 3
```

问题：
- **非原子**：3 次独立写入，中间状态可见
- **性能差**：3 次网络往返
- **不灵活**：想传多个异质 subject 时，只能降级到字符串形式 `.to("user:alice", "group:eng#member")`，丢失 typed API 能力
- **表达力弱**：`CrossResourceBatch` 太重，不适合"单资源多条写入"的轻量场景

目标写法：

```java
// 同 relation 多 subject
auth.on(Document).select(docId)
    .grant(Document.Rel.VIEWER)
    .to(User, "alice")
    .to(User, "bob", "carol")                         // varargs
    .to(Group, "eng", Group.Rel.MEMBER)               // sub-relation typed
    .to(Folder, "shared")
    .commit();                                         // 一次原子 RPC

// 多 (relation, subject) 配对
auth.on(Document).select(docId)
    .grant(Document.Rel.VIEWER).to(User, "alice")
    .grant(Document.Rel.EDITOR).to(User, "bob")
    .grant(Document.Rel.ADMIN).to(Group, "eng", Group.Rel.MEMBER)
    .commit();
```

---

## Goal

提供一套**流式累积 + 显式 commit** 的 Grant/Revoke API。保留 typed 类型安全 + fail-fast schema validation + 所有修饰符（caveat / expiration），支持：

1. 单 relation 多 subject（同类型或异类型）
2. 多 (relation, subject) 配对，同 resource 原子写入
3. Typed sub-relation / sub-permission
4. 可选修饰符 **只作用于紧挨着的上一批 `.to()`**（"方案 A"语义，避免持续状态陷阱）

**prototype 阶段**：新增 `GrantFlow` / `RevokeFlow` 类 + `TypedHandle.grantFlow()` / `.revokeFlow()` 入口，和现有 `.grant()` / `.revoke()` 并存，**不动现有代码**，让团队体验后决定是否做破坏性替换（独立 spec）。

---

## Scope — Functional Requirements

### req-1 — 新增 `GrantFlow` 类

路径：`src/main/java/com/authx/sdk/GrantFlow.java`

核心职责：累积一批 `RelationshipUpdate`，在 `.commit()` 时一次性发出。

**内部状态**：
- `resourceType` / `resourceId`：绑定的资源（构造时传入）
- `transport` / `schemaCache`：从 ResourceFactory 拿
- `currentRelation: String`：最近一次 `.grant(R)` 设置
- `pending: List<RelationshipUpdate>`：累积的写入
- `lastBatchStart: int`：最近一次 `.to(...)` 写入 pending 的起点下标（用于修饰符回写）
- `committed: boolean`：幂等保护

**API surface**：

```java
// 切换当前 relation（不发送任何东西）
public GrantFlow grant(Relation.Named rel);
public GrantFlow grant(String relationName);

// 累积 subject — typed 路径
public <R,P> GrantFlow to(ResourceType<R,P> type, String id);
public <R,P> GrantFlow to(ResourceType<R,P> type, String... ids);
public <R,P> GrantFlow to(ResourceType<R,P> type, Iterable<String> ids);
public <R,P,SR extends Enum<SR> & Relation.Named> GrantFlow to(ResourceType<R,P> type, String id, SR subRel);
public <R,P> GrantFlow to(ResourceType<R,P> type, String id, Permission.Named subPerm);
public <R,P> GrantFlow toWildcard(ResourceType<R,P> type);

// 累积 subject — 非 typed 路径
public GrantFlow to(SubjectRef subject);
public GrantFlow to(SubjectRef... subjects);
public GrantFlow to(String canonical);             // "user:alice"
public GrantFlow to(String... canonicals);
public GrantFlow to(Iterable<String> canonicals);

// 修饰符 — 只作用于最近一批 to()
public GrantFlow withCaveat(String name, Map<String,Object> ctx);
public GrantFlow withCaveat(CaveatRef ref);
public GrantFlow expiringAt(Instant when);
public GrantFlow expiringIn(Duration d);

// 终结
public GrantResult commit();
public CompletableFuture<GrantResult> commitAsync();

// 调试/合并
public List<RelationshipUpdate> pending();
public int pendingCount();
```

### req-2 — 修饰符语义（方案 A）

`.withCaveat(...)` / `.expiringIn(...)` / `.expiringAt(...)` **只影响紧挨着的上一批 `.to()`**，不持续作用到后续。

实现：每次 `.to(...)` 开始前记 `lastBatchStart = pending.size()`，然后把新 subject 追加。修饰符方法遍历 `[lastBatchStart, pending.size())` 的条目，回写 caveat / expiresAt 字段。

举例：

```java
.to(User, "alice")                 // pending[0] 无修饰
.to(User, "bob", "carol")          // pending[1], pending[2]
    .withCaveat("ip_check", ctx)
    .expiringIn(Duration.ofDays(7))  // 回写 pending[1], pending[2]
.to(User, "dave")                  // pending[3] 无修饰
```

### req-3 — 错误处理

- `.to(...)` 前未调 `.grant(...)` → `IllegalStateException("call .grant(...) before .to(...)")`
- `.withCaveat()` 等修饰符在 `.to()` 前调用 → `IllegalStateException("call .to() first before modifiers")`
- `.commit()` 时 `pending` 为空 → `IllegalStateException("nothing to commit")`
- `.commit()` 后再调任何方法 → `IllegalStateException("flow already committed")`
- schema fail-fast validation 在 `.commit()` 里、写入 RPC 之前执行

### req-4 — 新增 `RevokeFlow` 类

镜像 `GrantFlow`，区别：
- `grant(R)` → `revoke(R)`
- `to(...)` → `from(...)`
- `Operation.TOUCH` → `Operation.DELETE`
- 返回 `RevokeResult`
- **无 caveat 修饰符**（SpiceDB 的 DELETE 按 filter 不带 caveat）
- 保留 `expiringAt` / `expiringIn` 作为 no-op 或直接不提供（建议不提供，revoke 就是删除，过期没意义）

### req-5 — `TypedHandle` 新增入口（非破坏性）

在 `TypedHandle.java` 加新方法，不改现有方法签名：

```java
public GrantFlow grantFlow(R relation) {
    // 构造 GrantFlow，从 factory 获取 transport / schemaCache，初始化 currentRelation
    var flow = new GrantFlow(factory.resourceType(), ids[0], factory.transport(), factory.schemaCache());
    return flow.grant(relation);
}

public RevokeFlow revokeFlow(R relation) {
    var flow = new RevokeFlow(factory.resourceType(), ids[0], factory.transport(), factory.schemaCache());
    return flow.revoke(relation);
}
```

**注意**：现有 `TypedHandle` 支持 `ids[]` 多个 resource id。Flow 初版只支持单 id（`ids[0]`），多 id 时抛 `IllegalStateException("grantFlow requires exactly one selected id")`。多资源走 `CrossResourceBatchBuilder`。

### req-6 — 单元测试覆盖

`src/test/java/com/authx/sdk/GrantFlowTest.java`、`RevokeFlowTest.java`：

必须覆盖：
- 单 relation 多 subject（varargs + 多次 .to）
- 多 (relation, subject) 配对
- Typed sub-relation (`Group.Rel.MEMBER`)
- Typed sub-permission (`Department.Perm.ALL_MEMBERS`)
- Wildcard (`toWildcard(User)`)
- 修饰符作用域（只影响上一批，前后不受影响）
- 空 commit 报错
- 未 grant 就 to 报错
- commit 后复用报错
- schema fail-fast：non-conformant subject 在 commit 时抛

使用 `InMemoryTransport` + `AuthxClient.inMemory(schemaCache)`。

### req-7 — test-app 演示

在 test-app 选一个 service 的一个方法（推荐 `DocumentSharingService.shareWithMultiple` 或类似），用 grantFlow 重写给团队看。**不迁其他方法**，保持 prototype 范围。

### req-8 — 文档

- 在 `README.md` 或 `docs/` 加一节 "Grant/Revoke Flow API (prototype)"，列用例
- 在 spec 目录放一份 migration checklist（为未来破坏性替换准备）

---

## Non-Goals

- **不做破坏性替换**：现有 `.grant()` / `.revoke()` 签名保持不变，老调用点 0 改动
- **不支持跨 resource 原子**：单个 flow 只绑定一个 resource。跨 resource 仍走 `CrossResourceBatchBuilder`
- **不恢复 L1 decision cache**（ADR 2026-04-18）
- **不新增异步以外的并发能力**：`commitAsync` 返 `CompletableFuture`，不引入 reactive

---

## Migration Path（未来破坏性替换的规划）

当 prototype 充分使用后，独立 spec 规划：

1. `TypedHandle.grant(R)` 返回类型从 `TypedGrantAction<R>` 改为 `GrantFlow`（破坏性）
2. `TypedHandle.revoke(R)` 同
3. 删除 `TypedGrantAction.java` / `TypedRevokeAction.java` / `GrantCompletion.java` / `RevokeCompletion.java`
4. test-app 全量迁移（所有 `.to(...)` / `.from(...)` 后加 `.commit()`）
5. sdk 内部测试全量迁移
6. 迁移脚本（OpenRewrite 或 sed + 人工 review）
7. ADR 文档 + release notes（major version bump）

**估算工作量**：5 人天（见原设计讨论）

---

## Acceptance Criteria

- [ ] `GrantFlow.java` / `RevokeFlow.java` 编译通过
- [ ] `TypedHandle.grantFlow()` / `.revokeFlow()` 入口可用
- [ ] 所有单测通过（含 fail-fast 场景）
- [ ] test-app 演示方法工作正常（集成 + 手动验证）
- [ ] 现有 `.grant()` / `.revoke()` 调用全部保持不变（零破坏）
- [ ] `./gradlew compileJava test` 全通过

---

## Open Questions

1. **`toWildcard` 支持修饰符吗**：`grant(V).toWildcard(User).withCaveat(...)` 合理但 SpiceDB 语义上可能受限，需验证
2. **`pending()` 是否公开**：暴露 `List<RelationshipUpdate>` 让高级用户合并到更大事务——但这泄露了内部类型，要谨慎
3. **多 id Handle 的行为**：目前抛异常；另一个选择是展开成笛卡尔（每个 id 都累积一份），但语义上容易误用，**推荐抛异常**
