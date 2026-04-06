package com.authcses.benchmark;

import com.authcses.sdk.AuthCsesClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 真实访问模式压测。
 *
 * 每个线程模拟一个在线用户：
 *   - 有固定的 userId
 *   - 有常访问的 5-10 个文档（工作区）
 *   - 80% 的请求打自己的工作区（SDK 缓存命中率高）
 *   - 20% 的请求打随机文档（缓存未命中）
 *   - 每次页面加载查 3 个权限（view, edit, manage）
 *
 * 对比之前的纯随机模式，展示 SDK 缓存在真实场景下的效果。
 */
public class RealisticStress {

    static final int TOTAL_DOCS = 500;
    static final int TOTAL_USERS = 1000;
    static final int DOCS_PER_USER = 8;       // 每个用户常访问的文档数
    static final int STEP_THREADS = 50;        // 每轮增加的"在线用户"
    static final int MAX_THREADS = 2000;
    static final int WINDOW_SEC = 5;
    static final double BREACH_RATIO = 0.30;
    static final long THRESHOLD_US = 100_000;  // 100ms

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   真实访问模式极限压测                                         ║");
        System.out.println("║   每个线程 = 1 个在线用户，80% 查自己的文档                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.printf("  文档: %d | 用户: %d | 每用户常访问 %d 文档%n",
                TOTAL_DOCS, TOTAL_USERS, DOCS_PER_USER);
        System.out.printf("  每轮 +%d 在线用户 | 阈值: >100ms 超 30%% 停止%n%n", STEP_THREADS);

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

        // ── 预热 ──
        System.out.println("[预热] 预热 SpiceDB dispatch cache...");
        var warmPool = Executors.newFixedThreadPool(20);
        var warmFutures = new ArrayList<Future<?>>();
        for (int i = 0; i < TOTAL_DOCS; i++) {
            int d = i;
            warmFutures.add(warmPool.submit(() -> {
                try { client.check("document", "doc-" + d, "view", "user-0"); } catch (Exception ignored) {}
            }));
        }
        for (var f : warmFutures) f.get();
        warmPool.shutdown();
        Thread.sleep(2000);
        System.out.println("[预热] 完成\n");

        // ── 加压 ──
        System.out.printf("%-10s %10s %10s %10s %10s %10s %10s %8s%n",
                "在线用户", "QPS", "总请求", "P50(µs)", "P95(µs)", "P99(µs)", ">100ms", "比例");
        System.out.println("─".repeat(90));

        List<long[]> history = new ArrayList<>();  // [threads, qps, p50, p95, p99, over, total]
        int peakQps = 0;

        for (int threads = STEP_THREADS; threads <= MAX_THREADS; threads += STEP_THREADS) {
            var result = runRound(client, threads);
            long qps = result[1], total = result[6], p50 = result[2], p95 = result[3],
                 p99 = result[4], over = result[5];
            double ratio = total == 0 ? 1.0 : (double) over / total;
            history.add(result);

            if (qps > peakQps) peakQps = (int) qps;

            System.out.printf("%-10d %,10d %,10d %,10d %,10d %,10d %,10d %7.1f%%%n",
                    threads, qps, total, p50, p95, p99, over, ratio * 100);

            if (ratio >= BREACH_RATIO) {
                System.out.println("─".repeat(90));
                System.out.printf("⚠ 停止: %.1f%% 请求 >100ms%n%n", ratio * 100);
                break;
            }
        }

        // ── 报告 ──
        var m = client.metrics().snapshot();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                     压 测 报 告                               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("  峰值 QPS:      %,d%n", peakQps);
        System.out.printf("  SDK 缓存命中率: %.1f%%  (命中: %,d | 未命中: %,d)%n",
                m.cacheHitRate() * 100, m.cacheHits(), m.cacheMisses());
        System.out.printf("  合并请求:       %,d%n", m.coalescedRequests());
        System.out.println();

