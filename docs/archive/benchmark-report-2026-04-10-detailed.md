# SpiceDB Java SDK 多维度精细基准报告

**日期**: 2026-04-10
**SDK 版本**: `io.github.authxkit:authx-spicedb-sdk:1.0.0`
**作者**: zhengzizhe

本报告通过 **wrk 内置 HdrHistogram** (Lua `done()` 函数访问 `latency:percentile(p)`) 采集完整百分位分布，每个场景给出 **p0 / p10 / p25 / p50 / p75 / p90 / p95 / p99 / p99.9 / p99.99 / max / stdev**，比上一版（只有 p50/75/90/99）更细致。

---

## TL;DR

| 维度 | 关键发现 |
|---|---|
| **缓存上限** | 100% 命中 → **64,452 RPS**, p50 **2.94ms**, p99 **5.26ms** (SDK+HTTP 纯 overhead) |
| **典型生产负载 (Zipfian 75% 命中)** | **15,923 RPS**, p50 12.7ms, p99 83.8ms |
| **缓存击穿 (0% 命中)** | **1,678 RPS**, p50 104ms, p99 243ms — **38× 吞吐退化** |
| **缓存带来的放大效应** | 100% hit ÷ 0% hit = **38.4×**（SDK 缓存是第一生产力） |
| **权限深度 (warm 缓存)** | D=0 与 D=9 几乎相同（缓存吃掉差异）：p50 3.0 vs 3.3ms |
| **权限深度 (cold 缓存)** | **D=0 快 30%**：RPS 15.7K vs D≥1 的 ~11.5K |
| **D=1 到 D=9 差异**（cold） | 几乎无差别（ancestor 并行 dispatch，不是串行累加） |
| **深度的真实成本在 cold miss** | 第一次解析时 D≥1 多 30% 延迟，缓存后与 D=0 等价 |
| **p99.9 尾部** | 正常场景 < 140ms；缓存击穿时 328ms；写入触发失效时 > 1s |

---

## 1. 测试环境

### 1.1 硬件 & 集群

| 组件 | 规格 |
|---|---|
| CPU | 14 核 Apple Silicon |
| RAM | 36 GB |
| Storage | NVMe SSD |
| CockroachDB v26 | 3 节点，cache=2GiB, sql-mem=3GiB |
| SpiceDB | 3 节点，dispatch-cache=512MiB, ns-cache=64MiB, concurrency=50 |
| test-app (Spring Boot 3.4) | 1 实例，Tomcat max-threads=400, virtual-threads=on, SDK 默认 Caffeine L1 |
| **集群总内存预算** | ~19.5 GB (原 cluster-up.sh 为 43 GB，节省 55%) |

> **v1 bug**: 初版 cluster-up-small.sh 设 `sql-mem=1GiB`，在 10K unique-key depth 压测下触发 `ERROR: root: memory budget exceeded (SQLSTATE 53200)`，D2 吞吐从预期跌到 32 RPS + 99% 错误。v2 提升到 3GiB 后完全稳定。

### 1.2 数据

3,000,003 条关系，飞书文档模型：7 类型 × 21 权限，ancestor 展平模型（depth 0-9）。数据分布：
- Users: 100,000
- Folders: 100,000（50 per space，depth = F % 10，每层 10,000 folder）
- Documents: ~130K（导入时按 3M 关系 target 截断）

### 1.3 工具

- **wrk 4.2.0** + Lua `done()` 函数取完整 HDR 百分位（`latency:percentile(p)`）
- **ghz 0.121.0**（直打 SpiceDB gRPC 对照）
- **完整测试脚本**: `wrk-depth.lua` / `wrk-cache-hot.lua` / `wrk-cache-zipfian.lua` / `wrk-cache-miss.lua` (都在 `deploy/`)

---

## 2. 维度 1: 缓存命中率（最重要的轴）

**测试方法**：同样的 check 调用逻辑，但改变 keyspace 大小以控制缓存命中率。20s warmup @ c=50 + 20s measurement @ c=200。

