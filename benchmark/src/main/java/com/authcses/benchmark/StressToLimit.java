package com.authcses.benchmark;

import com.authcses.sdk.AuthCsesClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 极限压测 — 逐步加压，直到 >30% 的请求超过 100ms 自动停止。
 *
 * 策略：每轮增加并发线程数，监测 >100ms 的请求比例。
 * 一旦超过 30%，输出当前极限 QPS 并停止。
 */
public class StressToLimit {

    static final int WINDOW_SEC = 5;         // 每轮测量时间
    static final int STEP_THREADS = 20;      // 每轮增加线程数
    static final int MAX_THREADS = 2000;     // 安全上限
    static final double BREACH_RATIO = 0.30; // >100ms 的比例阈值
    static final long LATENCY_THRESHOLD_US = 100_000; // 100ms in µs

    // 数据规模
    static final int HOT_DOCS = 200;
    static final int ACTIVE_USERS = 1000;
    static final int TOTAL_DOCS = 500;

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   极限压测 — 自动加压直到 30% 请求 >100ms                       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.printf("  热门文档: %d | 活跃用户: %d | 每轮 +%d 线程 | 阈值: >100ms 超 30%% 停止%n%n",
                HOT_DOCS, ACTIVE_USERS, STEP_THREADS);

        var client = AuthCsesClient.builder()
                .connection(c -> c
                        .targets("localhost:50051", "localhost:50052")
                        .presharedKey("testkey")
                        .requestTimeout(Duration.ofSeconds(10))
                        .keepAliveTime(Duration.ofSeconds(30)))
                .cache(c -> c.enabled(true).maxSize(500_000).watchInvalidation(false))
                .features(f -> f.coalescing(true))
                .extend(e -> e.policies(
                        com.authcses.sdk.policy.PolicyRegistry.builder()
                                .defaultPolicy(com.authcses.sdk.policy.ResourcePolicy.builder()
                                        .cache(com.authcses.sdk.policy.CachePolicy.builder()
                                                .enabled(true).ttl(Duration.ofSeconds(30)).build())
                                        .readConsistency(com.authcses.sdk.policy.ReadConsistency.minimizeLatency())
                                        .build())
                                .build()))
                .build();

        // ── 播种 ──
        System.out.println("[播种] 创建数据...");
        seed(client);
        System.out.println("[播种] 完成\n");

