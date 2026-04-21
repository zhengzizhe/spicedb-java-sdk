# Schema-Aware Codegen

## Summary

把从 SpiceDB schema 生成 typed Java 类的能力恢复到 AuthX SDK，并**升级**成 schema-aware —— 每个 relation 的 enum value 带上 schema 里声明的允许 subject 类型。以此为基础，SDK 侧分阶段让业务代码 `.to(id)` / `.to(ResourceType, id)` 写得更短、错用时 fail-fast、caveat 用 typed 工厂。

**范围**：codegen 本身 + 支撑 codegen 的 SDK 侧 schema 读取通路 + 两级 SDK 使用端改造 + typed caveat。分 4 个独立 PR 滚动上线。

## 背景

AuthX SDK 曾有一个 `AuthxCodegen.java`（commit 195eb75 时期），在 commit `025b7a1`（"remove L1 cache + Watch infrastructure"）那次**随 L1 cache 一起被误伤删除**，连带 `SchemaClient` / `SchemaLoader` / `SchemaCache` 整条 schema 读取通路。历史 codegen 生成的产物（`test-app/src/main/java/com/authx/testapp/schema/*.java`，7 个文件）保留在 main 分支上仍在使用，但**生成器本身在 main 上已经不存在**。

旧 codegen 只消费 `relationsOf` / `permissionsOf` / `caveatNames`，**从未消费** `relation → 允许 subject types` 这一列信息，即使 `SchemaLoader` 已经 parse 过。这次恢复 + 补完这条通路。

## 用户痛点

业务代码里典型的 subject 表达长这样：

```java
.to(SubjectRef.of("folder", folderId, null))       // 前缀 "folder" + 尾缀 null
.to("user:" + userId)                               // 字符串拼接
.to("group:" + groupId + "#member")                 // 字符串拼接
.to("user:*")                                       // 字符串通配
```

所有 subject 类型名（`"folder"` / `"user"` / `"group"`）都是业务手写的魔法字符串。schema 里规定 relation 允许哪些 subject 类型的信息没有传递到业务代码，导致：

1. 每次 grant / revoke / check 都要重复手写 subject 类型前缀
2. 传错 subject 类型（例如给只接受 folder 的 relation 传 user）要发到 SpiceDB 才报错
3. IDE 无法补全 "这个 relation 允许什么 subject"
4. Caveat 通过字符串 Map 传参，容易拼错 key 或类型

## Data Source

SpiceDB `ExperimentalReflectSchema` gRPC 返回 `ExperimentalReflectSchemaResponse`：

- `getDefinitionsList()` — 每个 `ExpDefinition` 含：
  - `getName()` — resource 类型名（`"document"`）
  - `getRelationsList()` — 每个 `ExpRelation`：
    - `getName()` — relation 名（`"folder"`）
    - `getSubjectTypesList()` — 每个 `ExpRelationSubjectType`：
      - `getSubjectDefinitionName()` — subject 类型名（`"folder"`）
      - `getOptionalRelationName()` — subject relation（`"member"`）
      - `getIsPublicWildcard()` — 是否 `:*` 通配
  - `getPermissionsList()` — 每个 `ExpPermission`.getName()
- `getCaveatsList()` — 每个 `ExpCaveat` 含 `getName()` / `getParametersList()` / `getExpression()` / `getComment()`

codegen 直接消费 live SpiceDB 连接的 ReflectSchema 响应。**不做** `.zed` 文件解析（避免自己实现 zed 语法 parser）。

## Requirements

### 第一期：Codegen 本身 + SDK 读取通路

#### req-1：恢复 `SchemaLoader`

新增 `src/main/java/com/authx/sdk/transport/SchemaLoader.java`，调用 SpiceDB `ExperimentalReflectSchema` gRPC，解析 definitions + caveats。每个 relation 的 subject types 被提取成 `List<SubjectType>`。UNIMPLEMENTED 降级为"schema 不可用"（非致命）。