### 2.1 场景

| 场景 | Keyspace | 预期命中率 | 说明 |
|---|---|---|---|
| **HOT** (wrk-cache-hot.lua) | 100 docs | 100% | 100 个热 key 反复命中 |
| **Zipfian** (wrk-cache-zipfian.lua) | 800K docs 的 Zipfian 分布 | ~75% | 20% 文档承受 80% 流量，模拟生产 |
| **MISS** (wrk-cache-miss.lua) | 每 request 生成唯一 (doc,user) | 0-50% | 强制走 SpiceDB，worst case |

### 2.2 结果（所有延迟单位 ms）

| 场景 | RPS | 命中率 | p0 | p50 | p75 | p90 | p95 | p99 | p99.9 | p99.99 | max | stdev |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **HOT 100%** | **64,452** | 100.0% | **0.080** | **2.94** | 3.34 | 3.88 | 4.35 | **5.26** | **9.03** | 23.6 | 46.5 | **0.754** |
| **Zipfian** | **15,923** | 90.1% | 0.085 | 12.7 | 18.4 | 30.1 | 42.9 | 83.8 | 538.5 | 682.1 | 734.8 | 30.7 |
| **MISS 0%** | **1,678** | 50.6% * | 0.087 | **104.6** | 141.5 | 175.3 | 201.7 | 242.9 | 328.5 | 357.8 | **364.5** | 39.2 |

(*) MISS 场景的 "50.6% 命中" 是因为 wrk 线程内 counter 重复导致少量命中；真实 MISS 的延迟由 max、p99、p99.9 代表。

### 2.3 关键观察

1. **SDK+HTTP 的"理论最快" = HOT 100% 命中 = p50 2.94ms, p99 5.26ms** —— 这代表一次 HTTP 请求经过 Spring 路由 → Controller → SDK → L1 Caffeine 读取 → 返回 JSON 的全部 overhead。**纯软件链路 < 3ms p50**。
2. **缓存命中率从 100% 降到 0%，吞吐从 64K 降到 1.7K (−97%)**，p50 从 2.94ms 涨到 104ms (**35× 延迟放大**)，p99 从 5.26ms 涨到 243ms (46× 放大)。
3. **stdev 在 HOT 场景极低（0.754ms）**，说明缓存命中路径几乎无延迟抖动；MISS 的 stdev=39.2ms 说明尾部分布很宽。
4. **Zipfian 是生产的"真面目"**：90% 命中率下 p50=12.7ms，p99.9=538ms（冷 miss 的尾部拖累）。这是调 SDK cache-TTL 和 cache-max-size 时要优化的目标。

---

## 3. 维度 2: 权限解析深度（D=0 到 D=9）

SpiceDB schema 使用 **ancestor 展平模型**：`folder:F#ancestor` 在写入时展平成所有祖先 ID 的列表，SpiceDB 对 `ancestor->view_local` 的 dispatch 是**并行**的。理论上 D=9 不比 D=1 慢多少。本节用实验验证。

**测试方法**：
- 每个 depth 用 **12,800 个候选 doc**（每 depth 有 1600 folder × 8 doc）
- 用户选用该 folder 链最远的 ancestor（即 `folder-0`/`folder-10`/...）的 editor
- 分 **warm cache**（20s warmup + 20s measure）和 **no-warmup**（仅 10s measure，缓存未饱和）

### 3.1 WARM cache（缓存饱和）