        // QPS 曲线
        System.out.println("┌─ QPS 曲线 ────────────────────────────────────────────────┐");
        long maxQps = history.stream().mapToLong(r -> r[1]).max().orElse(1);
        for (var r : history) {
            int barLen = (int) ((double) r[1] / maxQps * 35);
            double ratio = r[6] == 0 ? 0 : (double) r[5] / r[6] * 100;
            String marker = ratio >= 30 ? " ✗" : "";
            System.out.printf("│ %4d用户 │%s %,d%s%n", (int) r[0], "█".repeat(barLen), r[1], marker);
        }
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        client.close();
        System.out.println("\n完成。");
    }

    static long[] runRound(AuthCsesClient client, int threads) throws Exception {
        // 每个线程分配一个固定用户和常访问文档列表
        int[][] userWorkspaces = new int[threads][];
        int[] userIds = new int[threads];
        var rng = ThreadLocalRandom.current();
        for (int t = 0; t < threads; t++) {
            userIds[t] = t % TOTAL_USERS;
            userWorkspaces[t] = new int[DOCS_PER_USER];
            // 每个用户的常访问文档是固定的（基于 userId hash）
            int base = (userIds[t] * 7) % TOTAL_DOCS;
            for (int d = 0; d < DOCS_PER_USER; d++) {
                userWorkspaces[t][d] = (base + d) % TOTAL_DOCS;
            }
        }

        var pool = Executors.newFixedThreadPool(threads);
        var ops = new LongAdder();
        var over100ms = new LongAdder();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        long deadline = System.currentTimeMillis() + WINDOW_SEC * 1000L;

        var futures = new ArrayList<Future<?>>();
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            final int userId = userIds[t];
            final int[] workspace = userWorkspaces[t];

            futures.add(pool.submit(() -> {
                var tlr = ThreadLocalRandom.current();
                String user = "user-" + userId;

                while (System.currentTimeMillis() < deadline) {
                    try {
                        long s = System.nanoTime();

                        if (tlr.nextDouble() < 0.80) {
                            // 80% — 查自己常访问的文档（模拟页面加载/刷新）
                            int docId = workspace[tlr.nextInt(DOCS_PER_USER)];
                            String doc = "doc-" + docId;

                            // 模拟页面加载：查 view + edit + manage
                            double op = tlr.nextDouble();
                            if (op < 0.50) {
                                client.check("document", doc, "view", user);
                            } else if (op < 0.80) {
                                client.check("document", doc, "edit", user);
                            } else {
                                client.check("document", doc, "manage", user);
                            }
                        } else {
                            // 20% — 随机浏览其他文档
                            int docId = tlr.nextInt(TOTAL_DOCS);
                            client.check("document", "doc-" + docId, "view", user);
                        }

                        long latUs = (System.nanoTime() - s) / 1000;
                        latencies.add(latUs);
                        ops.increment();
                        if (latUs > THRESHOLD_US) over100ms.increment();
                    } catch (Exception ignored) {}
                }
            }));
        }
        for (var f : futures) f.get();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        long totalOps = ops.sum();
        long qps = totalOps / WINDOW_SEC;
        long overCount = over100ms.sum();

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int n = sorted.size();
        long p50 = n > 0 ? sorted.get((int)(n * 0.50)) : 0;
        long p95 = n > 0 ? sorted.get((int)(n * 0.95)) : 0;
        long p99 = n > 0 ? sorted.get((int)(n * 0.99)) : 0;

        return new long[]{threads, qps, p50, p95, p99, overCount, totalOps};
    }

    static void seed(AuthCsesClient client) throws Exception {
        client.on("organization").resource("real-org").grant("admin").to("org-admin");
        client.on("space").resource("real-space").grant("org").toSubjects("organization:real-org");
        client.on("space").resource("real-space").grant("owner").to("space-owner");
        for (int u = 0; u < 200; u++)
            client.grant("space", "real-space", "member", "user-" + u);

        for (int d = 1; d <= 5; d++) {
            String fid = "real-folder-" + d;
            client.on("folder").resource(fid).grant("space").toSubjects("space:real-space");
            for (int a = 1; a < d; a++)
                client.on("folder").resource(fid).grant("ancestor").toSubjects("folder:real-folder-" + a);
            for (int u = 0; u < 20; u++)
                client.grant("folder", fid, "editor", "user-" + (d * 20 + u));
        }

        var seedPool = Executors.newFixedThreadPool(10);
        var futures = new ArrayList<Future<?>>();
        for (int batch = 0; batch < TOTAL_DOCS; batch += 50) {
            int start = batch;
            futures.add(seedPool.submit(() -> {
                for (int i = start; i < start + 50 && i < TOTAL_DOCS; i++) {
                    try {
                        String docId = "doc-" + i;
                        client.on("document").resource(docId).grant("folder")
                                .toSubjects("folder:real-folder-" + ((i % 5) + 1));
                        client.on("document").resource(docId).grant("space")
                                .toSubjects("space:real-space");
                        client.grant("document", docId, "owner", "user-" + (i % TOTAL_USERS));
                        for (int e = 0; e < 3; e++)
                            client.grant("document", docId, "editor",
                                    "user-" + ((i * 3 + e + 100) % TOTAL_USERS));
                        for (int v = 0; v < 5; v++)
                            client.grant("document", docId, "viewer",
                                    "user-" + ((i * 5 + v + 300) % TOTAL_USERS));
                    } catch (Exception ignored) {}
                }
            }));
        }
        seedPool.shutdown();
        for (var f : futures) f.get();
    }
}
