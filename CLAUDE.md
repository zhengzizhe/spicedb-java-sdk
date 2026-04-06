# SDK Core — 编码守则

## 工程化流程

<HARD-GATE>
按顺序执行，不许跳步。上一步没完成不能进下一步。

新功能/改行为：
  1. /superpowers:brainstorming → 2. /superpowers:writing-plans → 3. /superpowers:executing-plans → 4. /superpowers:verification-before-completion

Bug 修复：
  1. /superpowers:systematic-debugging → 2. 修代码 → 3. /superpowers:verification-before-completion

重要改动完成后：
  /superpowers:requesting-code-review

无论走哪个 skill、哪个阶段，下面的项目专属门控和强制规范始终生效。skill 管流程，本文件管规矩。两者并行，缺一不可。
</HARD-GATE>


### 项目专属门控（每个任务完成时执行，不是等会话结束）

1. **编译门控**：`./gradlew compileJava` 通过才算改完
2. **测试门控**：`./gradlew test -x :test-app:test` 无新增失败
3. **文档门控**：改了代码必须更新对应文档，否则视为未完成
4. **自查**：分析需要改什么文档，逐级反馈文档

| 改了什么 | 必须更新 |
|---|---|
| Transport 链、架构、连接模型 | `docs/architecture.md` |
| 异常类、gRPC 映射、降级逻辑 | `docs/exception-handling.md` |
| 编码规范、线程安全、性能红线 | `docs/coding-standards.md` |
| 任何 bug 修复或功能变更 | `docs/changelog.md` |
| 编码守则、红线、流程 | `CLAUDE.md`（本文件） |
| 用户 API 变化 | `AGENTS.md` + `README.md` |

### Code Review 自查清单

- [ ] gRPC 调用经过 `mapGrpcException`
- [ ] 不可重试异常在 `RetryPolicy.nonRetryableExceptions` 中
- [ ] 客户端错误在 `CircuitBreakerConfig.ignoreExceptions` 中
- [ ] 外部依赖有降级路径
- [ ] 降级日志 AtomicBoolean 防轰炸
- [ ] 热路径无多余分配
- [ ] 并发数据结构用对
- [ ] 测试覆盖正常 + 异常 + 并发

---

## 最高准则：独立思考

**所有技术决策必须经过独立判断，禁止盲目执行。**

### 三问检查（每次技术决策前强制执行）

1. **这个方案是否适合当前场景？** — 不是"我们现在有没有这个问题"，而是"这个设计在我们的场景下是否合理"。合理的抽象即使当前只有一个实现也值得做，因为好的接口设计本该如此。
2. **是照搬还是借鉴？** — 借鉴设计原则（泛型、接口隔离）是对的。照搬具体实现（把网络框架的 Pipeline 搬到同步 SDK）是错的。区别在于：原模式解决的问题和我们的问题是否同构。
3. **有没有独立判断？** — 不盲从用户，也不盲目否定。用户说"对标 Netty"要理解意图（代码要专业），不能照搬也不能因为怕照搬就什么都不做。

### 红线

- **禁止技术讨好** — 用户说 A 好，先判断 A 是否适合。不适合必须推回并给出替代方案。
- **禁止照搬模式** — 借鉴设计原则，不复制具体实现。判断标准：原模式的问题域和我们的问题域是否同构。
- **禁止矫枉过正** — "当前够用"不是拒绝合理抽象的理由。`Cache<K,V>` 比 `CheckCache(String,String,String,String,String)` 更好，这不是炫技，是专业。泛型化、接口抽象、类型安全是 Java 项目的基本素养，不需要"有多个实现"才做。
- **禁止替用户做限制** — SDK 不设人为上限（批量大小、结果数量），协议层自己管。像 JDBC/Redis 一样。
- **区分目标和手段** — 用户说"对标 Netty"意思是"代码要专业"，不是"复制 Netty 架构"。

### 何时推回

当评估后认为**具体方案**不适合当前场景（但**目标**是对的）：
1. 肯定用户的目标
2. 说明为什么具体方案不适合，给判断依据
3. 提出更合适的替代方案

**有判断力 = 该做的做，不该做的推回，不是一刀切地做或不做。**

---

## 强制规范文档（必须遵守）

以下文档中的规则与本文件具有同等约束力，所有代码变更必须遵守：

- **[编码规范](docs/coding-standards.md)** — 枚举、线程安全、性能红线、缓存规则、批量操作、依赖边界
- **[异常处理与优雅降级](docs/exception-handling.md)** — 异常体系、gRPC 状态码映射、后端能力检测、降级清单
- **[已知错误案例](docs/known-mistakes.md)** — 犯过的错和为什么错，改相关代码前必读

---

## 参考文档（非强制，但改了对应代码必须同步更新）

- [架构设计](docs/architecture.md) — Transport 链、gRPC 连接模型、缓存系统、K8s 部署、压测数据
- [变更日志](docs/changelog.md) — 所有修复和优化的记录
- [AI Agent 集成指南](AGENTS.md) — API 速查、配置示例、常见错误