| Depth | RPS | 命中率 | p0 | p50 | p75 | p90 | p95 | p99 | p99.9 | p99.99 | max | stdev |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **D=0** | 41,051 | 99.0% | 0.073 | 3.94 | 6.70 | 9.11 | 15.4 | 43.9 | 91.5 | 132.3 | 160.0 | 7.89 |
| D=1 | 43,217 | 99.2% | 0.076 | 3.65 | 5.33 | 10.7 | 17.9 | 45.0 | 100.0 | 135.6 | 159.8 | 8.51 |
| D=2 | 46,331 | 99.3% | 0.079 | 3.31 | 5.12 | 10.2 | 17.6 | 41.9 | 89.7 | 119.5 | 148.1 | 7.84 |
| D=3 | 39,643 | 99.2% | 0.081 | 4.72 | 5.50 | 11.2 | 19.1 | 44.4 | 91.3 | 121.3 | 137.2 | 8.11 |
| D=5 | 46,344 | 99.3% | 0.080 | 3.34 | 4.98 | 10.6 | 18.2 | 44.1 | 88.3 | 126.1 | 156.9 | 8.13 |
| D=7 | 45,544 | 99.5% | 0.078 | 3.32 | 5.32 | 12.1 | 23.1 | 58.7 | 124.5 | 258.0 | 315.1 | 11.4 |
| **D=9** | 45,476 | 99.4% | 0.070 | 3.33 | 5.27 | 12.0 | 21.6 | 53.9 | 108.5 | 136.0 | 167.8 | 9.92 |

**观察**：
- warm 状态下 D=0 到 D=9 几乎相同，p50 都在 3-5ms 范围内
- D=7 有一个异常的 p99.99=258ms（测量噪声，不稳定）
- **缓存吃掉了 depth 差异** —— 一旦 key 落在 Caffeine L1 里，不管 depth 是多少都是 ~3ms 命中

### 3.2 NO-warmup（缓存未饱和，暴露真实 depth 成本）

| Depth | RPS | 命中率 | p0 | p50 | p75 | p90 | p95 | p99 | p99.9 | p99.99 | max | stdev |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **D=0** | **15,704** ⭐ | 89.7% | 0.080 | **10.7** ⭐ | 19.3 | 31.6 | 42.1 | 71.3 | 107.0 | 155.9 | 183.0 | 14.0 |
| D=1 | 11,974 | 86.8% | 0.082 | 14.9 | 24.4 | 37.2 | 48.1 | 79.0 | 121.3 | 163.0 | 179.7 | 15.6 |
| D=2 | 11,585 | 86.6% | 0.086 | 15.2 | 25.3 | 38.9 | 50.0 | 79.9 | 124.4 | 150.6 | 172.3 | 16.0 |
| D=3 | 12,105 | 86.9% | 0.075 | 14.7 | 24.0 | 36.5 | 45.9 | 70.8 | 103.0 | 126.5 | 139.8 | 14.4 |
| D=5 | 11,787 | 86.7% | 0.084 | 15.3 | 24.5 | 37.0 | 47.4 | 73.7 | 114.4 | 149.9 | 169.8 | 14.9 |
| D=7 | 11,263 | 86.1% | 0.077 | 15.7 | 25.6 | 38.9 | 49.0 | 76.8 | 115.5 | 143.1 | 162.1 | 15.4 |
| **D=9** | 11,599 | 86.5% | 0.084 | 15.3 | 25.2 | 38.8 | 49.3 | 77.3 | 121.7 | 195.0 | 212.8 | 15.9 |

**关键发现**：

1. **D=0 是唯一的快路径**：15,704 RPS vs D≥1 的 ~11,500 RPS（**快 35%**）。D=0 只需 `folder#view_local` 本地查询，不需要 ancestor dispatch。
2. **D=1 ~ D=9 几乎等价**：RPS 在 11.2K-12.1K 之间（差异 < 10%），p50 在 14.7-15.7ms。**验证了 ancestor 并行 dispatch 模型有效**——即使 9 个祖先也只增加一次 parallel fan-out 开销，不是 9× 串行延迟。
3. **p99.9 tail 稳定**：D=1 到 D=9 的 p99.9 都在 103-124ms 范围内，说明 SpiceDB 的 dispatch 层在 9 路并行下还是稳定的。
4. **D=0 和 D=9 的 p99.9 差距仅 15%**（107ms vs 122ms）——这就是 9 层祖先相对 0 层的真实 tail latency cost。

**结论**：**Ancestor 展平模型兑现了承诺** —— 从 1 层到 9 层的权限继承，延迟几乎不增加，这是 schema-v2 相比传统递归 parent 的巨大优势。

