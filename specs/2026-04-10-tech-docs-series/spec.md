# AuthX 授权系统技术沉淀文档系列 — Spec

**日期**：2026-04-10
**状态**：待评审
**类型**：文档交付（非代码实现）

---

## 1. 背景

AuthX SDK 是基于 SpiceDB 的 Java 授权 SDK，已完成核心功能、集群基准、ADR 沉淀。当前缺失一份系统化的技术文档，能够：

- 向技术领导呈现**授权领域的技术判断链路**（论文 → 生态 → 选型 → 实现）
- 将分散的 ADR、benchmark report、resilience-guide、cache-consistency-guide 等素材整合为**自顶向下的叙事骨架**
- 作为长期的**团队/个人技术沉淀**，具备独立可读性，可在新人 onboarding、架构评审、技术分享等场景复用

本文档规格定义该系列的结构、风格、内容边界与交付方式。

---

## 2. 目标与非目标

### 2.1 目标（requirements）

| ID | 要求 |
|---|---|
| req-1 | 产出 8 篇正文 + 1 篇索引，共 9 份 Markdown 文档，覆盖 Zanzibar 论文 → 生态横评 → 存储选型 → 基准实测 → SDK 架构 → 缓存一致性 → 韧性设计 → 工程原则 |
| req-2 | 所有文档采用**参考手册风格**：决策、事实、数据、表格、代码引用为主；不采用叙事体；不使用第一人称情绪性表达 |
| req-3 | 所有技术论断必须有可追溯的证据：论文引用、源码路径、ADR 编号、benchmark 数据点、配置文件行号 |
| req-4 | 每篇文档具备独立可读性：可以被单独引用、检索、转发，不依赖前后文才能看懂 |
| req-5 | 全系列复用项目现有素材（2 份 benchmark report、1 份 ADR、resilience-guide、cache-consistency-guide、现有 specs），不重复造轮子 |
| req-6 | 全中文，不做双语 |
| req-7 | 每篇文档落盘到 `docs/tech-series/NN-<slug>.md`，索引 `docs/tech-series/README.md` 提供目录与阅读路径 |

### 2.2 非目标（non-goals）

- 不做 PPT / slides / 演讲稿
- 不做个人反思录、成长记录、晋升材料口吻的文字
- 不做对外发布的白皮书（不追求合规/法务/品牌打磨）
- 不重写已有 ADR 和 benchmark report —— 这些作为底层引用存在，系列文章在其上做抽象和整合
- 不覆盖 SpiceDB 的使用教程（quickstart 类内容由官方文档承担）

---

## 3. 受众与语境

**首要受众**：技术领导（架构师 / 技术总监级别，具备分布式系统和鉴权背景）

**次要受众**：团队内其他工程师、后续加入的新人

**阅读场景**：
- 从 00 索引顺序读完全系列（~1.5–2 小时）
- 按需跳读某一篇（如只关心"存储选型"或"基准实测"）
- 被 ADR / code review / 架构评审文档交叉引用

---

## 4. 风格规范（style guide，硬性约束）

本系列的风格已通过样例确认，定位为"**CockroachDB architecture docs / etcd raft docs / Redis 参考手册**"那一类工程参考文档。

### 4.1 必须做（do）

- 开门见山：每节第一句是**结论或事实**，不铺垫、不设悬念
- 表格承载结构化事实：维度对比、配置项、性能数据优先用表格
- 引用优先：数据必须注明来源（`docs/benchmark-report-2026-04-10-detailed.md §2.2`、`src/main/java/com/authx/sdk/cache/CaffeineCache.java:42`、`ADR-2026-04-08` 等）
- 使用"本 SDK / 本项目 / AuthX SDK"作为主语
- 每段传达事实或论证，段落之间不做情绪过渡

### 4.2 不得做（don't）

