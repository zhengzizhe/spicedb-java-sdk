package com.authcses.benchmark;

import com.authcses.sdk.AuthCsesClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 真实生产场景模拟 — SpiceDB dispatch cache 热身后测量。
 *
 * 核心原理：SpiceDB 的 dispatch cache 按 (resource, permission, revision) 缓存中间结果。
 * quantization-interval=5s 意味着同一个 5s 窗口内，同一个资源的权限图只解析一次。
 *
 * 例如：folder-5#view_local 第一次查了 4 次 CRDB，之后 5s 内不管哪个用户查 folder-5 的 view，
 * SpiceDB 直接返回缓存结果，不打 CRDB。
 *
 * 这才是真实生产行为 — 热门文档被反复查，dispatch cache 命中率极高。
 */
public class CacheWarmBenchmark {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   真实生产场景 — dispatch cache 热身后性能                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // SDK：minimize_latency + SDK 缓存
        var client = AuthCsesClient.builder()
                .connection(c -> c
                        .targets("localhost:50051", "localhost:50052")
                        .presharedKey("testkey")
                        .requestTimeout(Duration.ofSeconds(10)))
                .cache(c -> c.enabled(true).maxSize(500_000).watchInvalidation(false))
                .features(f -> f.coalescing(true))
                .extend(e -> e.policies(
                        com.authcses.sdk.policy.PolicyRegistry.builder()
                                .defaultPolicy(com.authcses.sdk.policy.ResourcePolicy.builder()
                                        .cache(com.authcses.sdk.policy.CachePolicy.builder()
                                                .enabled(true).ttl(Duration.ofSeconds(5)).build())
                                        .readConsistency(com.authcses.sdk.policy.ReadConsistency.minimizeLatency())
                                        .build())
                                .build()))
                .build();

        // 无缓存的客户端（对照组，但也用 minimize_latency 让 SpiceDB dispatch cache 生效）
        var clientNocache = AuthCsesClient.builder()
                .connection(c -> c
                        .targets("localhost:50051", "localhost:50052")
                        .presharedKey("testkey")
                        .requestTimeout(Duration.ofSeconds(10)))
                .cache(c -> c.enabled(false).watchInvalidation(false))
                .features(f -> f.coalescing(false))
                .build();

        // ── 播种 20 层嵌套数据 ──
        System.out.println("\n[1/5] 播种 20 层嵌套数据...");
        seed(client);
        System.out.println("  数据就绪\n");

        // ══════════════════════════════════════════════════════════════
        //  测试 1: 冷启动 vs 热 dispatch cache（单线程，SDK 无缓存）
        // ══════════════════════════════════════════════════════════════
        System.out.println("[2/5] 冷 vs 热 dispatch cache（minimize_latency, SDK 无缓存）");
        System.out.println("─".repeat(70));
        {
            // 冷启动：第一次查每个文档
            long[] coldLats = new long[100];
            for (int i = 0; i < 100; i++) {
                long s = System.nanoTime();
                clientNocache.check("document", "doc-" + i, "view", "user-" + (i * 7 % 500));
                coldLats[i] = System.nanoTime() - s;
            }
            Arrays.sort(coldLats);
            System.out.printf("  冷 dispatch cache (每个文档第一次查):  p50=%,dµs  p95=%,dµs  p99=%,dµs%n",
                    coldLats[50] / 1000, coldLats[95] / 1000, coldLats[99] / 1000);

            // 等 SpiceDB dispatch cache 稳定
            Thread.sleep(500);

            // 热 dispatch cache：同一批文档，不同用户
            long[] hotLats = new long[1000];
            for (int i = 0; i < 1000; i++) {
                long s = System.nanoTime();
                clientNocache.check("document", "doc-" + (i % 100), "view",
                        "user-" + (i * 13 % 500));
                hotLats[i] = System.nanoTime() - s;
            }
            Arrays.sort(hotLats);
            System.out.printf("  热 dispatch cache (同文档不同用户):   p50=%,dµs  p95=%,dµs  p99=%,dµs%n",
                    hotLats[500] / 1000, hotLats[950] / 1000, hotLats[990] / 1000);
        }

        // ══════════════════════════════════════════════════════════════
        //  测试 2: SDK 缓存命中（同用户重复查同文档）
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n[3/5] SDK L1 缓存命中（同用户重复查同文档）");
        System.out.println("─".repeat(70));
        {
            // 热身
            for (int i = 0; i < 100; i++) {
                client.check("document", "doc-" + (i % 50), "view", "user-" + (i % 20));
            }
            // 测量：完全命中 SDK 缓存
            long[] lats = new long[10000];
            for (int i = 0; i < 10000; i++) {
                long s = System.nanoTime();
                client.check("document", "doc-" + (i % 50), "view", "user-" + (i % 20));
                lats[i] = System.nanoTime() - s;
            }
            Arrays.sort(lats);
            System.out.printf("  SDK 缓存命中:  p50=%,dµs  p95=%,dµs  p99=%,dµs  (不打 SpiceDB)%n",
                    lats[5000] / 1000, lats[9500] / 1000, lats[9900] / 1000);
        }