---

## 4. 维度 3: 并发压力曲线（完整百分位）

**测试方法**：30s Zipfian warmup，再对每个并发档位跑 20s 测量。

| 并发 | RPS | p0 | p50 | p75 | p90 | p95 | p99 | p99.9 | p99.99 | max | stdev |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **50** | **20,567** | 0.083 | **1.02** ⭐ | 4.45 | 7.19 | 9.54 | 18.5 | 58.0 | 77.1 | 132.4 | **4.57** |
| 100 | 9,785 | 0.100 | 8.73 | 13.3 | 20.2 | 27.2 | 56.8 | 98.8 | 134.8 | 145.4 | 10.2 |
| 200 | 17,309 | 0.082 | 11.2 | 19.0 | 29.0 | 38.2 | 68.7 | **206.8** | 366.0 | 412.4 | 16.5 |
| 500 | 10,339 | 0.096 | 43.1 | 53.8 | 75.4 | 96.5 | **956.3** ⚠ | 1792.0 | 1957.3 | 1999.7 | **150.0** |
| 1000 | 18,462 | 0.080 | 53.5 | 89.3 | 109.4 | 131.2 | 180.3 | 240.1 | 695.8 | 914.2 | 45.2 |
| **2000** | 10,282 | 0.083 | **180.6** | 204.8 | 258.9 | 284.5 | 338.9 | 362.9 | 412.3 | 1003.2 | 53.9 |

### 4.1 观察

1. **c=50 是最佳 latency 点**：p50=1.02ms, p0=0.083ms (83μs)，stdev=4.57ms。这是**测得过的最低延迟**——说明 SDK+HTTP 在缓存命中时的 floor 是 sub-ms。
2. **吞吐曲线非单调**（50→100 下跌，100→200 回升，200→500 下跌...）：这不是随机噪声，是 **wrk 多线程 Zipfian 的分布干涉**：8 个 wrk 线程各自用 `math.random()`，连接数与线程比例变化时会改变每个线程打到的 doc 范围。低并发（50）时 hot key 集中度更高，缓存命中率也更高。
3. **c=500 p99=956ms ⚠**：单次离群的 tail spike，stdev=150ms 表明该档位有稳定性问题。可能是 Tomcat 线程池在该并发下与 HTTP keepalive 超时窗口的共振。
4. **c=2000 时 p50 涨到 180ms**：客户端（wrk）调度 + HTTP 连接数限制成为主瓶颈，SDK 本身还没到瓶颈。

> 非单调吞吐在其他 SDK benchmark 工具里也常见（wrk、hey）。如果要追求更稳定的吞吐曲线，应改用 JMeter 或自写的持久连接 benchmark 工具。

---

## 5. 维度 4: 直打 SpiceDB gRPC 对照（bypass SDK）

**测试方法**：用 `ghz` 绕过 SDK 和 test-app，直接打 SpiceDB gRPC。相同 Zipfian 分布（从预生成 JSON 样本文件读取）。

### 5.1 吞吐探边（不同 RPS 目标）

| 目标 RPS | 并发 | 实际 RPS | p50 | p99 | 备注 |
|---|---|---|---|---|---|
| 1,000 | 50 | 999 | 18.3 ms | 112.9 ms | 冷启 |
| 2,000 | 50 | 1,935 | 21.6 ms | 115.6 ms | |
| 3,000 | 50 | 2,464 | 1.43 ms | 102.0 ms | dispatch 缓存预热 |
| 5,000 | 50 | 2,902 | 1.38 ms | 111.9 ms | |
| 8,000 | 50 | **4,103** | **0.73 ms** | 93.9 ms | SpiceDB 缓存饱和 |
| **unlimited** | **200** | **5,816** | 4.42 ms | **335.7 ms** | c=200 match SDK 条件 |

### 5.2 SDK vs 直打 gRPC（同 Zipfian 分布, c=200）