- 不使用"最初是这么设计的、后来发现、但是……"这类时间叙事
- 不使用"结论很朴素"、"数据和直觉唱反调"、"让人眼前一亮"这类修辞性收束句
- 不使用第一人称："我当时"、"我们踩过"、"回过头看"
- 不使用情绪性词汇："惨痛教训"、"意外发现"、"坑点"
- 不写"本文将会介绍……"这种自我指涉的引导语
- 不为了凑字数而展开常识（例如不解释"什么是 gRPC"）

### 4.3 样例（已确认）

见本 spec 附录 A：《为什么移除 Redis L2 缓存》参考段落。

---

## 5. 文档骨架（9 篇）

### 00 — 总览与阅读指南（README.md）

| 项 | 内容 |
|---|---|
| 目的 | 系列地图 + 一句话结论 + 推荐阅读路径 |
| 长度 | 1,000–1,500 字 |
| 核心 | 9 篇列表、每篇 2–3 行摘要、交叉引用图、阅读路径推荐（完整路径 / 快速路径 / 按角色路径） |

### 01 — 授权模型演进与 Zanzibar 论文精读

| 项 | 内容 |
|---|---|
| 目的 | 讲清楚授权问题的演进和 Zanzibar 为什么成为事实标准 |
| 长度 | 4,000–5,000 字 |
| 核心 | ACL/RBAC/ABAC/ReBAC 的能力边界 · Google Zanzibar 论文（USENIX ATC 2019）核心概念：namespace config、relation tuple、userset rewrite、zookie 一致性 token、Leopard index、dispatch · 论文的工程取舍（一致性 vs 可用性、复杂度 vs 可表达性） · 为什么这篇论文后成为事实标准 |
| 素材来源 | Zanzibar 论文原文、公开技术博客、SpiceDB/FGA 的 schema 文档 |

### 02 — Zanzibar 生态横评：SpiceDB / OpenFGA / Ory Keto

| 项 | 内容 |
|---|---|
| 目的 | 对比三大主流开源实现，论证 SpiceDB 为当前最合适选择 |
| 长度 | 4,000–5,000 字 |
| 核心 | 三者技术路线对比表（语言、架构、schema DSL、一致性模型、存储后端、性能数据、社区活跃度、生产案例） · Schema DSL 语法对比（SpiceDB zed / FGA DSL / Keto 配置）· 一致性模型对比（ZedToken / Consistency modes / 无）· 特性矩阵（watch / lookup / bulk / caveats / wildcards）· 选型结论 |
| 素材来源 | 三个项目的官方文档与 GitHub、已有 ADR 中的 Zanzibar 生态表 |

### 03 — 存储选型：为什么 SpiceDB + CockroachDB

| 项 | 内容 |
|---|---|
| 目的 | 论证 SpiceDB datastore 的选型逻辑 |
| 长度 | 3,500–4,500 字 |
| 核心 | SpiceDB 支持的 datastore 矩阵（Postgres、CRDB、Spanner、MySQL、memory）· 每个 datastore 的权衡（一致性、横向扩展、Zedtoken 语义、运维成本、地理分布）· CRDB 的 Raft 一致性与 Zanzibar 外部一致性的契合点 · 本项目选 CRDB 的最终依据 |
| 素材来源 | SpiceDB datastore 文档、CockroachDB 架构文档、本项目集群部署配置 |

### 04 — 性能基准实测：3 节点集群 / 366K 关系 / 多维度

| 项 | 内容 |
|---|---|
| 目的 | 提供系列中最硬的数据层证据 |
| 长度 | 3,500–4,500 字 |
| 核心 | 基于 `docs/benchmark-report-2026-04-10-detailed.md` 的数据抽象与提炼 · 缓存命中率维度（HOT/Zipfian/MISS）· 权限深度维度（D0–D9，cold/warm） · 与官方 benchmark 和 FGA 公开数据的对比 · 数字背后的含义解读（例如 38× 吞吐退化意味着什么） |
| 素材来源 | `docs/benchmark-report-2026-04-10-detailed.md`、`docs/benchmark-report-2026-04-08.md`、`deploy/wrk-*.lua` |

### 05 — SDK 架构与设计决策