**成功标准**：连一个带已知 schema 的 SpiceDB 实例，`load()` 后能拿到所有 resource types、relations、permissions、relation→subject types 映射和 caveats。

#### req-2：恢复 `SchemaCache`（仅元数据部分）

新增 `src/main/java/com/authx/sdk/cache/SchemaCache.java`。**只保留** schema 元数据缓存（definitions + caveats），**不恢复** permission check 结果缓存（与 ADR 2026-04-18 保持一致）。记录：
- `DefinitionCache(Set<String> relations, Set<String> permissions, Map<String, List<SubjectType>> relationSubjectTypes)`
- `CaveatDef(String name, Map<String, String> parameters, String expression, String comment)`

暴露：`getResourceTypes()` / `getRelations(type)` / `getPermissions(type)` / `getSubjectTypes(type, rel)` / `getCaveatNames()` / `getCaveat(name)` / `hasSchema()`.

**成功标准**：`SchemaLoader.load()` 写入后，所有 getter 能回读。

#### req-3：恢复 `SchemaClient` public API

新增 `src/main/java/com/authx/sdk/SchemaClient.java`，包装 `SchemaCache` 暴露给业务代码内省。`AuthxClient` 加 `schema()` 访问器。

**成功标准**：`client.schema().resourceTypes()` 等公开方法可用且与 SchemaCache 数据一致。

#### req-4：新增 `SubjectType` record

新增 `src/main/java/com/authx/sdk/model/SubjectType.java`：

```java
public record SubjectType(String type, String relation, boolean wildcard) {
    public static SubjectType parse(String s);     // "user" / "group#member" / "user:*"
    public static SubjectType of(String type);
    public static SubjectType of(String type, String relation);
    public static SubjectType wildcard(String type);
    public String toRef();                          // 反向
}
```

`Relation.Named` 接口加默认方法：

```java
default List<SubjectType> subjectTypes() { return List.of(); }
```

**成功标准**：单元测试 `SubjectTypeTest` 覆盖所有工厂与 parse/toRef 往返。默认方法让现有无此信息的 Rel 产物零影响。

#### req-5：`AuthxClient.Builder` 启动时加载 schema

Builder 里新增 `.loadSchemaOnStart(boolean)`（默认 true）。启动时调用 `SchemaLoader.load()`，失败降级 `hasSchema() == false`。为 in-memory transport 预留 opt-out。

**成功标准**：集成测试连真实 SpiceDB，启动后 `client.schema().isLoaded() == true`。

#### req-6：恢复 `AuthxCodegen` 主类

新增 `src/main/java/com/authx/sdk/AuthxCodegen.java`。入口：

```java
AuthxCodegen.generate(AuthxClient client, String outputDir, String packageName);
```

对每个 resource type 生成一份 `XxxType.java`：
- `enum Rel implements Relation.Named` —— 每个 value 用 `String... subjectTypeRefs` varargs 构造，`subjectTypes()` 返回 `List<SubjectType>`
- `enum Perm implements Permission.Named` —— 只含 permission 名
- `public static final ResourceType<Rel, Perm> TYPE = ...`

每个 caveat 生成一份 `XxxCaveat.java`：name 常量 + 参数名常量 + `ref(...)` + `context(...)` 工厂（保持与旧版一致）。

跨类型生成：
- `ResourceTypes.java` —— 所有类型名 String 常量
- `Caveats.java` —— 所有 caveat 名 String 常量（schema 里有 caveat 时）

文件头注释写 `Generated by AuthxCodegen at <ISO timestamp> — do not edit.`

**成功标准**：用当前 `deploy/schema.zed` 部署的 SpiceDB 实例跑 codegen，生成的 `Document.java` / `Folder.java` 等与本 spec 附录的"生成样本"字节一致（或结构等价）。

#### req-7：重新生成 `test-app/schema/*.java`