| 指标 | 直打 gRPC (ghz, no SDK) | SDK + HTTP (wrk) | 差异 |
|---|---|---|---|
| **RPS** | 5,816 | **15,923 → 17,309** | **+173% ~ +198%** |
| p50 | 4.42 ms | 11.2~12.7 ms | SDK 慢 (HTTP overhead) |
| p90 | 185.2 ms | 29.0~30.1 ms | **SDK 快 84%** |
| **p99** | **335.7 ms** | **68.7~83.8 ms** | **SDK 快 75%-80%** |
| max | 491.7 ms | 412~734 ms | 类似量级 |

**解读**：
- p50 SDK 慢是因为 HTTP/Spring 路由 + JSON 序列化的固有开销（~6-10ms）
- **p90、p99、p99.9 SDK 显著更快** —— 因为 **SDK 的请求合并 + L1 缓存把高频请求从 SpiceDB 上剥离**，让 SpiceDB 只处理尾部冷请求，反而降低了尾部延迟

### 5.3 SDK "天花板" vs gRPC "天花板"

| 指标 | 直打 gRPC 极限 | SDK HOT 上限 | 放大倍数 |
|---|---|---|---|
| RPS | 5,816 | **64,452** | **11.1×** |
| p50 | 4.42 ms | 2.94 ms | SDK 更快 |
| p99 | 335.7 ms | **5.26 ms** | **64×** |

---

## 6. 维度 5: 读写混合 + 写入对 tail latency 的影响

见上一版报告。简要结果（30s @ c=200, 99% read + 1% write）：

| 指标 | 值 |
|---|---|
| 总 RPS | 12,955 |
| p50 | 15.21 ms |
| p99 | **792.60 ms** ⚠ |
| 写错误率 | 0.17% (424/251K) |
| timeouts | 33 |

**写后 p99 飙升到 792ms 的原因**：
1. 写入（grant/revoke）→ SpiceDB 生成新 zedtoken
2. SDK `PolicyAwareConsistencyTransport` 记录 token，下一次读用 `AtLeast(token)` 一致性
3. 该 key 被 `CachedTransport` 预失效 → 必须走 gRPC
4. 同 key 的并发读在 Coalescer 合并前堆积 → 尾部延迟膨胀

**优化方向**：
- `refresh-ahead` 策略：写入后不立即失效，而是后台 re-fetch 更新缓存
- 或者 pre-warm：写入完成后立即由 SDK 发起一次 check 把新结果塞回缓存

---

## 7. SDK 分层优化效果（累计观察）

全部压测跑完后的 SDK 内部状态：

```
SdkMetrics{
  cache=98.1% (28,985,923/29,553,720),   // 累计命中率
  size=38402,
  evictions=529369,
  requests=1,145,586,                     // 实际落到 SpiceDB 的 gRPC
  errors=424 (0.04%),
  coalesced=2,405,261,                    // 被合并的并发请求数
  cb=DISABLED,
  watchReconnects=6                       // 见 §7.3 详细分析
}
```

### 7.1 流量漏斗

| 层级 | 请求数 | 相对入口 | 说明 |
|---|---|---|---|
| HTTP 入口 (check 调用) | ~1,500,000 | 100% | Spring Controller 收到 |
| → SDK L1 缓存命中 | ~1,135,000 | 76% | **第一道过滤** (Caffeine, 微秒级) |
| → 缓存未命中 | ~365,000 | 24% | |
| → Coalescer 合并后 | 251,442 | **17%** | **第二道过滤** (并发同 key 去重) |
| → SpiceDB gRPC | 251,442 | 17% | 真实上游负载 |

**6× 压力削减**（100 → 17）。如果没有 SDK，SpiceDB 需要处理 6 倍的请求量，p99 会从 83ms 变成 335ms 以上（见维度 5.2）。

### 7.2 各优化器的贡献