        // ══════════════════════════════════════════════════════════════
        //  测试 3: 高并发 — 热门文档 + 大量用户（真实场景）
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n[4/5] 高并发真实场景（200 线程 × 10 秒）");
        System.out.println("─".repeat(70));
        System.out.println("  场景: 100 个热门文档, 500 个活跃用户, minimize_latency + SDK cache");
        {
            int HOT_DOCS = 100;
            int ACTIVE_USERS = 500;
            int THREADS = 200;
            int DURATION_SEC = 10;

            // 热身 dispatch cache: 每个热门文档查一次
            for (int i = 0; i < HOT_DOCS; i++) {
                client.check("document", "doc-" + i, "view", "user-0");
            }
            Thread.sleep(500);

            var pool = Executors.newFixedThreadPool(THREADS);
            var running = new AtomicBoolean(true);
            var ops = new LongAdder();
            var errs = new LongAdder();
            long deadline = System.currentTimeMillis() + DURATION_SEC * 1000L;
            ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

            var futures = new ArrayList<Future<?>>();
            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit(() -> {
                    var rng = ThreadLocalRandom.current();
                    while (running.get() && System.currentTimeMillis() < deadline) {
                        try {
                            int docId = rng.nextInt(HOT_DOCS);
                            int userId = rng.nextInt(ACTIVE_USERS);
                            long s = System.nanoTime();
                            client.check("document", "doc-" + docId, "view", "user-" + userId);
                            latencies.add((System.nanoTime() - s) / 1000); // µs
                            ops.increment();
                        } catch (Exception e) {
                            errs.increment();
                        }
                    }
                }));
            }
            for (var f : futures) f.get();
            running.set(false);
            pool.shutdown();

            long totalOps = ops.sum();
            double qps = totalOps / (double) DURATION_SEC;

            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            int n = sorted.size();

            System.out.printf("  总操作: %,d | QPS: %,.0f | 错误: %d%n", totalOps, qps, errs.sum());
            System.out.printf("  P50: %,dµs | P95: %,dµs | P99: %,dµs | Max: %,dµs%n",
                    sorted.get((int)(n*0.50)), sorted.get((int)(n*0.95)),
                    sorted.get((int)(n*0.99)), sorted.get(n-1));

            var m = client.metrics().snapshot();
            System.out.printf("  SDK 缓存命中率: %.1f%% | 命中: %,d | 未命中: %,d | 合并: %,d%n",
                    m.cacheHitRate()*100, m.cacheHits(), m.cacheMisses(), m.coalescedRequests());
        }

        // ══════════════════════════════════════════════════════════════
        //  测试 4: 极端并发 — 500 线程
        // ══════════════════════════════════════════════════════════════
        System.out.println("\n[5/5] 极端并发（500 线程 × 10 秒）");
        System.out.println("─".repeat(70));
        {
            int THREADS = 500;
            int DURATION_SEC = 10;
            int HOT_DOCS = 100;
            int ACTIVE_USERS = 500;

            var pool = Executors.newFixedThreadPool(THREADS);
            var ops = new LongAdder();
            var errs = new LongAdder();
            long deadline = System.currentTimeMillis() + DURATION_SEC * 1000L;
            ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

            var futures = new ArrayList<Future<?>>();
            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit(() -> {
                    var rng = ThreadLocalRandom.current();
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            long s = System.nanoTime();
                            client.check("document", "doc-" + rng.nextInt(HOT_DOCS), "view",
                                    "user-" + rng.nextInt(ACTIVE_USERS));
                            latencies.add((System.nanoTime() - s) / 1000);
                            ops.increment();
                        } catch (Exception e) {
                            errs.increment();
                        }
                    }
                }));
            }
            for (var f : futures) f.get();
            pool.shutdown();

            long totalOps = ops.sum();
            double qps = totalOps / (double) DURATION_SEC;

            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            int n = sorted.size();

            System.out.printf("  总操作: %,d | QPS: %,.0f | 错误: %d%n", totalOps, qps, errs.sum());
            if (n > 0) {
                System.out.printf("  P50: %,dµs | P95: %,dµs | P99: %,dµs%n",
                        sorted.get((int)(n*0.50)), sorted.get((int)(n*0.95)),
                        sorted.get((int)(n*0.99)));
            }
            var m = client.metrics().snapshot();
            System.out.printf("  SDK 缓存命中率: %.1f%% | 合并: %,d%n",
                    m.cacheHitRate()*100, m.coalescedRequests());
        }

        client.close();
        clientNocache.close();
        System.out.println("\n完成。");
    }

    static void seed(AuthCsesClient client) throws Exception {
        // space + org
        client.on("organization").resource("bench-org").grant("admin").to("org-admin");
        client.on("space").resource("bench-space").grant("org").toSubjects("organization:bench-org");
        client.on("space").resource("bench-space").grant("owner").to("space-owner");
        for (int u = 0; u < 100; u++) {
            client.grant("space", "bench-space", "member", "user-" + u);
        }

        // 20 级嵌套文件夹
        for (int d = 1; d <= 20; d++) {
            String fid = "deep-folder-" + d;
            client.on("folder").resource(fid).grant("space").toSubjects("space:bench-space");
            for (int a = 1; a < d; a++) {
                client.on("folder").resource(fid).grant("ancestor").toSubjects("folder:deep-folder-" + a);
            }
            client.grant("folder", fid, "editor", "folder-editor-" + d);
            client.grant("folder", fid, "viewer", "folder-viewer-" + d);
        }

        // 100 个文档在最深层文件夹
        for (int i = 0; i < 100; i++) {
            String docId = "doc-" + i;
            client.on("document").resource(docId).grant("folder").toSubjects("folder:deep-folder-20");
            client.on("document").resource(docId).grant("space").toSubjects("space:bench-space");
            client.grant("document", docId, "owner", "user-" + (i % 500));
            for (int e = 0; e < 3; e++) {
                client.grant("document", docId, "editor", "user-" + ((i * 3 + e + 100) % 500));
            }
            for (int v = 0; v < 5; v++) {
                client.grant("document", docId, "viewer", "user-" + ((i * 5 + v + 200) % 500));
            }
        }
    }
}