用本期 codegen 重跑 `test-app/src/main/java/com/authx/testapp/schema/`，产物带 subject types 信息。**业务代码零改动** —— `DocumentSharingService` 现有调用全部仍能编译通过（`Relation.Named.subjectTypes()` 是默认方法，业务没调用）。

**成功标准**：
- `./gradlew :test-app:compileJava` 通过
- `./gradlew :test-app:test` 通过
- 7 个 schema 类顶部都有 `Generated by AuthxCodegen at <新 timestamp>`
- `Document.Rel.FOLDER.subjectTypes()` 返回 `[SubjectType{folder}]`
- `Document.Rel.VIEWER.subjectTypes()` 返回 4 个（user、group#member、department#all_members、user:* 通配）

### 第二期：SDK 运行时 subject 校验 （fail-fast）

#### req-8：Grant / Revoke 发送前校验 subject

`GrantAction` / `RevokeAction` / `BatchGrantAction` / `BatchRevokeAction` 构造 `RelationshipUpdate` 前调用 `SchemaCache.validateSubject(resourceType, relation, subjectRef)`。传错类型抛 `InvalidRelationException`，错误信息包含允许的 subject types 列表。schema 未加载时 fail-open（不拦截）。

**成功标准**：
- 单元测试：对 `document.folder` grant `user:alice` 抛 InvalidRelationException，message 包含 `"[folder]"`
- 单元测试：schema 未加载时不抛
- 集成测试：与 SpiceDB 报错前先由 SDK 拦截

#### req-9：Typed 链 API 对应改造

`TypedGrantAction` / `TypedRevokeAction` 对应使用底层 action 后自动继承校验。无额外 API。

**成功标准**：typed 链误用同样抛 InvalidRelationException。

### 第三期：单类型 relation 推断 + typed subject 重载

#### req-10：`GrantAction.to(String id)` 单参推断

当前 relation 的 `subjectTypes()` 恰好只有一个非通配 SubjectType 时，`.to(String id)` 拼成 `type:id`。多类型或纯通配 relation 调用抛 `IllegalArgumentException` 并提示用哪个重载。

**成功标准**：
- `.grant(Rel.FOLDER).to("f-1")` → 写入 `folder:f-1`
- `.grant(Rel.VIEWER).to("alice")` → 抛 `IllegalArgumentException: ambiguous subject type (allowed: [user, group#member, department#all_members, user:*]); use to(ResourceType, id) instead`

#### req-11：`GrantAction.to(ResourceType, String id)` typed 重载

接受 SDK 通用 `ResourceType` 对象 + id，内部转成 `SubjectRef`。校验传入的 type 在当前 relation 允许列表里。同理 `to(ResourceType, String id, String relation)` 支持 group#member 形态。`toWildcard(ResourceType)` 支持 `:*`。

**成功标准**：
- `.grant(Rel.VIEWER).to(User.TYPE, "alice")` 成功
- `.grant(Rel.FOLDER).to(User.TYPE, "alice")` 抛 InvalidRelationException（由 req-8 统一处理）
- `.grant(Rel.VIEWER).toWildcard(User.TYPE)` 写入 `user:*`

#### req-12：Check / Revoke / Lookup 对称处理

`CheckAction.by(ResourceType, id)` / `RevokeAction.from(ResourceType, id)` / `WhoBuilder` / `LookupQuery.findBy(ResourceType, id)` 全部加对应重载。Check 侧**不做** 单类型推断（check subject 与 relation 无关，无从推断）—— 只保留 typed 重载。

**成功标准**：每种 action 的 typed 重载各有单测。

#### req-13：Iterable / Collection 的 `<ResourceType, Iterable<String>>` 重载

保持 PR#17 对 `Iterable<String>` 支持的一致性：
- `.to(ResourceType, Iterable<String> ids)` 批量 grant 单一 subject type 多 id
- 同理 check / lookup