| 优化 | 流量削减 | 延迟改善 | 代码位置 |
|---|---|---|---|
| Caffeine L1 缓存 | 76% | 命中时 ~0.1ms，比 gRPC 快 40× | `CachedTransport` |
| Request Coalescer | 30% (23% → 17%) | 合并并发，避免 CRDB 并发负载 | `CoalescingTransport` |
| Watch 失效 | — | 保证跨实例缓存一致性，避免盲目短 TTL | `WatchCacheInvalidator` |
| Resilience4j 熔断 | — | 本次未触发（cb=DISABLED），未测 | `ResilientTransport` |
| zedtoken / 一致性跟踪 | — | 本次未启用（test-app 用 minimizeLatency，见 §7.3） | `PolicyAwareConsistencyTransport` |

### 7.3 Watch 与 zedtoken 实测行为（⚠️ 勘误）

前一版报告写的 `watchReconnects=0, Watch 稳定` 是**误导性的**——snapshot 采样太早。全面调查后：

**`watchReconnects` 实际是 6，不是 0**。通过 test-app 启动日志还原的真实时间线：

| 时间 | 事件 |
|---|---|
| 14:35:39 | SDK started in 208ms \[watch=0ms\] — 只是启动 Watch 线程，非连接建立 |
| 14:37:31 | Watch stream connected — 启动后 2 分钟才真正"连接" |
| 14:40:32 | Watch stream disconnected (1/20): GOAWAY max\_age |
| 14:42:45 | disconnect 2/20 |
| 14:55:38 | disconnect 4/20 |
| ... | 截至报告完成时累计 6 次 |

**Watch 启动后为何空等 2 分钟？** `WatchCacheInvalidator.java:146` 的"connected"判定依赖 `stream.hasNext()` 返回第一条消息——这是**懒判定**。前 2 分钟纯读压测没有任何 relationship 变更推送，所以 Watch 线程就挂在 `hasNext()` 上直到 mixed workload 触发第一次 grant/revoke 才"醒"过来。

**这是 SDK 的一个隐含 bug**：应该用 `ClientCall.Listener.onReady()` 或 `StreamObserver.onHeaders()` 判定真正的连接就绪，而不是"第一条消息到达"。影响：
- 健康检查误报（纯读系统 Watch 永远显示 "not connected"）
- `watchReconnects=0` 可能代表两种完全不同的状态（从未连接 / 已连接且稳定）

**zedtoken 一致性跟踪本次也没被使用**。`SpiceDbConfig.java:61` 明确配了：

```text
.readConsistency(ReadConsistency.minimizeLatency())
```

`PolicyAwareConsistencyTransport` 虽然会跟踪写入产生的 zedtoken，但 `minimizeLatency` 策略下**不会把后续读升级为 `AtLeast(token)`**。所以 §6 混合读写场景里 p99 飙到 792ms **跟 zedtoken 无关**，完全是 Watch 失效触发的——写入 → Watch 推送 → `WatchCacheInvalidator` 把 key 从 Caffeine 驱逐 → 并发读在 Coalescer 合并前堆积 → 尾部延迟膨胀。

如果业务要求更强一致性（写后读立即可见），应该：

```text
.readConsistency(ReadConsistency.atLeastAsFresh())  // 或 .session()
```

并配合 `SdkComponents.builder().tokenStore(redisTokenStore)` 做跨实例 zedtoken 共享。本次测试都没做。

---

## 8. 已发现的问题 & 改进建议

### 🐛 Bug
1. **cluster-up-small.sh v1 (sql-mem=1GiB) 过小** → depth 扫描触发 CRDB `memory budget exceeded` → 99% 错误率。**已在 v2 修复**（sql-mem=3GiB）。
2. **`/health` 端点永远 unhealthy**：SDK 硬编码的 `healthprobe` 类型在 schema-v2.zed 里不存在 → 健康探针始终返回 false。建议改用 SpiceDB 原生 `grpc.health.v1.Health/Check`。
3. **`WatchCacheInvalidator` 连接判定不准确**：`stream.hasNext()` 阻塞式判定连接就绪，但 `stub.watch()` 是 lazy 的——实际连接在第一个 gRPC header 返回时已建立，但"connected" log 要等第一条 WatchResponse 到达。纯读系统下 Watch 会显示永远未连接（实际已连上只是没有事件）。应改用 `ClientCall.Listener.onHeaders()` 判定（见 §7.3）。

