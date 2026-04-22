# Schema Flat Descriptors — Spec

**Date:** 2026-04-22
**Status:** Draft (awaiting user review)
**Follows:** `specs/2026-04-21-schema-aware-codegen/` (已完成，建立 schema-aware codegen + 运行时 subject validation 基础)

---

## Problem

当前业务代码写 grant/check 的主路径长这样：

```java
client.on(Organization.TYPE)
      .select("acme")
      .grant(Organization.Rel.ADMIN)
      .to(User.TYPE, "u-ceo");
```

每次链式调用都要写 `.TYPE`、重复泛型 token、重复类型名，噪音占字符数 40%+。试过保留 `.TYPE` 但缩写（`.T` / `.$`）都解决不了根本问题：语法级别上"类名不能作值"是 Java 的硬限制。

业务团队的目标写法是：

```java
client.on(Organization)
      .select("acme")
      .grant(Organization.Rel.ADMIN)
      .to(User, "u-ceo");
```

POC 已经实验验证 Java 21 语法允许这种形式 —— 通过 **Descriptor + EnumProxy** 模式 + `import static Schema.*`，同名 `Organization` 作为 Descriptor 值字段存在，`Organization.Rel.ADMIN` 走"字段 → 嵌套类型 → enum 常量"解析链，编译运行都通过。

---

## Goal

重构 codegen 和 SDK API，让业务代码在保留编译期类型检查 + fail-fast subject validation + 所有现有 SDK 能力的前提下，达到上面目标写法。**不引入运行时开销，不恢复 decision cache（ADR 2026-04-18），不破坏 model 层 API**。

---

## Scope — Functional Requirements

### req-1 — `Schema.java` 聚合文件 codegen

`AuthxCodegen` 新增生成一个 `Schema.java` 文件到 `com.authx.testapp.schema` 包（路径：`test-app/src/main/java/com/authx/testapp/schema/Schema.java`）。文件内容必须满足：

1. `final class Schema`，私有构造器（禁止实例化）
2. 每个资源类型生成一个 **static nested Descriptor 类**：`XxxDescriptor extends ResourceType<Xxx.Rel, Xxx.Perm>`
3. 每个资源类型生成两个 **static nested Proxy 类**：`XxxRelProxy`（字段暴露 `Rel` enum 常量）、`XxxPermProxy`（字段暴露 `Perm` enum 常量）
4. Descriptor 实例作为 `public static final` 字段暴露，字段名**等于资源类型的 CamelCase 名**（`Organization`、`User`、`Department`、`Group`、`Space`、`Folder`、`Document`）
5. Descriptor 内部的 `Rel` / `Perm` 字段分别是 `XxxRelProxy` / `XxxPermProxy` 实例
6. **Proxy 内部的 enum 常量引用必须使用完全限定名**（`com.authx.testapp.schema.Organization.Rel.ADMIN`），防止 Schema 类初始化期字段遮蔽类型导致 NPE

**验收标准**：
- 运行 `AuthxCodegen` 后生成的 `Schema.java` 能直接编译
- 反射断言 `Schema.Organization instanceof ResourceType` 为真
- 反射断言 `Schema.Organization.name().equals("organization")` 为真
- `new SchemaInitOrderTest` 验证 `Schema.Organization.Rel.ADMIN == pkg.Organization.Rel.ADMIN`（同一 enum 常量，没有初始化 NPE）

### req-2 — `Xxx.java` 精简

codegen 重新生成 `Organization.java` / `Department.java` / `Group.java` / `Space.java` / `Folder.java` / `Document.java` / `User.java`，仅包含：

1. `public enum Rel implements Relation.Named { ... }` 及 `permissionName()` / `subjectTypes()` 等已有方法
2. `public enum Perm implements Permission.Named { ... }`

**不再生成**：
- `public static final ResourceType<Rel, Perm> TYPE = ...` 字段（直接删除，无 `@Deprecated` 过渡）
- `select(AuthxClient, String)` 静态助手方法（旧用法的遗留入口）

**特殊情形**：
- 若某资源类型在 schema 中无 permission 声明（如 `user`），生成 `public enum Perm {}` 空 enum 是合法的；但 `Schema.XxxPermProxy` 在此情况下生成为空 class（无字段），`Descriptor.Perm` 字段仍保留（指向空 Proxy）。