**成功标准**：
- `.grant(Rel.VIEWER).to(User.TYPE, List.of("a", "b", "c"))` 写入 3 条

#### req-14：test-app 迁移演示

`DocumentSharingService` 更新：
- `moveIntoFolder` 用 `.to(folderId)`（单类型推断）
- `setOwner` 用 `.to(userId)`（单类型 user）
- `shareWithUser` 用 `.to(User.TYPE, userId)`（多类型 typed）
- `shareWithGroup` 用 `.to(Group.TYPE, groupId, "member")`
- `makePublic` 用 `.toWildcard(User.TYPE)`

**成功标准**：
- 字符串拼接 `"user:" + id` 在 `DocumentSharingService` 里归零
- 老字符串写法在其他调用点（如 `cluster-test/`）仍能编译通过（向后兼容）

### 第四期：Typed Caveat

#### req-15：`CaveatRef` 构造 API

业务代码：

```java
.withCaveat(IpAllowlist.ref(IpAllowlist.CIDRS, List.of("10.0.0.0/8")))
```

`IpAllowlist.ref(Object... kv)` 返回 `CaveatRef`，拼 `NAME` + key/value Map。`IpAllowlist.context(Object... kv)` 返回 `Map<String,Object>` 用于 `.given(...)`。这两个方法在 req-6 的 caveat 类生成时已经写好。

**成功标准**：
- `Caveats.IP_ALLOWLIST` 常量可用
- `IpAllowlist.ref("cidrs", ...)` 和 `IpAllowlist.CIDRS` 等参数常量存在且名字与 schema 定义对齐
- test-app 新增 `ConditionalShareService` 演示 caveat 全流程

## Non-goals

明确不做的事：

- **不恢复 permission check 结果缓存**（ADR 2026-04-18 已决策，只恢复 schema 元数据缓存）
- **不解析 .zed 文件**（只走 live gRPC ReflectSchema）
- **不支持 Gradle task 封装**（等有人需要再加；当前只提供 `AuthxCodegen.generate(...)` 静态入口）
- **不做 schema 漂移检测 / SchemaSnapshot.java**（YAGNI，可作后续独立功能）
- **不做 per-subject-type-relation 的 typed `.toUser()` / `.toGroupMember()`**（违反"不 hard-code 业务类型"原则，即使由 codegen 生成也会造成 Rel-specific API 爆炸）
- **不做 check 侧的 "default subject type for user"**（PR#16 已明确删除，不回退）
- **不做编译期多类型 relation 约束**（运行时校验已足够，编译期约束需要 per-relation typed handle 类，产物爆炸）

## 分期

| PR | 覆盖 req | 可独立发布 |
|---|---|---|
| PR-A | req-1 ～ req-7 | ✅ codegen 能用，业务代码零变化 |
| PR-B | req-8 ～ req-9 | ✅ 运行时 fail-fast 生效，业务代码零变化 |
| PR-C | req-10 ～ req-14 | ✅ 单类型推断 + typed 重载 + test-app 迁移 |
| PR-D | req-15 | ✅ typed caveat |

每个 PR 独立通过以下测试才能发布：
- `./gradlew compileJava` 通过
- `./gradlew test -x :test-app:test -x :cluster-test:test` 通过
- `./gradlew test` 通过（含下游 test-app / cluster-test）

## 附录 A：生成样本 — `Document.java`