| 项 | 内容 |
|---|---|
| 目的 | 讲清楚本 SDK 的架构取舍，不是使用教程 |
| 长度 | 4,000–5,000 字 |
| 核心 | 为什么自研 SDK（对比裸 gRPC stub 的能力缺口） · 传输链架构（gRPC → resilient → cached → instrumented） · SPI 扩展点 · 异常层次（exception hierarchy） · 生命周期状态机 · 不设人为上限的设计原则（类比 JDBC/Redis 客户端） |
| 素材来源 | `src/main/java/com/authx/sdk/`、`src/main/resources/META-INF/authx-sdk/GUIDE.md`、`.claude/exception-hierarchy.md` |

### 06 — 缓存一致性与 Watch 失效

| 项 | 内容 |
|---|---|
| 目的 | 讲清楚 L1 缓存 + Watch 失效的一致性模型与移除 L2 的决策 |
| 长度 | 4,000–5,000 字 |
| 核心 | 四级缓存全景（L1 Caffeine / L2 已移除 / L3 SpiceDB dispatch / L4 CRDB） · Watch 流失效机制 · 与 ZedToken 的协同 · 失效路径的时序与一致性等级 · Redis L2 移除决策的完整论证 · per-type/per-permission TTL + jitter · single-flight 与 request coalescing |
| 素材来源 | `docs/cache-consistency-guide.md`、`docs/adr/2026-04-08-remove-redis-l2-cache.md`、`src/main/java/com/authx/sdk/cache/`、`src/main/java/com/authx/sdk/transport/WatchCacheInvalidator.java` |

### 07 — 韧性设计：熔断、重试、降级、可观测

| 项 | 内容 |
|---|---|
| 目的 | 讲清楚本 SDK 的韧性策略与可观测设计 |
| 长度 | 3,500–4,500 字 |
| 核心 | Resilience4j 集成（circuit breaker / retry / rate limiter / bulkhead） · 外部依赖降级路径（UNIMPLEMENTED 单次告警策略） · SLO 定义 · OpenTelemetry / Micrometer 集成 · 事件总线与 telemetry sink · HdrHistogram 精细百分位 |
| 素材来源 | `docs/resilience-guide.md`、`src/main/java/com/authx/sdk/builtin/`、`src/main/java/com/authx/sdk/policy/`、`src/main/java/com/authx/sdk/metrics/`、`src/main/java/com/authx/sdk/telemetry/` |

### 08 — 工程原则与设计准则

| 项 | 内容 |
|---|---|
| 目的 | 从前 7 篇抽象出可复用的通用工程原则 |
| 长度 | 2,500–3,500 字 |
| 核心 | 每条原则按 "定义 / 适用条件 / 本项目中的体现（引用 01–07）/ 反例" 格式组织。候选原则：① no artificial limits · ② verify metrics claims · ③ graceful degradation · ④ 独立技术判断（不盲从技术方向也不盲目否定） · ⑤ 证据先行（每个决策必须可追溯到数据/论文/源码） · ⑥ 参考 vs 抄袭（借鉴设计原则，不复制具体实现） |
| 注意 | 此篇**不得滑向个人反思录**。原则必须以第三人称的 "本项目采取 X 原则，因为 Y" 的形式陈述，引用前 7 篇作为证据链 |

---

## 6. 素材映射

既有素材与目标篇章的对应：

| 既有素材 | 被哪些篇引用 |
|---|---|
| `docs/benchmark-report-2026-04-10-detailed.md` | 04（主数据源）、06（缓存命中数据）、07（尾部延迟数据） |
| `docs/benchmark-report-2026-04-08.md` | 04（历史对比） |
| `docs/adr/2026-04-08-remove-redis-l2-cache.md` | 06（主论证）、08（证据原则举例） |
| `docs/cache-consistency-guide.md` | 06（主素材） |
| `docs/resilience-guide.md` | 07（主素材） |
| `src/main/resources/META-INF/authx-sdk/GUIDE.md` | 05（主素材） |
| `.claude/exception-hierarchy.md` | 05（异常层次） |
| `specs/2026-04-07-cache-refactor/` | 06（历史演进） |
| `specs/2026-04-08-cluster-benchmark/` | 04（测试方法） |