### req-3 — `ResourceTypes.java` 删除

旧的 `ResourceTypes.java`（集中注册表文件）功能被 `Schema.java` 完全覆盖，codegen 不再生成，现存文件在 PR 中删除。

### req-4 — `ResourceType` 允许子类化

`src/main/java/com/authx/sdk/ResourceType.java` 改动：

1. 去掉 `final` 修饰符
2. 主构造器改为 `protected`（原 `public` 静态工厂 `of(...)` 保留，内部调用构造器）
3. 所有现有字段（`name` / `relClass` / `permClass` / `permissionByName`）保持 `private final`，通过现有 `name()` / `relClass()` / `permClass()` 等 getter 暴露
4. Javadoc 补充说明："This class is extended by generated Descriptor types. End users should not subclass directly — use `AuthxCodegen` instead."

### req-5 — SDK 核心入口支持 `on(ResourceType)`

`src/main/java/com/authx/sdk/AuthxClient.java` 新增：

```java
public <R extends Enum<R> & Relation.Named,
        P extends Enum<P> & Permission.Named>
TypedHandle<R, P> on(ResourceType<R, P> type) { ... }
```

行为等价于现有 `on(String typeName)` 路径，但 `TypedHandle` 绑定泛型 `<R, P>`，后续链式方法获得类型约束。

同一份新增要加到：
- `CrossResourceBatchBuilder.java` 的 `.on(ResourceType, id)` / `.onAll(ResourceType, Iterable<String>)` 入口
- `batchCheck().add(ResourceType, id, Perm, SubjectRef)` 重载

### req-6 — typed subject 重载（grant / revoke 侧）

以下方法在 `TypedGrantAction` / `TypedRevokeAction` 上新增（同名 `from*` 对称）：

| Method | Purpose |
|---|---|
| `to(ResourceType<R2,P2> type, String id)` | 字面主体，单 id |
| `to(ResourceType<R2,P2> type, Iterable<String> ids)` | 字面主体，多 id（fan-out） |
| `to(ResourceType<R2,P2> type, String id, R2 subRelation)` | sub-relation 主体，typed enum |
| `to(ResourceType<R2,P2> type, String id, P2 subPermission)` | sub-permission 主体（如 `department#all_members`） |
| `to(ResourceType<R2,P2> type, String id, String subRelationName)` | sub-relation 字符串 fallback（动态场景） |
| `toWildcard(ResourceType<R2,P2> type)` | `user:*` 通配符主体 |

同样的扩展加到 `CrossResourceBatchBuilder` 的 `GrantScope` / `RevokeScope` / `MultiGrantScope` / `MultiRevokeScope` 内类上。

**约束**：
- typed 和字符串 sub-relation 路径**必须生成完全相同**的 `SubjectReference`（`group:eng#member` 格式），不引入行为差异
- 所有新增 public 方法都有 javadoc 示例

### req-7 — typed subject 重载（check / lookup 侧）

- `CheckAction` / `TypedCheckAction` 新增 `by(ResourceType<R2,P2> type, String id)`
- `TypedCheckAllAction` 新增 `by(ResourceType<R2,P2>, String)` / `byAll(ResourceType<R2,P2>, String)` 并额外接受 `PermProxy` 的 `checkAll(PermProxy)` 重载（替代 `checkAll(Class<Perm>)` 的主路径，原 `Class` 版本保留但不作推荐）
- `TypedLookupAction` 新增 `findBy(ResourceType<R2,P2>, String)`（lookup resources）和 `who(ResourceType<R2,P2>, P)` 接受 Perm 参数的入口（lookup subjects）

### req-8 — `checkAll(PermProxy)` 入口

新增 SDK 侧公共接口（`src/main/java/com/authx/sdk/PermissionProxy.java`）：

```java
public interface PermissionProxy<P extends Enum<P> & Permission.Named> {
    Class<P> enumClass();
}
```

codegen 生成的每个 `XxxPermProxy` 实现 `PermissionProxy<Xxx.Perm>`：

```java
public static final class DocumentPermProxy implements PermissionProxy<Document.Perm> {
    public final com.authx.testapp.schema.Document.Perm VIEW = ...;
    // ...
    @Override public Class<Document.Perm> enumClass() { return com.authx.testapp.schema.Document.Perm.class; }
}
```