        // ── 全量预热 dispatch cache ──
        System.out.println("[预热] 全量预热 SpiceDB dispatch cache (" + TOTAL_DOCS + " 文档 × 3 权限)...");
        // 预热所有文档的所有权限路径 — dispatch cache 缓存关系数据，用户无关
        var warmPool = java.util.concurrent.Executors.newFixedThreadPool(20);
        var warmFutures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < TOTAL_DOCS; i++) {
            int docId = i;
            warmFutures.add(warmPool.submit(() -> {
                try {
                    client.check("document", "doc-" + docId, "view", "user-0");
                    client.check("document", "doc-" + docId, "edit", "user-0");
                    client.check("document", "doc-" + docId, "manage", "user-0");
                } catch (Exception ignored) {}
            }));
        }
        for (var wf : warmFutures) wf.get();
        warmPool.shutdown();
        // 等 quantized revision 稳定
        Thread.sleep(2000);

        // 验证 dispatch cache 生效
        long before = fetchCrdbSelects();
        for (int i = 0; i < 100; i++)
            client.check("document", "doc-" + (i % TOTAL_DOCS), "view", "user-" + (i * 7 % ACTIVE_USERS));
        long after = fetchCrdbSelects();
        System.out.printf("[预热] 完成 — 验证: 100 个 check = %d CRDB SELECTs (%.1f/check, 应≈0)%n%n",
                after - before, (after - before) / 100.0);

        // ── 逐步加压 ──
        System.out.printf("%-8s %10s %10s %10s %10s %10s %10s %8s%n",
                "线程", "QPS", "总请求", "错误", "P50(µs)", "P99(µs)", ">100ms", "比例");
        System.out.println("─".repeat(88));

        List<RoundResult> history = new ArrayList<>();
        int peakQps = 0;
        int peakThreads = 0;

        for (int threads = STEP_THREADS; threads <= MAX_THREADS; threads += STEP_THREADS) {
            RoundResult r = runRound(client, threads);
            history.add(r);

            if (r.qps > peakQps) {
                peakQps = r.qps;
                peakThreads = threads;
            }

            System.out.printf("%-8d %,10d %,10d %,10d %,10d %,10d %,10d %7.1f%%%n",
                    threads, r.qps, r.total, r.errors, r.p50us, r.p99us,
                    r.overThreshold, r.breachRatio * 100);

            if (r.breachRatio >= BREACH_RATIO) {
                System.out.println("─".repeat(88));
                System.out.printf("⚠ 停止: %.1f%% 请求 >100ms (阈值 %.0f%%)%n%n",
                        r.breachRatio * 100, BREACH_RATIO * 100);
                break;
            }

            // QPS 下降超过 20% 也是退化信号，但继续加压看看
            if (history.size() > 2 && r.qps < peakQps * 0.7) {
                System.out.println("─".repeat(88));
                System.out.printf("⚠ 停止: QPS 从峰值 %,d 下降超过 30%%，当前 %,d%n%n", peakQps, r.qps);
                break;
            }
        }

        // ── 报告 ──
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                     极 限 报 告                               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // 找到最后一个 <30% 的轮次
        RoundResult lastGood = null;
        for (var r : history) {
            if (r.breachRatio < BREACH_RATIO) lastGood = r;
        }
        RoundResult breached = history.getLast();

        System.out.println();
        System.out.println("┌─ 极限 QPS ────────────────────────────────────────────────┐");
        System.out.printf("│  峰值 QPS:       %,10d  (at %d 线程)                     │%n", peakQps, peakThreads);
        if (lastGood != null) {
            System.out.printf("│  安全 QPS:       %,10d  (at %d 线程, >100ms=%.1f%%)       │%n",
                    lastGood.qps, lastGood.threads, lastGood.breachRatio * 100);
            System.out.printf("│  安全 P50:       %,10dµs                                │%n", lastGood.p50us);
            System.out.printf("│  安全 P99:       %,10dµs                                │%n", lastGood.p99us);
        }
        System.out.printf("│  崩溃点:         %,10d 线程 (>100ms=%.1f%%)               │%n",
                breached.threads, breached.breachRatio * 100);
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        var m = client.metrics().snapshot();
        System.out.println();
        System.out.println("┌─ SDK 指标 ────────────────────────────────────────────────┐");
        System.out.printf("│  缓存命中率: %6.1f%%  | 命中: %,d | 未命中: %,d           │%n",
                m.cacheHitRate()*100, m.cacheHits(), m.cacheMisses());
        System.out.printf("│  合并请求: %,d                                             │%n",
                m.coalescedRequests());
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        // QPS 曲线
        System.out.println();
        System.out.println("┌─ QPS 曲线 ────────────────────────────────────────────────┐");
        int maxQpsInHistory = history.stream().mapToInt(r -> r.qps).max().orElse(1);
        for (var r : history) {
            int barLen = (int) ((double) r.qps / maxQpsInHistory * 35);
            String marker = r.breachRatio >= BREACH_RATIO ? " ✗" : "";
            System.out.printf("│ %4d线程 │%s %,d%s%n",
                    r.threads, "█".repeat(barLen), r.qps, marker);
        }
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        // >100ms 比例曲线
        System.out.println();
        System.out.println("┌─ >100ms 比例 ─────────────────────────────────────────────┐");
        for (var r : history) {
            int barLen = (int) (r.breachRatio * 100);
            String bar = barLen > 30 ? "█".repeat(30) + "▓".repeat(Math.min(barLen - 30, 20))
                    : "█".repeat(barLen);
            System.out.printf("│ %4d线程 │%s %.1f%%%n", r.threads, bar, r.breachRatio * 100);
        }
        System.out.println("│          │" + " ".repeat(30) + "▲ 30% 阈值");
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        client.close();
        System.out.println("\n完成。");
    }

    static long fetchCrdbSelects() {
        try {
            var pb = new ProcessBuilder("bash", "-c",
                    "curl -s http://localhost:8080/_status/vars | grep '^sql_select_count{' | awk '{print $NF}'");
            var p = pb.start();
            String r = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return (long) Double.parseDouble(r);
        } catch (Exception e) { return 0; }
    }

    record RoundResult(int threads, int qps, int total, int errors,
                       long p50us, long p99us, int overThreshold, double breachRatio) {}

    static RoundResult runRound(AuthCsesClient client, int threads) throws Exception {
        var pool = Executors.newFixedThreadPool(threads);
        var ops = new LongAdder();
        var errs = new LongAdder();
        var over100ms = new LongAdder();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        long deadline = System.currentTimeMillis() + WINDOW_SEC * 1000L;

        var futures = new ArrayList<Future<?>>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                var rng = ThreadLocalRandom.current();
                while (System.currentTimeMillis() < deadline) {
                    try {
                        long s = System.nanoTime();

                        // 混合操作：80% check + 10% checkAll + 5% lookup + 5% write
                        double roll = rng.nextDouble();
                        if (roll < 0.60) {
                            // check view
                            int docId = zipfian(rng, HOT_DOCS, TOTAL_DOCS);
                            client.check("document", "doc-" + docId, "view",
                                    "user-" + rng.nextInt(ACTIVE_USERS));
                        } else if (roll < 0.80) {
                            // check edit
                            int docId = zipfian(rng, HOT_DOCS, TOTAL_DOCS);
                            client.check("document", "doc-" + docId, "edit",
                                    "user-" + rng.nextInt(ACTIVE_USERS));
                        } else if (roll < 0.90) {
                            // checkAll
                            int docId = zipfian(rng, HOT_DOCS, TOTAL_DOCS);
                            client.checkAll("document", "doc-" + docId,
                                    "user-" + rng.nextInt(ACTIVE_USERS),
                                    "view", "edit", "manage");
                        } else if (roll < 0.95) {
                            // grant
                            int docId = rng.nextInt(TOTAL_DOCS);
                            String[] rels = {"viewer", "editor", "commenter"};
                            client.grant("document", "doc-" + docId,
                                    rels[rng.nextInt(3)],
                                    "user-" + rng.nextInt(ACTIVE_USERS));
                        } else {
                            // revoke
                            int docId = rng.nextInt(TOTAL_DOCS);
                            try {
                                client.revoke("document", "doc-" + docId, "viewer",
                                        "user-" + rng.nextInt(ACTIVE_USERS));
                            } catch (Exception ignored) {}
                        }

                        long latUs = (System.nanoTime() - s) / 1000;
                        latencies.add(latUs);
                        ops.increment();
                        if (latUs > LATENCY_THRESHOLD_US) over100ms.increment();
                    } catch (Exception e) {
                        errs.increment();
                    }
                }
            }));
        }
        for (var f : futures) f.get();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        long totalOps = ops.sum();
        int qps = (int)(totalOps / WINDOW_SEC);
        int total = (int) totalOps;
        int errors = (int) errs.sum();
        int overCount = (int) over100ms.sum();
        double ratio = totalOps == 0 ? 1.0 : (double) overCount / totalOps;

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int n = sorted.size();
        long p50 = n > 0 ? sorted.get((int)(n * 0.50)) : 0;
        long p99 = n > 0 ? sorted.get((int)(n * 0.99)) : 0;

        return new RoundResult(threads, qps, total, errors, p50, p99, overCount, ratio);
    }

    static int zipfian(ThreadLocalRandom rng, int hotCount, int totalCount) {
        return rng.nextDouble() < 0.8 ? rng.nextInt(hotCount) : rng.nextInt(totalCount);
    }

    static void seed(AuthCsesClient client) throws Exception {
        client.on("organization").resource("stress-org").grant("admin").to("org-admin");
        client.on("space").resource("stress-space").grant("org").toSubjects("organization:stress-org");
        client.on("space").resource("stress-space").grant("owner").to("space-owner");
        for (int u = 0; u < 200; u++) {
            client.grant("space", "stress-space", "member", "user-" + u);
        }

        // 5 级文件夹嵌套 + ancestor
        for (int d = 1; d <= 5; d++) {
            String fid = "stress-folder-" + d;
            client.on("folder").resource(fid).grant("space").toSubjects("space:stress-space");
            for (int a = 1; a < d; a++) {
                client.on("folder").resource(fid).grant("ancestor").toSubjects("folder:stress-folder-" + a);
            }
            for (int u = 0; u < 20; u++) {
                client.grant("folder", fid, "editor", "user-" + (d * 20 + u));
            }
        }

        // 500 个文档
        var seedPool = Executors.newFixedThreadPool(10);
        var futures = new ArrayList<Future<?>>();
        for (int batch = 0; batch < TOTAL_DOCS; batch += 50) {
            int start = batch;
            futures.add(seedPool.submit(() -> {
                for (int i = start; i < start + 50 && i < TOTAL_DOCS; i++) {
                    try {
                        String docId = "doc-" + i;
                        client.on("document").resource(docId).grant("folder")
                                .toSubjects("folder:stress-folder-" + ((i % 5) + 1));
                        client.on("document").resource(docId).grant("space")
                                .toSubjects("space:stress-space");
                        client.grant("document", docId, "owner", "user-" + (i % ACTIVE_USERS));
                        for (int e = 0; e < 3; e++)
                            client.grant("document", docId, "editor",
                                    "user-" + ((i * 3 + e + 100) % ACTIVE_USERS));
                        for (int v = 0; v < 5; v++)
                            client.grant("document", docId, "viewer",
                                    "user-" + ((i * 5 + v + 300) % ACTIVE_USERS));
                    } catch (Exception ignored) {}
                }
            }));
        }
        seedPool.shutdown();
        for (var f : futures) f.get();
    }
}
