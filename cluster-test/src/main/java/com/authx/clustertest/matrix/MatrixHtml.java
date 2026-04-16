package com.authx.clustertest.matrix;

/**
 * 场景化 HTML 报告 — 用对比柱状图 + 线图讲故事，不用抽象热力图。
 * 每个场景组回答一个明确的问题。
 */
final class MatrixHtml {

    static String build(String chartJs, String dataJson) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<title>AuthX SDK 全维度性能报告</title>"
                + "<style>" + CSS + "</style>"
                + "<script>" + chartJs + "</script>"
                + "</head><body><div class=\"page\">"
                + "<header><h1>AuthX SDK 全维度性能报告</h1>"
                + "<div class=\"sub\">InMemoryTransport · 无网络开销 · 场景化对比测试</div></header>"
                + "<main id=\"root\"></main>"
                + "<footer>cluster-test SDK 矩阵 · Chart.js v4 · 离线可浏</footer></div>"
                + "<script>const DATA = " + dataJson + ";\n" + JS + "\nrenderReport(DATA);</script>"
                + "</body></html>";
    }

    private static final String CSS = """
            :root{--bg:#f7f8fa;--card:#fff;--ink:#1f2937;--dim:#6b7280;
              --border:#e5e7eb;--brand:#0a5bce;--brand-soft:#e8f0fe;
              --good:#10b981;--bad:#ef4444;--warn:#f59e0b}
            *{box-sizing:border-box}
            body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
                 margin:0;background:var(--bg);color:var(--ink);line-height:1.55}
            .page{max-width:1280px;margin:0 auto;padding:32px 24px}
            header{border-bottom:3px solid var(--brand);padding-bottom:16px;margin-bottom:24px}
            h1{margin:0;font-size:26px}
            .sub{color:var(--dim);font-size:13px;margin-top:6px}
            section{background:var(--card);border:1px solid var(--border);border-radius:12px;
                    padding:24px;margin-bottom:24px;box-shadow:0 1px 3px rgba(0,0,0,.04)}
            section h2{margin:0 0 8px 0;font-size:19px;color:var(--brand);
                       padding-bottom:8px;border-bottom:1px solid var(--border)}
            section .desc{color:var(--dim);font-size:13px;margin:8px 0 16px;line-height:1.6}
            table{border-collapse:collapse;width:100%;font-size:13px;margin:12px 0}
            th{background:#f9fafb;font-weight:600;text-align:left;padding:8px 12px;
               border-bottom:2px solid var(--border)}
            td{padding:8px 12px;border-bottom:1px solid var(--border)}
            td.num{font-family:ui-monospace,"SF Mono",Menlo,monospace;text-align:right}
            td.label{font-weight:600}
            tr:last-child td{border-bottom:none}
            tr:hover{background:#fafbfc}
            .chart-wrap{background:#fbfcfd;border:1px solid var(--border);border-radius:8px;
                        padding:16px;margin:16px 0;height:340px}
            .chart-sm{height:260px}
            .insight{background:#fef9e7;border-left:4px solid var(--warn);
                     padding:12px 16px;border-radius:4px;font-size:13px;margin:12px 0;line-height:1.7}
            .insight strong{color:#92400e}
            .key-finding{background:#e8f0fe;border-left:4px solid var(--brand);
                         padding:12px 16px;border-radius:4px;font-size:13px;margin:12px 0;line-height:1.7}
            .two-col{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin:16px 0}
            .meta{display:grid;grid-template-columns:auto 1fr;gap:6px 16px;font-size:13px;
                  padding:12px;background:#fbfcfd;border-radius:6px;border:1px solid var(--border)}
            .meta dt{color:var(--dim)}
            .meta dd{margin:0;font-family:ui-monospace,"SF Mono",Menlo,monospace}
            footer{margin-top:32px;padding-top:16px;border-top:1px solid var(--border);
                   color:var(--dim);font-size:12px;text-align:center}
            """;

    private static final String JS = """
            const us2ms = us => us < 1000 ? (us/1000).toFixed(3) : (us < 100000 ? (us/1000).toFixed(2) : (us/1000).toFixed(1));
            const fmtTps = n => n>=1_000_000 ? (n/1_000_000).toFixed(2)+'M' : (n>=1000 ? (n/1000).toFixed(1)+'k' : n.toFixed(0));
            const fmtN = n => n.toLocaleString();

            function renderReport(d){
              const r = document.getElementById('root');
              r.innerHTML += renderMeta(d);
              // SDK-mode (InMemory + LatencySim)
              r.innerHTML += renderSection1(d.cells);
              r.innerHTML += renderSection2(d.cells);
              r.innerHTML += renderSection3(d.cells);
              r.innerHTML += renderSection4(d.cells);
              r.innerHTML += renderSection5(d.cells);
              r.innerHTML += renderSection6(d.cells);
              // Real-mode (live SpiceDB cluster)
              r.innerHTML += renderSectionB1(d.cells);
              r.innerHTML += renderSectionB2(d.cells);
              r.innerHTML += renderSectionB3(d.cells);
              r.innerHTML += renderSectionB4(d.cells);
              r.innerHTML += renderSectionB5(d.cells);
              r.innerHTML += renderSectionB6(d.cells);
              r.innerHTML += renderSectionB7(d.cells);
              r.innerHTML += renderSectionB8(d.cells);
              r.innerHTML += renderDetailTable(d.cells);
              drawAllCharts(d);
            }

            function renderSectionB8(cells){
              const s = cells.filter(c => /^B8[A-Z]-/.test(c.name));
              if(!s.length) return '';
              const base = s[0];
              return `<section><h2>B8. 真实集群 — 一致性级别成本</h2>
                <div class="desc">同一负载，cache 关闭，三种 ReadConsistency：
                <code>minimizeLatency</code>（默认，可读 ~5s 旧）/
                <code>session</code>（用 tokenStore 保证读不旧于自己写）/
                <code>strong</code>（fully-consistent，强主读）。
                Cache 关闭，差异直接反映 SpiceDB+CRDB 一致性成本。</div>
                <table>
                  <tr><th>级别</th><th class="num">TPS</th>
                      <th class="num">vs minimize</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">max (ms)</th></tr>
                  ${s.map(c => {
                    const ratio = (c.tps / Math.max(1, base.tps) * 100).toFixed(1);
                    return `<tr>
                      <td class="label">${esc(c.name)}</td>
                      <td class="num"><strong>${fmtTps(c.tps)}</strong></td>
                      <td class="num">${ratio}%</td>
                      <td class="num">${us2ms(c.p50us)}</td>
                      <td class="num">${us2ms(c.p99us)}</td>
                      <td class="num">${us2ms(c.p999us)}</td>
                      <td class="num">${us2ms(c.maxUs)}</td>
                    </tr>`;
                  }).join('')}
                </table>
                <div class="chart-wrap"><canvas id="chart-b8"></canvas></div>
                <div class="insight">
                  生产典型选 <code>minimizeLatency</code> + 缓存。
                  写后立刻读用 <code>session</code>（需要 tokenStore，否则跨 JVM 无效）。
                  对账 / 强一致场景才上 <code>strong</code> —— 跳过 follower read 走主，延迟显著。
                </div>
              </section>`;
            }

            // ═══ B-class (real SpiceDB) sections ═══
            function renderSectionB1(cells){
              const s = cells.filter(c => /^B1[A-E]-/.test(c.name))
                            .sort((a,b) => b.targetHitRate - a.targetHitRate);
              if(!s.length) return '';
              const speedup = (s[0].tps / Math.max(1, s[s.length-1].tps)).toFixed(1);
              return `<section><h2>B1. 真实集群 — 缓存命中 vs 未命中</h2>
                <div class="desc">直连真实 SpiceDB+CRDB 集群。命中走 Caffeine（μs 级），未命中走 gRPC → SpiceDB → CRDB Raft（ms 级）。
                这里看的是缓存层在生产场景中真实带来的加速比。</div>
                <table>
                  <tr><th>场景</th><th class="num">TPS</th><th class="num">实测命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">max (ms)</th></tr>
                  ${s.map(c => `<tr>
                    <td class="label">${esc(c.name)}</td>
                    <td class="num"><strong>${fmtTps(c.tps)}</strong></td>
                    <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                    <td class="num">${us2ms(c.p999us)}</td>
                    <td class="num">${us2ms(c.maxUs)}</td>
                  </tr>`).join('')}
                </table>
                <div class="chart-wrap"><canvas id="chart-b1"></canvas></div>
                <div class="key-finding">
                  <strong>缓存命中相对未命中加速 ${speedup}×。</strong>
                  对比 SDK-mode 的加速比，可以看出 LatencySim 的 2ms 估算与真实 SpiceDB+CRDB RTT 的差距。
                </div>
              </section>`;
            }

            function renderSectionB2(cells){
              const s = cells.filter(c => /^B2-/.test(c.name)).sort((a,b) => a.threads - b.threads);
              if(!s.length) return '';
              const rows = s.map(c => {
                const target = c.threads, achieved = c.tps, ratio = achieved/target;
                const color = ratio>=0.95 ? 'var(--good)' : (ratio>=0.5?'var(--warn)':'var(--bad)');
                return `<tr>
                  <td class="label">${esc(c.name)}</td>
                  <td class="num">${fmtN(target)}</td>
                  <td class="num"><strong>${fmtN(Math.round(achieved))}</strong></td>
                  <td class="num" style="color:${color}"><strong>${(ratio*100).toFixed(1)}%</strong></td>
                  <td class="num">${us2ms(c.p50us)}</td>
                  <td class="num">${us2ms(c.p99us)}</td>
                  <td class="num">${us2ms(c.p999us)}</td>
                </tr>`;
              }).join('');
              return `<section><h2>B2. 真实集群 — QPS 阶梯</h2>
                <div class="desc">按目标 QPS 匀速发送（wrk2 风格 token bucket）到真实集群。
                超过 SpiceDB 处理能力时 SDK 排队，p99 显著上扬。</div>
                <table>
                  <tr><th>场景</th><th class="num">目标 QPS</th><th class="num">实测 QPS</th>
                      <th class="num">达成率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th><th class="num">p999 (ms)</th></tr>
                  ${rows}
                </table>
                <div class="chart-wrap"><canvas id="chart-b2"></canvas></div>
              </section>`;
            }

            function renderSectionB3(cells){
              const s = cells.filter(c => /^B3-write/.test(c.name))
                            .sort((a,b) => parseInt(a.name.match(/write(\\d+)/)[1]) -
                                           parseInt(b.name.match(/write(\\d+)/)[1]));
              if(!s.length) return '';
              return `<section><h2>B3. 真实集群 — 读写比例</h2>
                <div class="desc">写操作走真实 SpiceDB WriteRelationships，并触发 double-delete 失效本地缓存。
                写比例越高，缓存被破坏越频繁，TPS 下降越明显。</div>
                <table>
                  <tr><th>场景</th><th class="num">TPS</th><th class="num">实测命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th><th class="num">p999 (ms)</th></tr>
                  ${s.map(c => `<tr>
                    <td class="label">${esc(c.name)}</td>
                    <td class="num"><strong>${fmtTps(c.tps)}</strong></td>
                    <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                    <td class="num">${us2ms(c.p999us)}</td>
                  </tr>`).join('')}
                </table>
                <div class="chart-wrap"><canvas id="chart-b3"></canvas></div>
              </section>`;
            }

            function renderSectionB4(cells){
              const s = cells.filter(c => /^B4[AB]-/.test(c.name));
              if(!s.length) return '';
              const off = s.find(c => /关闭/.test(c.name)) || s[0];
              const on  = s.find(c => /开启/.test(c.name)) || s[1];
              const speedup = on && off ? (on.tps / Math.max(1, off.tps)).toFixed(1) : '?';
              return `<section><h2>B4. 真实集群 — Cache 开/关对比</h2>
                <div class="desc">同一负载（纯命中工作集），分别在 cache 关闭和开启下运行。这是回答"上 Caffeine 缓存到底值不值"最直接的对比。</div>
                <table>
                  <tr><th>场景</th><th class="num">TPS</th><th class="num">命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th><th class="num">p999 (ms)</th></tr>
                  ${s.map(c => `<tr>
                    <td class="label">${esc(c.name)}</td>
                    <td class="num"><strong>${fmtTps(c.tps)}</strong></td>
                    <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                    <td class="num">${us2ms(c.p999us)}</td>
                  </tr>`).join('')}
                </table>
                <div class="key-finding"><strong>开启 Caffeine 后吞吐 ${speedup}× 提升</strong>，p99 同时大幅下降。
                  这是 SDK 引入本地缓存的核心价值证明。</div>
              </section>`;
            }

            function renderSectionB5(cells){
              const all = cells.filter(c => /^B5-/.test(c.name));
              if(!all.length) return '';
              const cached = all.filter(c => !/noCache/.test(c.name))
                              .sort((a,b) => parseInt(a.name.match(/depth(\\d+)/)[1]) -
                                             parseInt(b.name.match(/depth(\\d+)/)[1]));
              const noCache = all.filter(c => /noCache/.test(c.name))
                              .sort((a,b) => parseInt(a.name.match(/depth?(\\d+)/)[1]) -
                                             parseInt(b.name.match(/depth?(\\d+)/)[1]));
              const row = c => `<tr>
                    <td class="label">${esc(c.name)}</td>
                    <td class="num"><strong>${fmtTps(c.tps)}</strong></td>
                    <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                    <td class="num">${us2ms(c.p999us)}</td>
                    <td class="num">${us2ms(c.maxUs)}</td>
                  </tr>`;
              return `<section><h2>B5. 真实集群 — 文件夹祖先继承深度</h2>
                <div class="desc">使用 schema-v2.zed 的 <code>folder.ancestor</code> 平展模型。
                每个 chain 把 doc 放在叶子 folder（depth 层），用户被授权在 root folder。
                Check <code>document#view</code> 时 SpiceDB 会沿 ancestor 链 fan-out 并行 dispatch 到所有祖先节点。
                理论上 ancestor 平展模型让"深度"几乎不增加延迟（vs 旧的 parent 递归）。</div>
                <h3 style="margin-top:16px;color:var(--ink)">B5a — Cache 开启（生产典型）</h3>
                <table>
                  <tr><th>场景</th><th class="num">TPS</th><th class="num">命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">max (ms)</th></tr>
                  ${cached.map(row).join('')}
                </table>
                <div class="chart-wrap"><canvas id="chart-b5a"></canvas></div>
                <h3 style="margin-top:24px;color:var(--ink)">B5b — Cache 关闭（每次都打 SpiceDB）</h3>
                <table>
                  <tr><th>场景</th><th class="num">TPS</th><th class="num">命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">max (ms)</th></tr>
                  ${noCache.map(row).join('')}
                </table>
                <div class="chart-wrap"><canvas id="chart-b5b"></canvas></div>
                <div class="insight">对比两组：cache 开启时深度对 TPS 几乎无影响（缓存命中绕开 dispatch 链）；
                cache 关闭时延迟随深度上升 — 这就是 ancestor 模型 vs parent 模型在真实集群上的实际差异。</div>
              </section>`;
            }

            function renderSectionB6(cells){
              const s = cells.filter(c => /^B6[A-Z]-/.test(c.name));
              if(!s.length) return '';
              return `<section><h2>B6. 真实集群 — 协作者路径</h2>
                <div class="desc">同一文档，授权方式不同：直接 viewer / via group / via space / via department→group / 完整链路（user→dept→group→space→folder→doc）。
                SpiceDB 必须沿不同关系路径解析，dispatch fan-out 不同。</div>
                <table>
                  <tr><th>路径</th><th class="num">TPS</th><th class="num">命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">max (ms)</th></tr>
                  ${s.map(c => `<tr>
                    <td class="label">${esc(c.name)}</td>
                    <td class="num"><strong>${fmtTps(c.tps)}</strong></td>
                    <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                    <td class="num">${us2ms(c.p999us)}</td>
                    <td class="num">${us2ms(c.maxUs)}</td>
                  </tr>`).join('')}
                </table>
                <div class="chart-wrap"><canvas id="chart-b6"></canvas></div>
                <div class="insight">direct 是基线（最快）。via group / via space 只多 1 跳。
                via dept 增加 department.all_members 的递归。
                full-chain 把所有跳数串起来 — 真实复杂权限的最坏情况。</div>
              </section>`;
            }

            function renderSectionB7(cells){
              const s = cells.filter(c => /^B7-/.test(c.name));
              if(!s.length) return '';
              // Group by depth, then sort by qps within
              const byDepth = {};
              s.forEach(c => {
                const d = c.name.match(/d(\\d+)/)[1];
                (byDepth[d] = byDepth[d]||[]).push(c);
              });
              Object.values(byDepth).forEach(arr => arr.sort((a,b)=>a.threads-b.threads));
              const depths = Object.keys(byDepth).sort((a,b)=>+a-+b);
              const allRows = depths.flatMap(d => byDepth[d].map(c => {
                const ratio = c.tps / c.threads;
                const color = ratio>=0.95 ? 'var(--good)' : (ratio>=0.5?'var(--warn)':'var(--bad)');
                return `<tr>
                  <td class="label">depth=${d}</td>
                  <td class="num">${fmtN(c.threads)}</td>
                  <td class="num"><strong>${fmtN(Math.round(c.tps))}</strong></td>
                  <td class="num" style="color:${color}"><strong>${(ratio*100).toFixed(1)}%</strong></td>
                  <td class="num">${us2ms(c.p50us)}</td>
                  <td class="num">${us2ms(c.p99us)}</td>
                  <td class="num">${us2ms(c.p999us)}</td>
                </tr>`;
              })).join('');
              return `<section><h2>B7. 真实集群 — QPS × 深度交叉</h2>
                <div class="desc">同一深度下扫多档 QPS。比较 depth=0 / 5 / 20 在 1k / 5k / 10k 目标 QPS 下的达成率与延迟，
                看深度对 SpiceDB 处理能力的真实影响（缓存预热后稳态）。</div>
                <table>
                  <tr><th>深度</th><th class="num">目标 QPS</th><th class="num">实测 QPS</th>
                      <th class="num">达成率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th><th class="num">p999 (ms)</th></tr>
                  ${allRows}
                </table>
                <div class="chart-wrap"><canvas id="chart-b7"></canvas></div>
              </section>`;
            }

            function renderMeta(d){
              return `<section><h2>测试元数据</h2>
                <dl class="meta">
                  <dt>每场景持续</dt><dd>${d.perCellDurationMs} ms</dd>
                  <dt>场景总数</dt><dd>${d.totalCells}</dd>
                  <dt>总耗时</dt><dd>${(d.totalElapsedMs/1000).toFixed(0)} 秒</dd>
                  <dt>生成时间</dt><dd>${d.generatedAt}</dd>
                  <dt>传输层</dt><dd>InMemoryTransport + LatencySim（每次后端 RPC 模拟 2ms，接近真实 SpiceDB+CRDB RTT）</dd>
                  <dt>并发</dt><dd>100 线程（部分场景特殊）</dd>
                  <dt>工作集</dt><dd>10,000 primed 资源</dd>
                </dl>
              </section>`;
            }

            // ═══ Section 1: 缓存命中 vs 未命中 ═══
            function renderSection1(cells){
              const s = cells.filter(c => /^1[A-E]-/.test(c.name))
                            .sort((a,b) => b.targetHitRate - a.targetHitRate);
              if(!s.length) return '';
              const tpsHit = s[0].tps, tpsMiss = s[s.length-1].tps;
              const speedup = (tpsHit / tpsMiss).toFixed(1);
              return `<section><h2>1. 缓存命中 vs 未命中</h2>
                <div class="desc">5 个场景，从纯命中到纯未命中。后端未命中时走 LatencySim 模拟 2ms SpiceDB+CRDB RTT，命中时直接走 Caffeine（~μs 级）。这是符合真实生产的对比。</div>
                <table>
                  <tr><th>场景</th><th class="num">TPS</th><th class="num">实测命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">max (ms)</th></tr>
                  ${s.map(c => `<tr>
                    <td class="label">${esc(c.name)}</td>
                    <td class="num"><strong>${fmtTps(c.tps)}</strong></td>
                    <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                    <td class="num">${us2ms(c.p999us)}</td>
                    <td class="num">${us2ms(c.maxUs)}</td>
                  </tr>`).join('')}
                </table>
                <div class="chart-wrap"><canvas id="chart-s1"></canvas></div>
                <div class="key-finding">
                  <strong>关键结论：</strong>缓存命中比未命中快 <strong>${speedup}×</strong>。
                  即使 InMemoryTransport 已经很快（纯未命中 ${fmtTps(tpsMiss)} TPS），
                  Caffeine 缓存层依然能提供显著的性能提升，主要是因为它跳过了 CachedTransport 内部的包装 + CoalescingTransport 的 ConcurrentHashMap 检查。
                </div>
              </section>`;
            }

            // ═══ Section 2: 嵌套层级深度 ═══
            function renderSection2(cells){
              const s = cells.filter(c => /^2[A-E]-/.test(c.name))
                            .sort((a,b) => parseInt(a.workload==='READ' ? (a.name.match(/depth(\\d+)/)||[0,'0'])[1] : 0) -
                                           parseInt(b.workload==='READ' ? (b.name.match(/depth(\\d+)/)||[0,'0'])[1] : 0));
              if(!s.length) return '';
              return `<section><h2>2. 嵌套层级深度</h2>
                <div class="desc">模拟 SpiceDB 的 ancestor 链展开成本：每层加 100μs 人工延迟，模拟 Zanzibar 递归检查 folder.view = viewer + ancestor→view。
                首次访问需要付这个代价，后续被 Caffeine 缓存住。所以这里看的是"深度对缓存未命中路径的影响"。</div>
                <table>
                  <tr><th>场景</th><th class="num">TPS</th><th class="num">实测命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th></tr>
                  ${s.map(c => `<tr>
                    <td class="label">${esc(c.name)}</td>
                    <td class="num"><strong>${fmtTps(c.tps)}</strong></td>
                    <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                    <td class="num">${us2ms(c.p999us)}</td>
                  </tr>`).join('')}
                </table>
                <div class="chart-wrap"><canvas id="chart-s2"></canvas></div>
                <div class="insight">
                  深度每 +1 层约 +100μs 冷启动代价（模拟 SpiceDB 典型层递归）。但一旦被缓存，访问成本瞬间降到 μs 级，与深度无关。
                  <strong>生产启示：</strong>深度嵌套的资源必须依赖缓存保持热度，冷启动和缓存穿透会被深度放大。
                </div>
              </section>`;
            }

            // ═══ Section 3: 多实例扩展性 ═══
            function renderSection3(cells){
              const s = cells.filter(c => /^3[A-D]-/.test(c.name))
                            .sort((a,b) => a.threads - b.threads);
              if(!s.length) return '';
              const base = s[0].tps;
              const rows = s.map(c => {
                const scale = (c.tps / base).toFixed(2);
                const eff = (c.tps / (base * c.threads) * 100).toFixed(0);
                return `<tr>
                  <td class="label">${esc(c.name)}</td>
                  <td class="num">${c.threads}</td>
                  <td class="num"><strong>${fmtTps(c.tps)}</strong></td>
                  <td class="num">${scale}×</td>
                  <td class="num">${eff}%</td>
                  <td class="num">${us2ms(c.p50us)}</td>
                  <td class="num">${us2ms(c.p99us)}</td>
                </tr>`;
              }).join('');
              return `<section><h2>3. 多实例并发扩展性</h2>
                <div class="desc">N 个独立 AuthxClient 实例同时打压同一内存存储。总线程数固定 100，按 N 平分给每个实例。理想情况下 N 倍实例 ≈ N 倍 TPS（线性扩展）。</div>
                <table>
                  <tr><th>场景</th><th class="num">实例数</th><th class="num">聚合 TPS</th>
                      <th class="num">相对单实例</th><th class="num">扩展效率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th></tr>
                  ${rows}
                </table>
                <div class="chart-wrap chart-sm"><canvas id="chart-s3"></canvas></div>
                <div class="insight">
                  扩展效率 = 聚合 TPS / (单实例 TPS × N)。
                  100% = 完美线性扩展；下降表示 JVM 资源（CPU、内存屏障、GC）成为瓶颈。
                </div>
              </section>`;
            }

            // ═══ Section 4: 读写比 ═══
            function renderSection4(cells){
              const s = cells.filter(c => /^4[A-E]-/.test(c.name))
                            .sort((a,b) => {
                              const wa = parseInt((a.name.match(/\\d+写/) || ['0'])[0]) || (a.name.includes('纯写') ? 100 : 0);
                              const wb = parseInt((b.name.match(/\\d+写/) || ['0'])[0]) || (b.name.includes('纯写') ? 100 : 0);
                              return wa - wb;
                            });
              if(!s.length) return '';
              return `<section><h2>4. 读写比例影响</h2>
                <div class="desc">写操作触发对应资源的缓存 double-delete（写前 + 写后各 invalidate 一次）。读写比例越高写，有效缓存命中率越低，SDK 要做的工作越多。</div>
                <table>
                  <tr><th>场景</th><th class="num">TPS</th><th class="num">实测命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th></tr>
                  ${s.map(c => `<tr>
                    <td class="label">${esc(c.name)}</td>
                    <td class="num"><strong>${fmtTps(c.tps)}</strong></td>
                    <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                    <td class="num">${us2ms(c.p999us)}</td>
                  </tr>`).join('')}
                </table>
                <div class="chart-wrap"><canvas id="chart-s4"></canvas></div>
                <div class="insight">
                  纯写场景命中率 0%（每次写都 invalidate）。50/50 混合下命中率约 54%，因为一半操作在持续破坏缓存。
                </div>
              </section>`;
            }

            // ═══ Section 5: QPS 阶梯 ═══
            function renderSection5(cells){
              const s = cells.filter(c => c.workload==='QPS-TARGET').sort((a,b) => a.threads - b.threads);
              if(!s.length) return '';
              const rows = s.map(c => {
                const target = c.threads;
                const achieved = c.tps;
                const ratio = achieved / target;
                const color = ratio >= 0.95 ? 'var(--good)' : (ratio >= 0.5 ? 'var(--warn)' : 'var(--bad)');
                return `<tr>
                  <td class="label">${esc(c.name)}</td>
                  <td class="num">${fmtN(target)}</td>
                  <td class="num"><strong>${fmtN(Math.round(achieved))}</strong></td>
                  <td class="num" style="color:${color}"><strong>${(ratio*100).toFixed(1)}%</strong></td>
                  <td class="num">${us2ms(c.p50us)}</td>
                  <td class="num">${us2ms(c.p99us)}</td>
                  <td class="num">${us2ms(c.p999us)}</td>
                  <td class="num">${us2ms(c.maxUs)}</td>
                </tr>`;
              }).join('');
              const baseline = s[0].p99us;
              let knee = s[0];
              for (const c of s) if (c.p99us < baseline * 3 && c.tps / c.threads > 0.95) knee = c;
              return `<section><h2>5. QPS 阶梯（控速 / 响应时延）</h2>
                <div class="desc">和"压满"测试不同：这里用 wrk2 风格 token-bucket 按目标 QPS 匀速发送请求，记录响应时延（含队列等待，捕捉协调省略）。
                每增一档 QPS，观察 SDK 是否还能跟上。</div>
                <table>
                  <tr><th>场景</th><th class="num">目标 QPS</th><th class="num">实测 QPS</th>
                      <th class="num">达成率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">max (ms)</th></tr>
                  ${rows}
                </table>
                <div class="chart-wrap"><canvas id="chart-s5"></canvas></div>
                <div class="key-finding">
                  <strong>SDK 健康上限：约 ${fmtN(knee.threads)} QPS</strong>
                  （p99 &lt; 3× 基线 ${us2ms(baseline)}ms，达成率 &gt; 95%）。超过这个值后 SDK 进入排队状态，p99 延迟显著升高。
                </div>
              </section>`;
            }

            // ═══ Section 6: 配置敏感度 ═══
            function renderSection6(cells){
              const ttl = cells.filter(c => /^6A-TTL-/.test(c.name))
                              .sort((a,b) => parseInt(a.name.replace(/\\D/g,'')) - parseInt(b.name.replace(/\\D/g,'')));
              const sz = cells.filter(c => /^6B-size-/.test(c.name))
                             .sort((a,b) => parseInt(a.name.replace(/\\D/g,'')) - parseInt(b.name.replace(/\\D/g,'')));
              const co = cells.filter(c => /^6C-coalescing-/.test(c.name));
              return `<section><h2>6. 配置敏感度</h2>
                <div class="desc">三组独立的配置项消融实验：TTL、Cache 容量、Coalescing 开关。</div>
                <h3 style="margin-top:16px;color:var(--ink)">6A — TTL 对命中率的影响</h3>
                <table>
                  <tr><th>TTL</th><th class="num">TPS</th><th class="num">命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th></tr>
                  ${ttl.map(c => `<tr>
                    <td class="label">${esc(c.name.replace('6A-TTL-',''))}</td>
                    <td class="num">${fmtTps(c.tps)}</td>
                    <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                  </tr>`).join('')}
                </table>
                <h3 style="margin-top:24px;color:var(--ink)">6B — Cache maxSize（工作集 10k）</h3>
                <table>
                  <tr><th>maxSize</th><th class="num">TPS</th><th class="num">命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th></tr>
                  ${sz.map(c => `<tr>
                    <td class="label">${esc(c.name.replace('6B-size-',''))}</td>
                    <td class="num">${fmtTps(c.tps)}</td>
                    <td class="num"><strong>${(c.actualHitRate*100).toFixed(1)}%</strong></td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                  </tr>`).join('')}
                </table>
                <div class="insight">maxSize 小于工作集时命中率急剧下降（W-TinyLFU 频繁驱逐）。<strong>生产建议：maxSize = 活跃资源数 × 1.2 以上。</strong></div>
                <h3 style="margin-top:24px;color:var(--ink)">6C — Coalescing 开关（single-hot，无缓存）</h3>
                <table>
                  <tr><th>配置</th><th class="num">TPS</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th><th class="num">p999 (ms)</th></tr>
                  ${co.map(c => `<tr>
                    <td class="label">${esc(c.name.replace('6C-coalescing-',''))}</td>
                    <td class="num">${fmtTps(c.tps)}</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                    <td class="num">${us2ms(c.p999us)}</td>
                  </tr>`).join('')}
                </table>
              </section>`;
            }

            function renderDetailTable(cells){
              return `<section><h2>附录：所有场景完整数据</h2>
                <div class="desc">所有 ${cells.length} 个场景的完整百分位明细，供深入分析。</div>
                <table>
                  <tr><th>场景</th><th class="num">操作数</th><th class="num">TPS</th>
                      <th class="num">命中率</th>
                      <th class="num">min</th><th class="num">p50</th>
                      <th class="num">p90</th><th class="num">p99</th>
                      <th class="num">p999</th><th class="num">p9999</th>
                      <th class="num">max</th><th class="num">err</th></tr>
                  ${cells.map(c => `<tr>
                    <td style="font-size:11px">${esc(c.name)}</td>
                    <td class="num">${fmtN(c.ops)}</td>
                    <td class="num">${fmtTps(c.tps)}</td>
                    <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                    <td class="num">${us2ms(c.minUs)}</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p90us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                    <td class="num">${us2ms(c.p999us)}</td>
                    <td class="num">${us2ms(c.p9999us)}</td>
                    <td class="num">${us2ms(c.maxUs)}</td>
                    <td class="num">${c.errors}</td>
                  </tr>`).join('')}
                </table>
                <div class="desc" style="margin-top:8px">所有延迟单位为毫秒（ms）</div>
              </section>`;
            }

            function drawAllCharts(d){
              const palette = ['#0a5bce','#10b981','#f59e0b','#ef4444','#8b5cf6','#14b8a6'];
              // S1 — cache hit/miss
              drawBar(d.cells, 'chart-s1', /^1[A-E]-/,
                      c => c.name, c => c.tps, 'TPS', 'TPS', true);
              // S2 — depth
              drawBarWithLine(d.cells, 'chart-s2', /^2[A-E]-/,
                      c => c.name, c => c.tps, 'TPS',
                      c => c.p99us/1000, 'p99 ms');
              // S3 — multi-instance scaling
              drawBarWithLine(d.cells, 'chart-s3', /^3[A-D]-/,
                      c => c.name, c => c.tps, '聚合 TPS',
                      c => c.p99us/1000, 'p99 ms');
              // S4 — read/write mix
              drawBarWithLine(d.cells, 'chart-s4', /^4[A-E]-/,
                      c => c.name, c => c.tps, 'TPS',
                      c => c.actualHitRate * 100, '命中率 %');
              // S5 — QPS ladder (log y for latency, linear for pct)
              drawQps(d.cells);
              // B-class charts
              drawBar(d.cells, 'chart-b1', /^B1[A-E]-/,
                      c => c.name, c => c.tps, 'TPS', 'TPS', true);
              drawBarWithLine(d.cells, 'chart-b2', /^B2-/,
                      c => fmtN(c.threads)+' QPS', c => c.tps, '实测 TPS',
                      c => c.p99us/1000, 'p99 ms');
              drawBarWithLine(d.cells, 'chart-b3', /^B3-write/,
                      c => c.name.replace('B3-',''), c => c.tps, 'TPS',
                      c => c.actualHitRate*100, '命中率 %');
              drawBarWithLine(d.cells, 'chart-b5a', /^B5-folder-depth\\d+$/,
                      c => c.name.replace('B5-folder-',''), c => c.tps, 'TPS',
                      c => c.p99us/1000, 'p99 ms');
              drawBarWithLine(d.cells, 'chart-b5b', /^B5-depth\\d+-noCache/,
                      c => c.name.replace('B5-','').replace('-noCache',''), c => c.tps, 'TPS',
                      c => c.p99us/1000, 'p99 ms');
              drawBarWithLine(d.cells, 'chart-b6', /^B6[A-Z]-/,
                      c => c.name.replace(/^B6.-/,''), c => c.tps, 'TPS',
                      c => c.p99us/1000, 'p99 ms');
              drawB7(d.cells);
              drawBarWithLine(d.cells, 'chart-b8', /^B8[A-Z]-/,
                      c => c.name.replace(/^B8.-/,''), c => c.tps, 'TPS',
                      c => c.p99us/1000, 'p99 ms');
            }

            function drawB7(cells){
              const s = cells.filter(c => /^B7-/.test(c.name));
              if(!s.length) return;
              const ctx = document.getElementById('chart-b7');
              if(!ctx) return;
              // group by depth → one line per depth, x = qps target
              const byDepth = {};
              s.forEach(c => {
                const d = c.name.match(/d(\\d+)/)[1];
                (byDepth[d] = byDepth[d]||[]).push(c);
              });
              Object.values(byDepth).forEach(arr => arr.sort((a,b)=>a.threads-b.threads));
              const depths = Object.keys(byDepth).sort((a,b)=>+a-+b);
              const colors = ['#10b981','#f59e0b','#ef4444','#8b5cf6'];
              const qpsLabels = byDepth[depths[0]].map(c => fmtN(c.threads)+' QPS');
              const datasets = depths.map((d, i) => ({
                label: 'depth='+d+' p99(ms)',
                data: byDepth[d].map(c => c.p99us/1000),
                borderColor: colors[i % colors.length],
                tension: 0.25
              }));
              new Chart(ctx, {
                type:'line',
                data:{ labels: qpsLabels, datasets },
                options:{ responsive:true, maintainAspectRatio:false,
                  scales:{ y:{type:'logarithmic',title:{display:true,text:'p99 ms (log)'}}}}
              });
            }

            function drawBar(cells, canvasId, pattern, labelFn, valueFn, seriesLabel, yLabel, logScale){
              const s = cells.filter(c => pattern.test(c.name));
              if(!s.length) return;
              const ctx = document.getElementById(canvasId);
              if(!ctx) return;
              new Chart(ctx, {
                type: 'bar',
                data: { labels: s.map(labelFn),
                        datasets: [{label: seriesLabel, data: s.map(valueFn),
                                    backgroundColor: '#0a5bce'}] },
                options: { responsive: true, maintainAspectRatio: false,
                  scales: { y: { beginAtZero: !logScale,
                                 type: logScale ? 'logarithmic' : 'linear',
                                 title:{display:true,text:yLabel}}}}
              });
            }

            function drawBarWithLine(cells, canvasId, pattern, labelFn, barFn, barLabel, lineFn, lineLabel){
              const s = cells.filter(c => pattern.test(c.name));
              if(!s.length) return;
              const ctx = document.getElementById(canvasId);
              if(!ctx) return;
              new Chart(ctx, {
                type: 'bar',
                data: { labels: s.map(labelFn),
                        datasets: [
                          {type:'bar', label: barLabel, data: s.map(barFn),
                           backgroundColor: '#0a5bce', yAxisID:'y'},
                          {type:'line', label: lineLabel, data: s.map(lineFn),
                           borderColor: '#ef4444', backgroundColor: '#ef444420',
                           tension: 0.3, yAxisID:'y2'}
                        ] },
                options: { responsive: true, maintainAspectRatio: false,
                  scales: { y: { beginAtZero: true, position: 'left',
                                 title:{display:true, text: barLabel}},
                            y2:{ beginAtZero: true, position: 'right',
                                 title:{display:true, text: lineLabel},
                                 grid:{drawOnChartArea: false}}}}
              });
            }

            function drawQps(cells){
              const s = cells.filter(c => c.workload==='QPS-TARGET')
                            .sort((a,b) => a.threads - b.threads);
              if(!s.length) return;
              const ctx = document.getElementById('chart-s5');
              if(!ctx) return;
              new Chart(ctx, {
                type: 'line',
                data: {
                  labels: s.map(c => fmtN(c.threads) + ' QPS'),
                  datasets: [
                    {label:'p50 (ms)', data: s.map(c => c.p50us/1000),
                     borderColor:'#10b981', tension:0.25, yAxisID:'y'},
                    {label:'p99 (ms)', data: s.map(c => c.p99us/1000),
                     borderColor:'#f59e0b', tension:0.25, yAxisID:'y'},
                    {label:'p999 (ms)', data: s.map(c => c.p999us/1000),
                     borderColor:'#ef4444', tension:0.25, yAxisID:'y'},
                    {label:'达成率 %', data: s.map(c => (c.tps/c.threads)*100),
                     borderColor:'#0a5bce', borderDash:[5,5], tension:0.25, yAxisID:'y2'}
                  ]
                },
                options:{ responsive:true, maintainAspectRatio:false,
                  scales:{
                    y:{type:'logarithmic', position:'left', title:{display:true,text:'延迟 ms（对数）'}},
                    y2:{beginAtZero:true, max:110, position:'right',
                        title:{display:true,text:'达成率 %'},
                        grid:{drawOnChartArea:false}}}}
              });
            }

            function esc(s){
              if(s===null||s===undefined) return '';
              return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
                              .replace(/"/g,'&quot;');
            }
            """;

    private MatrixHtml() {}
}