`TypedCheckAllAction` 保留现有 `checkAll(Class<Perm>)` 重载（不 breaking），新增：

```java
public <P2 extends Enum<P2> & Permission.Named>
TypedCheckAllAction<P2> checkAll(PermissionProxy<P2> proxy) {
    return checkAll(proxy.enumClass());  // 内部 delegate 到 Class 版本
}
```

**业务代码形态**：
```java
client.on(Document).select(docId).checkAll(Document.Perm).by(User, userId);
//                                          ^^^^^^^^^^^^^
//                                          Document.Perm 是 Schema.DocumentDescriptor.Perm 字段
//                                          = DocumentPermProxy 实例 = PermissionProxy<Document.Perm>
```

`enumClass()` 方法是 public（实现接口要求），但 javadoc 标 `@apiNote` 说明"this is intended for SDK internal use; business code should pass the proxy directly to `checkAll`"。不强制禁止调用（YAGNI —— 真有人想拿 Class 也不拦着），但文档明确推荐姿势。

**Rel 侧不做对称接口**：当前 SDK 没有任何 `Class<Rel>` 的消费点（grant/revoke 只吃 enum 实例），遵循 YAGNI 不预留 `RelationProxy`。若未来 schema introspection API 需要，再加。

### req-9 — test-app 四个 Service 迁移

将以下文件的所有 `client.on(Xxx.TYPE)` / `.to(User.TYPE, id)` / `.grant(Xxx.Rel.XXX)` 等调用迁移到新 API：

- `test-app/src/main/java/com/authx/testapp/service/CompanyWorkspaceService.java`
- `test-app/src/main/java/com/authx/testapp/service/DocumentSharingService.java`
- `test-app/src/main/java/com/authx/testapp/service/WorkspaceAccessService.java`
- `test-app/src/main/java/com/authx/testapp/service/ConditionalShareService.java`

迁移后：
- 每个 service 文件顶部 `import static com.authx.testapp.schema.Schema.*;`
- 所有 `Xxx.TYPE` 字符串常量消失
- 所有 `.to(User.TYPE, id)` 变为 `.to(User, id)`
- sub-relation 场景优先用 typed enum 版本（`.to(Group, id, Group.Rel.MEMBER)`），保留 1-2 处字符串版本作为多态测试覆盖

对应测试文件（`*ServiceTest.java`）同步迁移，行为断言不变。

### req-10 — 文档更新

- `README.md`（如涉及 API 示例）更新到新写法
- `docs/` 下任何 howto 示例同步更新
- 新增 `docs/migration-schema-flat-descriptors.md`，给外部用户一份 before/after 对照表

---

## Non-Goals

1. **不恢复 client-side decision cache**（ADR 2026-04-18 红线）
2. **不破坏 model 层 API**：`SubjectRef.of(type, id)` / `SubjectRef.of(type, id, subRelation)` / `SubjectRef.parse(str)` / `ResourceRef.of(...)` 全部保留
3. **不做 `@Deprecated` 过渡期**：`Xxx.TYPE` 直接删除，和业务代码迁移同 commit 生效（用户已确认外部用户可控）
4. **不改动 schema-aware fail-fast validation 逻辑**：上一期 spec（2026-04-21）已就位的 `validateSubject` 保持原样
5. **不引入 Kotlin / Scala `object` 等价物**：保持纯 Java 21
6. **不给 `Schema.XxxPermProxy` 暴露 public `enumClass()` 方法**：反射需求走 `Document.Perm.class`（enum 类本身仍存在）
7. **不生成 per-relation 语义糖**（如 `.toViewer(User, id)`）：保持泛型入口
8. **不做编译期 multi-type relation 约束**：运行时 validateSubject 继续承担

---

## Architecture

### 核心模式：Descriptor + EnumProxy

```
Schema.java (codegen 生成，~500 行，一个文件)
├── OrganizationDescriptor extends ResourceType<Organization.Rel, Organization.Perm>
│   ├── field `Rel`: OrganizationRelProxy  (嵌套类，字段=enum 常量)
│   └── field `Perm`: OrganizationPermProxy (嵌套类，字段=enum 常量)
├── ... (每个资源类型一组)
└── public static final OrganizationDescriptor Organization = new OrganizationDescriptor();
    public static final UserDescriptor User = ...;
    ... 其余类型
```