```java
package com.authx.testapp.schema;

import com.authx.sdk.ResourceType;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectType;

import java.util.Arrays;
import java.util.List;

/**
 * Typed metadata for SpiceDB resource type <b>document</b>.
 * Generated by AuthxCodegen at 2026-04-21T... — do not edit.
 */
public final class Document {

    public enum Rel implements Relation.Named {
        COMMENTER("commenter", "user", "group#member", "department#all_members"),
        EDITOR   ("editor",    "user", "group#member", "department#all_members"),
        FOLDER   ("folder",    "folder"),
        LINK_EDITOR("link_editor", "user:*"),
        LINK_VIEWER("link_viewer", "user:*"),
        OWNER    ("owner",     "user"),
        SPACE    ("space",     "space"),
        VIEWER   ("viewer",    "user", "group#member", "department#all_members", "user:*");

        private final String value;
        private final List<SubjectType> subjectTypes;
        Rel(String v, String... sts) {
            this.value = v;
            this.subjectTypes = Arrays.stream(sts).map(SubjectType::parse).toList();
        }
        @Override public String relationName() { return value; }
        @Override public List<SubjectType> subjectTypes() { return subjectTypes; }
    }

    public enum Perm implements Permission.Named {
        COMMENT("comment"),
        DELETE("delete"),
        EDIT("edit"),
        MANAGE("manage"),
        SHARE("share"),
        VIEW("view");

        private final String value;
        Perm(String v) { this.value = v; }
        @Override public String permissionName() { return value; }
    }

    public static final ResourceType<Rel, Perm> TYPE =
            ResourceType.of("document", Rel.class, Perm.class);

    private Document() {}
}
```

## 附录 B：终态业务代码样本

```java
// 单类型 relation —— 裸 id 推断 (PR-C 之后)
public void moveIntoFolder(String docId, String folderId) {
    client.on(Document.TYPE).select(docId)
          .grant(Document.Rel.FOLDER).to(folderId);
}

public void setOwner(String docId, String userId) {
    client.on(Document.TYPE).select(docId)
          .grant(Document.Rel.OWNER).to(userId);
}

// 多类型 relation —— typed 重载 (PR-C 之后)
public void shareWithUser(String docId, String userId) {
    client.on(Document.TYPE).select(docId)
          .grant(Document.Rel.VIEWER).to(User.TYPE, userId);
}

public void shareWithGroup(String docId, String groupId) {
    client.on(Document.TYPE).select(docId)
          .grant(Document.Rel.VIEWER).to(Group.TYPE, groupId, "member");
}

public void makePublic(String docId) {
    client.on(Document.TYPE).select(docId)
          .grant(Document.Rel.VIEWER).toWildcard(User.TYPE);
}

// 错用 —— SDK fail-fast (PR-B 之后)
public void oops(String docId, String userId) {
    client.on(Document.TYPE).select(docId)
          .grant(Document.Rel.FOLDER).to(User.TYPE, userId);
    // ↑ InvalidRelationException: document.folder does not accept subject "user:xxx".
    //                              Allowed subject types: [folder]
}

// Typed caveat (PR-D 之后)
public void shareConditional(String docId, String userId, List<String> cidrs) {
    client.on(Document.TYPE).select(docId)
          .grant(Document.Rel.VIEWER)
          .withCaveat(IpAllowlist.ref(IpAllowlist.CIDRS, cidrs))
          .to(User.TYPE, userId);
}

// 业务内省 (PR-A 之后)
List<SubjectType> allowed = Document.Rel.VIEWER.subjectTypes();
// [SubjectType{user}, SubjectType{group#member},
//  SubjectType{department#all_members}, SubjectType{user wildcard}]
```

## 附录 C：与之前删除的代码的关系

历史 `AuthxCodegen.java` 保存在 `feature/write-listener-api` 等 8 个 feature 分支上（main 分支已删除）。本次**不直接 cherry-pick** —— 历史版本没消费 subjectTypes 信息，且生成代码的 javadoc 示例里仍有砍掉的 `.toUser()` / `findByUser()` API 引用。本次作为**重写**对待，可参考历史实现的字符串工具 (`toPascalCase` / `toConstant`) 和 caveat emit 结构。

历史 `SchemaCache.java` 的 subject type 部分（`SubjectType` record、`validateSubject(...)` 方法、`getSubjectTypes(...)` API）可作为起点参考 —— 数据结构和校验逻辑已经正确。恢复时**剥离** schema 校验之外的部分（例如 L1 permission cache 相关残留）。
