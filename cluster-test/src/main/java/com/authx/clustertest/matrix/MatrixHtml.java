package com.authx.clustertest.matrix;

/**
 * Self-contained Chinese HTML report generator for matrix benchmarks.
 * Produces a single offline-viewable file with heatmap, percentile chart,
 * coalescing/cache comparison, and full per-cell HdrHistogram tables.
 */
final class MatrixHtml {

    static String build(String chartJs, String dataJson) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<title>AuthX SDK 矩阵压测报告</title>"
                + "<style>" + CSS + "</style>"
                + "<script>" + chartJs + "</script>"
                + "</head><body><div class=\"page\">"
                + "<header><h1>AuthX SDK 矩阵压测报告（A 类 / SDK-only）</h1>"
                + "<div class=\"sub\">InMemoryTransport · 无网络开销 · 测 SDK 自身性能特征</div></header>"
                + "<main id=\"root\"></main>"
                + "<footer>cluster-test 矩阵 · Chart.js v4 · 离线可浏</footer></div>"
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
            section h2{margin:0 0 8px 0;font-size:18px;color:var(--brand);
                       padding-bottom:8px;border-bottom:1px solid var(--border)}
            section .desc{color:var(--dim);font-size:13px;margin:8px 0 16px}
            table{border-collapse:collapse;width:100%;font-size:13px;margin:12px 0}
            th{background:#f9fafb;font-weight:600;text-align:left;padding:8px 12px;
               border-bottom:2px solid var(--border)}
            td{padding:8px 12px;border-bottom:1px solid var(--border);
               font-family:ui-monospace,"SF Mono",Menlo,monospace}
            tr:last-child td{border-bottom:none}
            .num{text-align:right}
            .heatmap{display:grid;grid-template-columns:auto repeat(var(--cols),1fr);gap:2px;
                     font-size:12px;margin:12px 0}
            .heatmap .hdr{background:#f3f4f6;padding:8px 12px;font-weight:600;text-align:center}
            .heatmap .rowlabel{background:#f3f4f6;padding:8px 12px;font-weight:600;text-align:right}
            .heatmap .cell{padding:10px 12px;text-align:center;border-radius:4px;
                           font-family:ui-monospace,"SF Mono",Menlo,monospace}
            .heatmap .cell .v{font-size:14px;font-weight:600;display:block}
            .heatmap .cell .s{font-size:10px;color:rgba(0,0,0,.65);display:block;margin-top:2px}
            .chart-wrap{background:#fbfcfd;border:1px solid var(--border);border-radius:8px;
                        padding:16px;margin:16px 0;height:360px}
            .grid-2{display:grid;grid-template-columns:1fr 1fr;gap:16px}
            .pill{display:inline-block;padding:2px 8px;border-radius:10px;font-size:11px;
                  background:var(--brand-soft);color:var(--brand);font-weight:600}
            .legend{font-size:12px;color:var(--dim);margin-top:8px}
            .insight{background:#fef9e7;border-left:4px solid var(--warn);
                     padding:12px 16px;border-radius:4px;font-size:13px;margin:12px 0}
            footer{margin-top:32px;padding-top:16px;border-top:1px solid var(--border);
                   color:var(--dim);font-size:12px;text-align:center}
            """;

    private static final String JS = """
            const us2ms = us => (us/1000).toFixed(us<10000?3:(us<100000?2:1));
            function fmtTps(n){ return n>=1000 ? (n/1000).toFixed(1)+'k' : n.toFixed(0); }
            function colorFor(value, min, max){
              const t = (value-min) / Math.max(max-min, 1);
              const r = Math.round(254 - 200*t);
              const g = Math.round(243 - 50*t);
              const b = Math.round(199 - 100*t);
              return `rgb(${r},${g},${b})`;
            }
            function colorForLatency(value, min, max){
              const t = (value-min) / Math.max(max-min, 1);
              const r = Math.round(220 + 35*t);
              const g = Math.round(245 - 100*t);
              const b = Math.round(220 - 100*t);
              return `rgb(${r},${g},${b})`;
            }

            function renderReport(d){
              const r = document.getElementById('root');
              r.innerHTML += renderMeta(d);
              r.innerHTML += renderMatrix1(d.cells);
              r.innerHTML += renderMatrix2(d.cells);
              r.innerHTML += renderMatrix3(d.cells);
              r.innerHTML += renderMatrix4(d.cells);
              r.innerHTML += renderQpsLadder(d.cells);
              r.innerHTML += renderTtlMatrix(d.cells);
              r.innerHTML += renderSizeMatrix(d.cells);
              r.innerHTML += renderMixMatrix(d.cells);
              r.innerHTML += renderAllCells(d.cells);
              drawCharts(d);
              drawQpsChart(d);
              drawMixChart(d);
            }

            function renderMeta(d){
              return `<section><h2>测试元数据</h2>
                <table>
                  <tr><th>每单元持续</th><td>${d.perCellDurationMs} ms</td></tr>
                  <tr><th>总单元数</th><td>${d.totalCells}</td></tr>
                  <tr><th>总耗时</th><td>${d.totalElapsedMs} ms（${(d.totalElapsedMs/1000).toFixed(1)} 秒）</td></tr>
                  <tr><th>生成时间</th><td>${d.generatedAt}</td></tr>
                  <tr><th>传输层</th><td>InMemoryTransport（无 SpiceDB / 无网络）</td></tr>
                  <tr><th>缓存</th><td>CaffeineCache，maxSize=100,000，TTL=10min</td></tr>
                </table>
                <div class="insight">这一组测试只测 SDK 本身的性能（缓存查找、coalescing、metrics 开销等），延迟数据反映的是 Java 调用栈 + 线程同步成本，不含任何 SpiceDB 行为。</div>
              </section>`;
            }

            function renderMatrix1(cells){
              const m1 = cells.filter(c => c.distribution==='uniform' && /^read\\.dist=uniform\\.hr=.*\\.t=\\d+\\.cache=true\\.coalesce=false$/.test(c.name));
              if(!m1.length) return '';
              const hrs = [...new Set(m1.map(c => c.targetHitRate))].sort((a,b)=>a-b);
              const ts = [...new Set(m1.map(c => c.threads))].sort((a,b)=>a-b);
              const tpsVals = m1.map(c => c.tps);
              const tpsMin = Math.min(...tpsVals), tpsMax = Math.max(...tpsVals);
              const p99Vals = m1.map(c => c.p99us);
              const p99Min = Math.min(...p99Vals), p99Max = Math.max(...p99Vals);

              let html = `<section><h2>矩阵 1 — 缓存命中率 × 并发（uniform 分布，5×4=20 单元）</h2>
                <div class="desc">主矩阵：横轴并发线程数，纵轴目标缓存命中率。颜色越绿表示越优（TPS 越高 / 延迟越低）。</div>
                <h3 style="margin-top:8px">TPS 热力图</h3>
                <div class="heatmap" style="--cols:${ts.length}">
                  <div class="hdr">命中率 \\\\ 并发</div>`;
              ts.forEach(t => html += `<div class="hdr">${t} 线程</div>`);
              hrs.forEach(hr => {
                html += `<div class="rowlabel">${(hr*100).toFixed(0)}%</div>`;
                ts.forEach(t => {
                  const cell = m1.find(c => c.targetHitRate===hr && c.threads===t);
                  if(cell){
                    html += `<div class="cell" style="background:${colorFor(cell.tps, tpsMin, tpsMax)}">
                      <span class="v">${fmtTps(cell.tps)}</span>
                      <span class="s">实测命中 ${(cell.actualHitRate*100).toFixed(0)}%</span></div>`;
                  } else { html += `<div class="cell">—</div>`; }
                });
              });
              html += `</div>
                <div class="legend">数字 = 操作/秒，下方括号 = 实测缓存命中率</div>

                <h3 style="margin-top:24px">p99 延迟热力图（ms）</h3>
                <div class="heatmap" style="--cols:${ts.length}">
                  <div class="hdr">命中率 \\\\ 并发</div>`;
              ts.forEach(t => html += `<div class="hdr">${t} 线程</div>`);
              hrs.forEach(hr => {
                html += `<div class="rowlabel">${(hr*100).toFixed(0)}%</div>`;
                ts.forEach(t => {
                  const cell = m1.find(c => c.targetHitRate===hr && c.threads===t);
                  if(cell){
                    html += `<div class="cell" style="background:${colorForLatency(cell.p99us, p99Min, p99Max)}">
                      <span class="v">${us2ms(cell.p99us)}</span>
                      <span class="s">p50=${us2ms(cell.p50us)} ms</span></div>`;
                  } else { html += `<div class="cell">—</div>`; }
                });
              });
              html += `</div>
                <div class="legend">数字 = p99 毫秒（ms），下方括号 = 中位数 p50</div>
                <div class="chart-wrap"><canvas id="chart-m1-tps"></canvas></div>
              </section>`;
              return html;
            }

            function renderMatrix2(cells){
              const m2 = cells.filter(c => c.threads===100 && c.targetHitRate===0.95
                   && /^read\\.dist=.*\\.hr=0\\.95\\.t=100\\.cache=true\\.coalesce=false$/.test(c.name));
              if(m2.length < 3) return '';
              return `<section><h2>矩阵 2 — 请求分布的影响（100 线程，95% 目标命中率）</h2>
                <div class="desc">同样 95% 目标命中率，不同请求分布对实测命中率和延迟的影响。Zipfian (α=1.5) 模拟"少数热点 + 长尾"，single-hot 模拟所有请求打同一资源。</div>
                <table>
                  <tr><th>请求分布</th><th class="num">TPS</th><th class="num">实测命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">最大 (ms)</th></tr>
                  ${m2.map(c => `<tr>
                    <td>${esc(c.distribution)}</td>
                    <td class="num">${fmtTps(c.tps)}</td>
                    <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                    <td class="num">${us2ms(c.p50us)}</td>
                    <td class="num">${us2ms(c.p99us)}</td>
                    <td class="num">${us2ms(c.p999us)}</td>
                    <td class="num">${us2ms(c.maxUs)}</td>
                  </tr>`).join('')}
                </table>
                <div class="chart-wrap" style="height:280px"><canvas id="chart-m2-pct"></canvas></div>
              </section>`;
            }

            function renderMatrix3(cells){
              const m3 = cells.filter(c => c.distribution==='single-hot' && c.targetHitRate===1.0
                   && /^read\\.dist=single-hot\\.hr=1\\.00\\.t=100\\.cache=false\\./.test(c.name));
              if(m3.length < 2) return '';
              const off = m3.find(c => c.name.endsWith('coalesce=false'));
              const on = m3.find(c => c.name.endsWith('coalesce=true'));
              if(!off || !on) return '';
              const speedup = (on.tps / off.tps).toFixed(2);
              const p99cut = (1 - on.p99us / off.p99us) * 100;
              return `<section><h2>矩阵 3 — Coalescing 效果（single-hot 100 并发，无缓存）</h2>
                <div class="desc">所有 100 线程都打同一个资源 + 缓存关闭。Coalescing 将并发的相同请求合并成一次底层调用。</div>
                <table>
                  <tr><th></th><th class="num">TPS</th><th class="num">p50 (ms)</th><th class="num">p99 (ms)</th><th class="num">p999 (ms)</th></tr>
                  <tr><td>关闭 coalescing</td><td class="num">${fmtTps(off.tps)}</td><td class="num">${us2ms(off.p50us)}</td><td class="num">${us2ms(off.p99us)}</td><td class="num">${us2ms(off.p999us)}</td></tr>
                  <tr><td>开启 coalescing</td><td class="num">${fmtTps(on.tps)}</td><td class="num">${us2ms(on.p50us)}</td><td class="num">${us2ms(on.p99us)}</td><td class="num">${us2ms(on.p999us)}</td></tr>
                </table>
                <div class="insight">开启 coalescing 后 TPS 提升 <strong>${speedup}×</strong>，p99 下降 <strong>${p99cut.toFixed(1)}%</strong>。这个差距完全归因于 SDK 把并发的"读同一 key"请求合并成 1 次底层调用。</div>
              </section>`;
            }

            function renderMatrix4(cells){
              const m4 = cells.filter(c => c.distribution==='uniform' && c.targetHitRate===1.0
                   && /^read\\.dist=uniform\\.hr=1\\.00\\.t=100\\..+\\.coalesce=false$/.test(c.name));
              if(m4.length < 2) return '';
              const cacheOn = m4.find(c => c.name.includes('cache=true'));
              const cacheOff = m4.find(c => c.name.includes('cache=false'));
              if(!cacheOn || !cacheOff) return '';
              const speedup = (cacheOn.tps / cacheOff.tps).toFixed(2);
              return `<section><h2>矩阵 4 — 缓存开关对比（uniform 100 并发，全部 primed）</h2>
                <div class="desc">100% 命中率 + 缓存开/关。开启缓存后命中走 Caffeine（~微秒），关闭后每次都走 InMemoryTransport（也很快但有 ConcurrentHashMap 开销）。</div>
                <table>
                  <tr><th></th><th class="num">TPS</th><th class="num">p50 (ms)</th><th class="num">p99 (ms)</th><th class="num">实测命中率</th></tr>
                  <tr><td>开启缓存</td><td class="num">${fmtTps(cacheOn.tps)}</td><td class="num">${us2ms(cacheOn.p50us)}</td><td class="num">${us2ms(cacheOn.p99us)}</td><td class="num">${(cacheOn.actualHitRate*100).toFixed(0)}%</td></tr>
                  <tr><td>关闭缓存</td><td class="num">${fmtTps(cacheOff.tps)}</td><td class="num">${us2ms(cacheOff.p50us)}</td><td class="num">${us2ms(cacheOff.p99us)}</td><td class="num">N/A</td></tr>
                </table>
                <div class="insight">缓存提升 TPS <strong>${speedup}×</strong>。即使 InMemoryTransport 已经很快，缓存层依然能砍掉一定开销。</div>
              </section>`;
            }

            function renderQpsLadder(cells){
              const qps = cells.filter(c => c.workload==='QPS-TARGET').sort((a,b)=>a.threads-b.threads);
              if(!qps.length) return '';
              const rows = qps.map(c => {
                const target = c.threads;        // we encoded target QPS as threads
                const achieved = c.tps;
                const reached = achieved / target;
                const cls = reached >= 0.95 ? 'pass' : (reached >= 0.5 ? 'warn' : 'fail');
                const reachedColor = reached >= 0.95 ? '#10b981' : (reached >= 0.5 ? '#f59e0b' : '#ef4444');
                return `<tr>
                  <td class="num">${target.toLocaleString()}</td>
                  <td class="num"><strong>${achieved.toFixed(0).toLocaleString()}</strong></td>
                  <td class="num" style="color:${reachedColor}">${(reached*100).toFixed(1)}%</td>
                  <td class="num">${us2ms(c.minUs)}</td>
                  <td class="num">${us2ms(c.p50us)}</td>
                  <td class="num">${us2ms(c.p99us)}</td>
                  <td class="num">${us2ms(c.p999us)}</td>
                  <td class="num">${us2ms(c.maxUs)}</td>
                  <td class="num">${c.errors}</td>
                </tr>`;
              }).join('');
              // Find the "knee" (highest QPS that maintained <50% degradation in p99 vs lowest)
              const baseline = qps[0];
              const baselineP99 = baseline.p99us;
              let knee = baseline;
              for (const q of qps) {
                if (q.p99us < baselineP99 * 3) knee = q;   // p99 < 3× baseline → still healthy
              }
              return `<section><h2>矩阵 5 — QPS 阶梯（控制速率，看延迟拐点）</h2>
                <div class="desc">和前面"压满"测试不同：这里指定每秒目标 QPS（uniform 95% 命中），用 wrk2 风格的 token-bucket 控速，记录响应时延（含队列等待，捕捉协调省略）。</div>
                <table>
                  <tr><th class="num">目标 QPS</th><th class="num">实测 QPS</th><th class="num">达成率</th>
                      <th class="num">min (ms)</th><th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">最大 (ms)</th><th class="num">err</th></tr>
                  ${rows}
                </table>
                <div class="insight">
                  <strong>拐点（健康上限）：约 ${knee.threads.toLocaleString()} QPS</strong> —
                  超过这个值后 p99 延迟开始 &gt;3× 基线（${us2ms(baselineP99)} ms），SDK 进入排队状态。
                </div>
                <div class="chart-wrap" style="height:340px"><canvas id="chart-qps-ladder"></canvas></div>
                <div class="legend">绿色达成率=系统能跟上；橙黄=部分跟上但开始排队；红色=严重过载</div>
              </section>`;
            }

            function renderTtlMatrix(cells){
              const ttl = cells.filter(c => c.name.startsWith('ttl.'))
                              .sort((a,b) => parseInt(a.name.match(/ttl=(\\d+)s/)[1]) - parseInt(b.name.match(/ttl=(\\d+)s/)[1]));
              if(!ttl.length) return '';
              const rows = ttl.map(c => {
                const sec = parseInt(c.name.match(/ttl=(\\d+)s/)[1]);
                const label = sec < 60 ? sec+' 秒' : (sec < 3600 ? (sec/60)+' 分钟' : (sec>=86400 ? '无限(1天)' : (sec/3600)+' 小时'));
                return `<tr>
                  <td><strong>${label}</strong></td>
                  <td class="num">${fmtTps(c.tps)}</td>
                  <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                  <td class="num">${us2ms(c.p50us)}</td>
                  <td class="num">${us2ms(c.p99us)}</td>
                  <td class="num">${us2ms(c.p999us)}</td>
                  <td class="num">${us2ms(c.maxUs)}</td>
                </tr>`;
              }).join('');
              return `<section><h2>矩阵 6 — 缓存 TTL 影响（uniform 100 并发，全部 primed）</h2>
                <div class="desc">相同工作负载下改变 TTL。短 TTL 意味着缓存条目频繁过期、需要重新经过整个 transport 链，实测命中率会反映出来。</div>
                <table>
                  <tr><th>TTL</th><th class="num">TPS</th><th class="num">实测命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">max (ms)</th></tr>
                  ${rows}
                </table>
              </section>`;
            }

            function renderSizeMatrix(cells){
              const sz = cells.filter(c => c.name.startsWith('size.'))
                             .sort((a,b) => parseInt(a.name.match(/max=(\\d+)/)[1]) - parseInt(b.name.match(/max=(\\d+)/)[1]));
              if(!sz.length) return '';
              const rows = sz.map(c => {
                const size = parseInt(c.name.match(/max=(\\d+)/)[1]);
                return `<tr>
                  <td><strong>${size.toLocaleString()}</strong></td>
                  <td class="num">${fmtTps(c.tps)}</td>
                  <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                  <td class="num">${us2ms(c.p50us)}</td>
                  <td class="num">${us2ms(c.p99us)}</td>
                  <td class="num">${us2ms(c.p999us)}</td>
                  <td class="num">${us2ms(c.maxUs)}</td>
                </tr>`;
              }).join('');
              return `<section><h2>矩阵 7 — Cache 容量影响（uniform 100 并发，10k primed）</h2>
                <div class="desc">数据集 10k 个资源，缓存容量从 1k（远小于数据集，大量驱逐）到 100k（完全覆盖）。容量小于数据集时会看到命中率暴跌。</div>
                <table>
                  <tr><th>Cache maxSize</th><th class="num">TPS</th><th class="num">实测命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">max (ms)</th></tr>
                  ${rows}
                </table>
                <div class="insight">缓存容量小于工作集时，W-TinyLFU 驱逐启动，命中率显著下降 — 这个数据可以用来给生产环境挑合适的 maxSize。</div>
              </section>`;
            }

            function renderMixMatrix(cells){
              const mix = cells.filter(c => c.name.startsWith('mix.'))
                              .sort((a,b) => parseFloat(a.name.match(/writeRatio=([\\d.]+)/)[1]) - parseFloat(b.name.match(/writeRatio=([\\d.]+)/)[1]));
              if(!mix.length) return '';
              const rows = mix.map(c => {
                const wr = parseFloat(c.name.match(/writeRatio=([\\d.]+)/)[1]);
                const wrPct = (wr*100).toFixed(0);
                const rPct = ((1-wr)*100).toFixed(0);
                const label = wr === 0 ? '纯读' : (wr === 1 ? '纯写' : `${rPct}% 读 / ${wrPct}% 写`);
                return `<tr>
                  <td><strong>${label}</strong></td>
                  <td class="num">${fmtTps(c.tps)}</td>
                  <td class="num">${(c.actualHitRate*100).toFixed(1)}%</td>
                  <td class="num">${us2ms(c.p50us)}</td>
                  <td class="num">${us2ms(c.p99us)}</td>
                  <td class="num">${us2ms(c.p999us)}</td>
                  <td class="num">${us2ms(c.maxUs)}</td>
                  <td class="num">${c.errors}</td>
                </tr>`;
              }).join('');
              return `<section><h2>矩阵 8 — 读写混合比（100 并发）</h2>
                <div class="desc">不同读写比例下的 SDK 吞吐。每次写触发对应资源的缓存失效（double-delete），所以写比例越高、有效命中率越低、TPS 越低。</div>
                <table>
                  <tr><th>工作负载</th><th class="num">TPS</th><th class="num">实测命中率</th>
                      <th class="num">p50 (ms)</th><th class="num">p99 (ms)</th>
                      <th class="num">p999 (ms)</th><th class="num">max (ms)</th><th class="num">err</th></tr>
                  ${rows}
                </table>
                <div class="chart-wrap" style="height:280px"><canvas id="chart-mix"></canvas></div>
              </section>`;
            }

            function renderAllCells(cells){
              return `<section><h2>所有单元 — 完整百分位明细</h2>
                <div class="desc">所有 ${cells.length} 个矩阵单元的完整 HdrHistogram 数据（min/p50/p90/p99/p999/p9999/max + 错误计数）。</div>
                <table>
                  <tr><th>单元名</th><th class="num">操作数</th><th class="num">TPS</th>
                      <th class="num">命中率</th>
                      <th class="num">min</th><th class="num">p50</th><th class="num">p90</th>
                      <th class="num">p99</th><th class="num">p999</th><th class="num">p9999</th>
                      <th class="num">max</th><th class="num">err</th></tr>
                  ${cells.map(c => `<tr>
                    <td style="word-break:break-all;font-size:11px">${esc(c.name)}</td>
                    <td class="num">${c.ops.toLocaleString()}</td>
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
                <div class="legend">所有延迟单位为毫秒（ms）；操作太快时可能显示 0.001 = 1μs</div>
              </section>`;
            }

            function drawCharts(d){
              // Matrix 1 TPS chart: lines per hit-rate, x=concurrency
              const m1 = d.cells.filter(c => c.distribution==='uniform' && /^read\\.dist=uniform\\.hr=.*\\.t=\\d+\\.cache=true\\.coalesce=false$/.test(c.name));
              if(m1.length){
                const hrs = [...new Set(m1.map(c => c.targetHitRate))].sort((a,b)=>a-b);
                const ts = [...new Set(m1.map(c => c.threads))].sort((a,b)=>a-b);
                const palette = ['#0a5bce','#10b981','#f59e0b','#ef4444','#8b5cf6'];
                const datasets = hrs.map((hr,i) => ({
                  label: '命中率 ' + (hr*100).toFixed(0) + '%',
                  data: ts.map(t => (m1.find(c=>c.targetHitRate===hr && c.threads===t)||{}).tps || 0),
                  borderColor: palette[i % palette.length],
                  backgroundColor: palette[i % palette.length] + '20',
                  tension: 0.25
                }));
                const ctx = document.getElementById('chart-m1-tps');
                if(ctx) new Chart(ctx, {
                  type:'line',
                  data:{ labels: ts.map(t=>t+'线程'), datasets },
                  options:{ responsive:true, maintainAspectRatio:false,
                    scales:{ y:{ beginAtZero:true, title:{display:true,text:'TPS'} },
                             x:{ title:{display:true,text:'并发线程数'} } } }
                });
              }

              // Matrix 2 percentile chart
              const m2 = d.cells.filter(c => c.threads===100 && c.targetHitRate===0.95
                   && /^read\\.dist=.*\\.hr=0\\.95\\.t=100\\.cache=true\\.coalesce=false$/.test(c.name));
              if(m2.length){
                const palette = ['#0a5bce','#10b981','#f59e0b'];
                const datasets = m2.map((c,i) => ({
                  label: c.distribution,
                  data: c.histogramBuckets.map(b => b[1]),
                  borderColor: palette[i % palette.length],
                  backgroundColor: palette[i % palette.length] + '20',
                  tension: 0.2
                }));
                const ctx = document.getElementById('chart-m2-pct');
                if(ctx) new Chart(ctx, {
                  type:'line',
                  data:{ labels: m2[0].histogramBuckets.map(b => 'p'+(b[0]/100).toFixed(b[0]>=10000?2:0)),
                         datasets: datasets.map(ds => ({...ds, data: ds.data.map(v => v/1000)})) },
                  options:{ responsive:true, maintainAspectRatio:false,
                    scales:{ y:{ beginAtZero:false, type:'logarithmic',
                                 title:{display:true,text:'延迟 ms (对数)'} },
                             x:{ title:{display:true,text:'百分位'} } } }
                });
              }
            }

            function drawQpsChart(d){
              const qps = d.cells.filter(c => c.workload==='QPS-TARGET').sort((a,b)=>a.threads-b.threads);
              if(!qps.length) return;
              const ctx = document.getElementById('chart-qps-ladder');
              if(!ctx) return;
              new Chart(ctx, {
                type:'line',
                data:{
                  labels: qps.map(c => c.threads.toLocaleString() + ' QPS'),
                  datasets: [
                    {label:'p50 (ms)', data: qps.map(c => c.p50us/1000),
                     borderColor:'#10b981', backgroundColor:'#10b98120', tension:0.25, yAxisID:'y'},
                    {label:'p99 (ms)', data: qps.map(c => c.p99us/1000),
                     borderColor:'#f59e0b', backgroundColor:'#f59e0b20', tension:0.25, yAxisID:'y'},
                    {label:'p999 (ms)', data: qps.map(c => c.p999us/1000),
                     borderColor:'#ef4444', backgroundColor:'#ef444420', tension:0.25, yAxisID:'y'},
                    {label:'达成率 %', data: qps.map(c => (c.tps/c.threads)*100),
                     borderColor:'#0a5bce', backgroundColor:'#0a5bce20', borderDash:[5,5], tension:0.25, yAxisID:'y2'}
                  ]
                },
                options:{ responsive:true, maintainAspectRatio:false,
                  scales:{
                    y:{beginAtZero:true, type:'logarithmic', position:'left',
                       title:{display:true, text:'延迟 ms（对数）'}},
                    y2:{beginAtZero:true, max:110, position:'right',
                        title:{display:true, text:'达成率 %'},
                        grid:{drawOnChartArea:false}}
                  } }
              });
            }

            function drawMixChart(d){
              const mix = d.cells.filter(c => c.name.startsWith('mix.'))
                                .sort((a,b) => parseFloat(a.name.match(/writeRatio=([\\d.]+)/)[1]) - parseFloat(b.name.match(/writeRatio=([\\d.]+)/)[1]));
              if(!mix.length) return;
              const ctx = document.getElementById('chart-mix');
              if(!ctx) return;
              new Chart(ctx, {
                type:'bar',
                data:{
                  labels: mix.map(c => {
                    const wr = parseFloat(c.name.match(/writeRatio=([\\d.]+)/)[1]);
                    return wr===0 ? '纯读' : (wr===1 ? '纯写' : ((1-wr)*100).toFixed(0)+'读/'+(wr*100).toFixed(0)+'写');
                  }),
                  datasets:[
                    {label:'TPS', data: mix.map(c => c.tps),
                     backgroundColor:'#0a5bce', yAxisID:'y'},
                    {label:'命中率 %', data: mix.map(c => c.actualHitRate*100),
                     backgroundColor:'#10b981', yAxisID:'y2'}
                  ]
                },
                options:{ responsive:true, maintainAspectRatio:false,
                  scales:{
                    y:{beginAtZero:true, position:'left', title:{display:true,text:'TPS'}},
                    y2:{beginAtZero:true, max:100, position:'right',
                        title:{display:true,text:'命中率 %'},
                        grid:{drawOnChartArea:false}}
                  }}
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