业务代码 `import static com.authx.testapp.schema.Schema.*` 后，`Organization` 是**字段引用**（Descriptor 实例），`Organization.Rel.ADMIN` 按 JLS §15.11 解析为"字段 → 嵌套类型访问 → enum 常量"，最终拿到的是 `com.authx.testapp.schema.Organization.Rel.ADMIN` 同一个 enum 值。

### 同名消歧

- `com.authx.testapp.schema.Organization` = enum 容器类（只有 Rel/Perm enum）
- `Schema.Organization`（经 static import 后裸 `Organization`）= Descriptor 实例字段

由于 JLS §6.4.2 "变量遮蔽类型"规则，在 `import static Schema.*` 作用域内，裸 `Organization` 优先解析为字段。要访问 enum 类本身（`Organization.Rel.class`），必须走 FQN 或不用 static import。这是硬规则，文档里说明。

### SDK API 表面（完整）

| 场景 | 新 API | 字符串/低层 fallback |
|---|---|---|
| 入口 | `client.on(Organization)` | `client.on("organization")` (保留) |
| select 单 id | `.select("o-acme")` | 不变 |
| grant 字面 user | `.to(User, "u-ceo")` | — |
| grant 多 user | `.to(User, List.of(...))` | — |
| grant wildcard | `.toWildcard(User)` | — |
| grant group member | `.to(Group, "eng", Group.Rel.MEMBER)` | `.to(Group, "eng", "member")`（保留）|
| grant dept all_members | `.to(Department, "hq", Department.Perm.ALL_MEMBERS)` | `.to(Department, "hq", "all_members")` |
| revoke 对称 | `.from(...)` / `.fromWildcard(...)` | 同 |
| check | `.by(User, "u-ceo")` | — |
| checkAll | `.checkAll(Document.Perm).by(User, id)` | — |
| byAll 多资源 | `.checkAll(Document.Perm).byAll(User, id)` | — |
| lookup resources | `.findBy(User, "u-ceo")` | — |
| lookup subjects | `.who(User, Document.Perm.VIEW)` | — |
| 批量跨资源 | `client.batch().on(Space, id).grant(...).to(User, id)` | — |
| 批量 check | `client.batchCheck().add(Document, id, Document.Perm.VIEW, SubjectRef.of(User, id))` | — |

### 类初始化循环依赖防护

POC 验证发现：Schema 内的 Proxy 字段初始化时，写短名 `Organization.Rel.ADMIN` 会被 Java 优先解析为 `Schema.Organization.Rel.ADMIN`（字段访问链），而此时 `Schema.Organization` 字段尚未初始化 → NPE。

**解决方案**：codegen 对 Proxy 内的 enum 引用**必须生成 FQN**：

```java
public static final class OrganizationRelProxy {
    public final com.authx.testapp.schema.Organization.Rel ADMIN =
        com.authx.testapp.schema.Organization.Rel.ADMIN;  // ← FQN
    // ...
}
```

生成后外观丑，但这是"实现细节文件"，业务代码永远看不到。

---

## PR Breakdown

| PR | Branch | 内容 | 是否可独立 merge |
|---|---|---|---|
| **PR-A+B (combined)** | `feature/schema-flat-descriptors` | req-1..8 — codegen + SDK 全量重载 + ResourceType 去 final | ❌ 必须合二为一（单独 A 会让 test-app 编译失败） |
| **PR-C** | 同一分支后续 commit | req-9 — test-app 四个 service + 测试迁移 | ✅（PR-A+B merge 后） |
| **PR-D** | 同一分支后续 commit | req-10 — 文档更新 + migration guide | ✅ |

实际是**一个 feature branch，三段 commit**（每段可独立 revert）。

---

## Testing Strategy