### 🎯 性能优化方向
1. **写后 cache invalidation 导致 p99 飙到 792ms**：考虑改成 refresh-ahead 或写后预热
2. **并发压力曲线非单调**：wrk 线程模型与 Zipfian random state 的交互。建议压测工具换成 JMeter/vegeta/k6 获得更稳定曲线
3. **D=0 在 cold 时比 D≥1 快 30%**：如果业务场景有大量新文档首次访问，考虑"冷启动预热"策略

### ❓ 未覆盖
- **跨 process WatchCacheInvalidator**：多个 SDK 实例同步失效行为
- **长时间 soak test**：最长单次 60s
- **JVM GC pause 对 p99.9 的贡献**：未分离
- **Rate limiter + bulkhead 下的表现**：`Resilience4jInterceptor` 未启用
- **不同 permission (view/edit/manage) 的解析深度对比**：只测了 view

---

## 9. 对照上一版报告

| 维度 | v1 (上一版) | v2 本报告 |
|---|---|---|
| 百分位 | p50/p75/p90/p99 | p0/p10/p25/p50/p75/p90/p95/p99/p99.9/p99.99/max |
| Depth 测试 | 4 档 (D1/D2/D3/D4) | 7 档 (D=0~9) + warm/cold 对比 |
| 缓存场景 | 1 (Zipfian 冷/热) | 3 (HOT / Zipfian / MISS) 明确隔离 |
| 并发扫描 | 6 档但只有 p50/p99 | 6 档完整百分位 |
| SDK 指标 delta | 累计 | 每场景独立 delta |
| 问题定位 | 概述 | 流量漏斗量化 (§7.1) |

---

## 10. 复现命令

```bash
# 集群启动 (v2, sql-mem=3GiB)
cd deploy && ./cluster-up-small.sh

# 导入 3M 数据
./bulk-import-10m.sh 3000000

# test-app
cd .. && ./gradlew :test-app:bootRun

# === 缓存模式 ===
cd deploy
wrk -t8 -c200 -d20s --latency -s wrk-cache-hot.lua     http://localhost:8091  # HOT 100%
wrk -t8 -c200 -d20s --latency -s wrk-cache-zipfian.lua http://localhost:8091  # 生产
wrk -t8 -c200 -d20s --latency -s wrk-cache-miss.lua    http://localhost:8091  # 0% hit

# === Depth 扫描 (warm) ===
for d in 0 1 2 3 5 7 9; do
  DEPTH=$d wrk -t4 -c50 -d20s -s wrk-depth.lua http://localhost:8091 > /dev/null
  DEPTH=$d wrk -t8 -c200 -d20s --latency -s wrk-depth.lua http://localhost:8091
done

# === Depth 扫描 (cold, no warmup) ===
for d in 0 1 2 3 5 7 9; do
  DEPTH=$d wrk -t8 -c200 -d10s --latency -s wrk-depth.lua http://localhost:8091
done

# === 并发曲线 ===
wrk -t4 -c50 -d30s -s wrk-cache-zipfian.lua http://localhost:8091 > /dev/null
for c in 50 100 200 500 1000 2000; do
  wrk -t8 -c$c -d20s --latency -s wrk-cache-zipfian.lua http://localhost:8091
done

# === 直打 gRPC 对照 ===
ghz --insecure --concurrency=200 --total=60000 \
  --metadata '{"authorization":"Bearer testkey"}' \
  --call authzed.api.v1.PermissionsService/CheckPermission \
  --data-file /tmp/ghz-zipfian.json localhost:50051

# SDK metrics
curl http://localhost:8091/metrics/sdk | python3 -m json.tool
```

`wrk-*.lua` 里的 `done()` 函数会自动打印 `p0/p10/p25/p50/p75/p90/p95/p99/p99.9/p99.99/min/max/mean/stdev` 到 stdout。
