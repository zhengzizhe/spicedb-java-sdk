# 代码美化 Phase 2：API 层重构

**日期**：2026-04-08
**范围**：ResourceHandle 拆分、AuthxClient Builder 提取、API 层 Javadoc、import 整理
**原则**：用户 API 完全不变，只改内部组织。所有现有测试必须通过。

---

## req-1: ResourceHandle 内部类提取为顶层类

将 ResourceHandle.java 的 11 个内部类提取到 `com.authx.sdk.action` 包：

| 内部类 | 提取为 | 职责 |
|---|---|---|
| GrantAction | `action/GrantAction.java` | grant().to()/toSubjects() |
| RevokeAction | `action/RevokeAction.java` | revoke().from()/fromSubjects() |
| RevokeAllAction | `action/RevokeAllAction.java` | revokeAll().from() |
| CheckAction | `action/CheckAction.java` | check().by()/byAsync() |
| CheckAllAction | `action/CheckAllAction.java` | checkAll().by() |
| WhoBuilder | `action/WhoBuilder.java` | who().withPermission()/withRelation() |
| SubjectQuery | `action/SubjectQuery.java` | who()...fetch()/fetchSet() |
| RelationQuery | `action/RelationQuery.java` | relations().groupByRelation() |
| BatchBuilder | `action/BatchBuilder.java` | batch().grant().revoke().execute() |
| BatchGrantAction | `action/BatchGrantAction.java` | batch 内的 grant |
| BatchRevokeAction | `action/BatchRevokeAction.java` | batch 内的 revoke |

**规则**：
- 类声明为 `public class`（方法链返回类型必须 public）
- 构造器为 package-private（用户不直接实例化）
- 构造器参数：`String resourceType, String resourceId, SdkTransport transport, String defaultSubjectType` + 操作特定参数
- ResourceHandle 瘦身为 ~100 行入口类

**验证标准**：
- ResourceHandle.java < 150 行
- action/ 包下 11 个文件
- 所有现有测试通过（API 不变）
- `doc.grant("editor").to("alice")` 等链式调用编译通过

## req-2: AuthxClient Builder 提取

将 AuthxClient 的 Builder 类及其内部类（ConnectionConfig、CacheConfig、FeatureConfig、ExtendConfig、BuildContext）提取到 `AuthxClientBuilder.java`。

**规则**：
- `AuthxClientBuilder` 为 public class，与 AuthxClient 同包
- `AuthxClient.builder()` 返回 `AuthxClientBuilder`
- 4 个 Config 类保持为 Builder 的内部类（它们逻辑上属于 Builder）
- buildTransportStack()、buildWatch()、buildScheduler()、buildChannel() 跟随 Builder 移动

**验证标准**：
- AuthxClient.java < 300 行
- AuthxClientBuilder.java 包含完整 Builder 逻辑
- `AuthxClient.builder().connection(c -> ...).build()` 编译通过

## req-3: API 层 Javadoc

为以下文件的所有 public class 和 public method 补齐 Javadoc：

- ResourceHandle.java — 类 + 每个入口方法（grant、revoke、check、who、relations、batch、expand）
- AuthxClient.java — 类 + on()、resource()、lookup()、batch()、metrics()、health()、close()、inMemory()
- AuthxClientBuilder.java — 类 + connection()、cache()、features()、extend()、build()
- ResourceFactory.java — 补全缺失的方法 Javadoc
- LookupQuery.java — 类 + 所有公开方法
- CrossResourceBatchBuilder.java — 类 + on()、execute()
- 所有 action/ 包的类 — 类级 Javadoc + 关键方法

**验证标准**：API 层每个 public class 有类级 Javadoc；每个 public method 有 Javadoc 或 @Override。

## req-4: import 整理

API 层文件禁止通配符 import，全部显式。

**范围**：AuthxClient、AuthxClientBuilder、ResourceHandle、ResourceFactory、LookupQuery、CrossResourceBatchBuilder、action/ 包所有文件。

**验证标准**：scope 内文件无 `import xxx.*`。

## req-5: action 包 @NullMarked

为 `com.authx.sdk.action` 包创建 `package-info.java`，标注 `@NullMarked`。

**验证标准**：action/ 包有 package-info.java。