外部素材需求：
- Zanzibar 论文（USENIX ATC 2019）—— 01 精读
- SpiceDB 官方文档 —— 02、03
- OpenFGA 官方文档 / GitHub —— 02
- Ory Keto 官方文档 —— 02
- CockroachDB 架构文档 —— 03

---

## 7. 交付落盘

### 7.1 文件结构

```
docs/tech-series/
├── README.md                      # 00 总览与阅读指南
├── 01-zanzibar-paper.md
├── 02-ecosystem-comparison.md
├── 03-storage-selection-crdb.md
├── 04-benchmark.md
├── 05-sdk-architecture.md
├── 06-cache-consistency.md
├── 07-resilience.md
└── 08-engineering-principles.md
```

### 7.2 交付节奏

- 逐篇写，每篇写完后停顿，交付当前篇内容
- 用户 review 并给出修改意见，修订到位后再写下一篇
- 顺序：01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 00（索引最后写，因为需要全局信息）

### 7.3 完成标准（每篇）

| 检查项 | 标准 |
|---|---|
| 风格 | 通过风格规范（§4）的所有 do/don't 检查 |
| 证据 | 所有技术论断都有可追溯的引用 |
| 独立性 | 不依赖前后文即可单独阅读 |
| 长度 | 落在本 spec §5 规定的区间内（±20% 可接受） |
| 交叉引用 | 与其他篇的引用使用相对路径，链接有效 |
| 用户确认 | 当篇经用户 review 并确认后方视为完成 |

---

## 8. 成功判据

| ID | 判据 |
|---|---|
| succ-1 | 9 篇文档全部落盘到 `docs/tech-series/`，文件命名、结构、链接符合 §7.1 |
| succ-2 | 每篇通过 §4 的风格检查（无叙事、无第一人称、无情绪性修辞） |
| succ-3 | 用户 review 后确认每篇符合预期 |
| succ-4 | 00 索引能让不熟悉项目的技术读者在 3 分钟内理解系列结构并选择阅读路径 |
| succ-5 | 08 原则篇的每条原则都能在 01–07 中找到至少一个证据点 |

---

## 附录 A — 已确认的参考段落

以下段落由用户在 brainstorming 阶段确认风格对味，作为全系列的行文基线：

> ### 移除 Redis L2 缓存
>
> **决策**：2026-04-08 起，AuthX SDK 缓存层仅保留 Caffeine 本地缓存，移除 Redis L2。
>
> **原设计**：两级缓存——Caffeine（本地，sub-ms 命中）+ Redis（跨实例共享）。
>
> **移除依据**：
>
> | 维度 | 数据 / 事实 |
> |---|---|
> | 延迟 | SpiceDB 服务端自带缓存，Check 命中 P99 ≈ 3ms；Redis 跨网段 RTT 1.5–2ms + 序列化，可压缩空间 < 1ms |
> | 压测 | 3 节点集群 / 366K 关系下，带 Redis 与纯本地缓存版本 QPS 与 P99 曲线重合；移除后 P99 下降 0.4ms |
> | 一致性 | Watch 流仅驱动本地失效；Redis 失效需额外 pub/sub 通道，引入分区 / 重连 / 丢消息的故障面 |
> | 污染半径 | 本地缓存污染影响 1 实例；Redis 缓存污染影响整个集群 |
>
> **替代方案**：跨实例共享职责下沉到 SpiceDB 服务端缓存；本地 Caffeine 承担热点加速与请求合并。
>
> **参考**：`docs/adr/2026-04-08-remove-redis-l2-cache.md`、`docs/benchmark-report-2026-04-10-detailed.md`

特征总结：
1. 先决策，后依据
2. 表格承载结构化事实
3. 无时间叙事、无修辞收束
4. 每段独立、可检索、可被引用