| 测试 | 覆盖 req | 路径 |
|---|---|---|
| `AuthxCodegenTest`（扩展） | req-1, req-2, req-3 | `src/test/java/com/authx/sdk/AuthxCodegenTest.java` |
| `SchemaInitOrderTest`（新建） | req-1 (NPE 防护) | `test-app/src/test/java/com/authx/testapp/schema/SchemaInitOrderTest.java` |
| `ResourceTypeSubclassTest`（新建） | req-4 | `src/test/java/com/authx/sdk/ResourceTypeSubclassTest.java` |
| `TypedGrantActionTest`（扩展） | req-5, req-6 | 现有测试 + 新用例断言 wire format |
| `TypedCheckActionTest`（扩展） | req-7 | 同上 |
| `TypedCheckAllProxyTest`（新建） | req-8 | `src/test/java/com/authx/sdk/TypedCheckAllProxyTest.java` |
| `CrossResourceBatchTypedTest`（扩展） | req-5, req-6 批量路径 | 现有测试 + 新用例 |
| 原有 `*ServiceTest`（迁移） | req-9 | `test-app/src/test/...`（行为不变） |

**一致性测试**（关键）：每个新 typed 重载都要和字符串对应路径**断言生成完全相同的 wire format**（`SubjectReference` proto 字段 by field 相等）。

**闸门**：
- 每个 task 后 `./gradlew compileJava` 必过
- 每个 phase 后 `./gradlew test -x :test-app:test` 必过
- 最终 `./gradlew test` 全绿

---

## Edge Cases

1. **User.Perm 空 enum** — schema 中 user 无 permission 声明。codegen 生成 `enum Perm {}` 合法，`UserPermProxy` 生成为无字段的 empty class。`checkAll(User.Perm)` 结果为空 EnumMap（合法，零 RPC call）。
2. **Java 关键字冲突** — 若 schema 有 relation name 是 Java 保留字（`new`、`class` 等），codegen fail-fast 报 "relation name `xxx` conflicts with Java reserved word, rename in schema.zed"。
3. **Schema.Organization 字段 vs Organization enum 类同名** — JLS §6.4.2 硬规则，业务文档显式说明："要拿 enum 的 Class token，写 `com.authx.testapp.schema.Organization.Rel.class`（FQN）而不是裸 `Organization.Rel.class`"。
4. **用户自建 ResourceType 子类** — `protected` 构造器允许同包或子类调用。SDK 核心逻辑走 `name()` / `relClass()` / `permClass()` 接口访问，不依赖具体子类身份，所以用户自建子类不会破坏 SDK 行为（虽然文档不推荐）。
5. **typed 和字符串 sub-relation 行为一致性** — 单测 `TypedGrantActionTest.subRelationWireFormatIdentical()` 强制 byte-level 对比。

---

## Open Questions

无。全部设计决策已在 brainstorming 阶段确认：

| # | 问题 | 决定 |
|---|---|---|
| Q1 | Schema.java 包位置 / 命名 | `com.authx.testapp.schema.Schema` |
| Q2 | Descriptor 文件布局 | 全部 static nested 在 `Schema.java` 内 |
| Q3 | Proxy 是否暴露 `enumClass()` | 不暴露；SDK 为 `checkAll` 等加 Proxy 重载 |
| Q4 | wildcard 方法命名 | `.toWildcard(User)` / `.fromWildcard(User)` |
| Q5 | `Xxx.TYPE` 过渡期 | 无过渡期，PR-C 一刀切 |
| Q6 | 字符串 sub-relation 保留 | 保留（typed + string 多态并存） |

---

## Success Criteria

1. `./gradlew compileJava` + `./gradlew test` 全绿
2. `CompanyWorkspaceService.java` 字符数减少 ≥ 25%（裸眼可见）
3. 任何 `.TYPE` 字符串在 `test-app/src/main/java/com/authx/testapp/service/` 下 grep 为零
4. `Schema.java` 里所有 enum 引用都是 FQN（防 NPE 回归）
5. typed `.to(Group, "eng", Group.Rel.MEMBER)` 与字符串 `.to(Group, "eng", "member")` 的 wire format byte-level 相同
6. 外部用户（README 示例）的迁移路径在 `docs/migration-schema-flat-descriptors.md` 里有完整 before/after

---

## References

- ADR 2026-04-18 (remove L1 cache) — 本 spec 不恢复 decision cache
- Spec `specs/2026-04-21-schema-aware-codegen/spec.md` — 前一期建立的 schema-aware 基础
- POC 验证文件 `/tmp/javatest2/` — Descriptor+EnumProxy 可行性实证（会话内实验）
- JLS §6.4.2 Obscuring — 同名变量遮蔽类型的语义依据
