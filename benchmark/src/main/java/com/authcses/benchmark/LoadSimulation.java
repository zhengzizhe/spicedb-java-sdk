package com.authcses.benchmark;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.model.Consistency;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 高并发压测模拟 — 打真实 SpiceDB。
 *
 * 模拟飞书文档系统：
 * - 组织 → 空间 → 文件夹(嵌套) → 文档
 * - 用户通过部门/组/直接授权获得权限
 * - 读写比 90:10，Zipfian 热点分布
 *
 * 用法: ./gradlew :benchmark:run
 * 可选参数: -Dbench.users=2000 -Dbench.docs=5000 -Dbench.duration=60 -Dbench.threads=200
 */
public class LoadSimulation {

    // ── 配置 ──
    static final int NUM_USERS    = Integer.getInteger("bench.users", 2000);
    static final int NUM_DOCS     = Integer.getInteger("bench.docs", 5000);
    static final int NUM_FOLDERS  = NUM_DOCS / 5;   // 1000
    static final int NUM_SPACES   = 20;
    static final int NUM_ORGS     = 3;
    static final int NUM_DEPTS    = 30;
    static final int NUM_GROUPS   = 50;
    static final int DURATION_SEC = Integer.getInteger("bench.duration", 60);
    static final int THREADS      = Integer.getInteger("bench.threads", 200);

    // ── 运行时状态 ──
    static AuthCsesClient client;
    static final Random RNG = ThreadLocalRandom.current();

    // 已知授权（用于正确性校验）
    static final ConcurrentHashMap<String, Boolean> knownGrants = new ConcurrentHashMap<>();

    // ── 指标 ──
    static final LongAdder totalOps       = new LongAdder();
    static final LongAdder checkOps       = new LongAdder();
    static final LongAdder writeOps       = new LongAdder();
    static final LongAdder lookupOps      = new LongAdder();
    static final LongAdder errors         = new LongAdder();
    static final LongAdder correctChecks  = new LongAdder();
    static final LongAdder wrongChecks    = new LongAdder();

    // 延迟跟踪 (纳秒)
    static final ConcurrentLinkedQueue<long[]> latencyWindows = new ConcurrentLinkedQueue<>();

    // 每窗口指标
    record WindowMetrics(long windowStart, long ops, long errs, long checkCnt, long writeCnt,
                         double p50us, double p95us, double p99us, double maxUs) {}
    static final List<WindowMetrics> windows = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║        SpiceDB Java SDK — 高并发压测模拟                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.printf("  用户: %,d | 文档: %,d | 文件夹: %,d | 空间: %d%n",
                NUM_USERS, NUM_DOCS, NUM_FOLDERS, NUM_SPACES);
        System.out.printf("  线程: %d | 持续: %ds | SpiceDB: localhost:50051%n%n", THREADS, DURATION_SEC);

        // ── 1. 连接（生产级：双节点 SpiceDB + 全缓存）──
        client = AuthCsesClient.builder()
                .connection(c -> c
                        .targets("localhost:50051", "localhost:50052")  // 双节点负载均衡
                        .presharedKey("testkey")
                        .requestTimeout(Duration.ofSeconds(10))
                        .keepAliveTime(Duration.ofSeconds(30)))
                .cache(c -> c
                        .enabled(true)
                        .maxSize(500_000)
                        .watchInvalidation(false))
                .features(f -> f
                        .coalescing(true)
                        .shutdownHook(true))
                .extend(e -> e
                        .policies(com.authcses.sdk.policy.PolicyRegistry.builder()
                                .defaultPolicy(com.authcses.sdk.policy.ResourcePolicy.builder()
                                        .cache(com.authcses.sdk.policy.CachePolicy.builder()
                                                .enabled(true)
                                                .ttl(Duration.ofSeconds(5))
                                                .build())
                                        .readConsistency(com.authcses.sdk.policy.ReadConsistency.minimizeLatency())
                                        .retry(com.authcses.sdk.policy.RetryPolicy.defaults())
                                        .circuitBreaker(com.authcses.sdk.policy.CircuitBreakerPolicy.defaults())
                                        .build())
                                .build()))
                .build();

        System.out.println("[1/4] SpiceDB 已连接，SDK 初始化完成");

        // ── 2. 数据播种 ──
        long seedStart = System.currentTimeMillis();
        seedData();
        long seedMs = System.currentTimeMillis() - seedStart;
        System.out.printf("[2/4] 数据播种完成 — %,d 条关系, 耗时 %.1fs%n%n",
                knownGrants.size(), seedMs / 1000.0);

        // ── 3. 压测 ──
        System.out.printf("[3/4] 开始压测 — %d 线程, %d 秒%n", THREADS, DURATION_SEC);
        System.out.println("─".repeat(62));
        System.out.printf("%-8s %8s %8s %8s %8s %8s %8s%n",
                "时间", "QPS", "错误", "P50(ms)", "P95(ms)", "P99(ms)", "Max(ms)");
        System.out.println("─".repeat(62));

        runLoadTest();

        System.out.println("─".repeat(62));
        System.out.println();

        // ── 4. 报告 ──
        printReport();

        client.close();
    }

    // ================================================================
    //  数据播种
    // ================================================================

    static void seedData() throws Exception {
        System.out.println("  播种组织结构...");

        // 组织
        for (int i = 0; i < NUM_ORGS; i++) {
            String orgId = "org-" + i;
            // 每个组织 5 个 admin
            for (int u = 0; u < 5; u++) {
                client.grant("organization", orgId, "admin", "user-" + (i * 5 + u));
                knownGrants.put("organization:" + orgId + "#manage@user-" + (i * 5 + u), true);
            }
            // 每个组织一批 member
            for (int u = 0; u < Math.min(200, NUM_USERS / NUM_ORGS); u++) {
                int userId = i * (NUM_USERS / NUM_ORGS) + u;
                client.grant("organization", orgId, "member", "user-" + userId);
            }
        }

        System.out.println("  播种部门...");
        // 部门
        for (int d = 0; d < NUM_DEPTS; d++) {
            String deptId = "dept-" + d;
            int membersPerDept = NUM_USERS / NUM_DEPTS;
            for (int u = 0; u < membersPerDept && u < 100; u++) {
                int userId = d * membersPerDept + u;
                if (userId < NUM_USERS) {
                    client.grant("department", deptId, "member", "user-" + userId);
                }
            }
        }

        System.out.println("  播种空间 + 文件夹...");
        // 空间
        for (int s = 0; s < NUM_SPACES; s++) {
            String spaceId = "space-" + s;
            String orgId = "org-" + (s % NUM_ORGS);
            client.on("space").resource(spaceId).grant("org").toSubjects("organization:" + orgId);
            client.grant("space", spaceId, "owner", "user-" + s);
            knownGrants.put("space:" + spaceId + "#manage@user-" + s, true);

            // 每个空间一些 member
            for (int u = 0; u < 50; u++) {
                int userId = (s * 50 + u) % NUM_USERS;
                client.grant("space", spaceId, "member", "user-" + userId);
                knownGrants.put("space:" + spaceId + "#edit@user-" + userId, true);
            }
        }

        // 文件夹（带父子关系）
        for (int f = 0; f < NUM_FOLDERS; f++) {
            String folderId = "folder-" + f;
            String spaceId = "space-" + (f % NUM_SPACES);
            client.on("folder").resource(folderId).grant("space").toSubjects("space:" + spaceId);

            // V2 ancestor 模型：写入所有祖先（不是只写 parent）
            // 模拟 5 级嵌套深度：每 200 个 folder 为一组，组内链式嵌套
            int groupSize = NUM_FOLDERS / 5; // 每组 200 个
            int posInGroup = f % groupSize;
            if (posInGroup > 0) {
                int groupStart = f - posInGroup;
                for (int anc = groupStart; anc < f && anc < groupStart + Math.min(posInGroup, 5); anc++) {
                    client.on("folder").resource(folderId).grant("ancestor")
                            .toSubjects("folder:folder-" + anc);
                }
            }

            // 直接授权
            for (int u = 0; u < 5; u++) {
                int userId = (f * 7 + u) % NUM_USERS;
                client.grant("folder", folderId, "editor", "user-" + userId);
                knownGrants.put("folder:" + folderId + "#edit@user-" + userId, true);
            }
        }

        System.out.println("  播种文档...");
        // 文档（并行播种加速）
        int batchSize = 500;
        ExecutorService seedPool = Executors.newFixedThreadPool(20);
        List<Future<?>> futures = new ArrayList<>();

        for (int batch = 0; batch < NUM_DOCS; batch += batchSize) {
            int start = batch;
            int end = Math.min(batch + batchSize, NUM_DOCS);
            futures.add(seedPool.submit(() -> {
                for (int d = start; d < end; d++) {
                    try {
                        String docId = "doc-" + d;
                        String folderId = "folder-" + (d % NUM_FOLDERS);
                        client.on("document").resource(docId).grant("folder")
                                .toSubjects("folder:" + folderId);

                        // 每个文档一些直接权限
                        int ownerId = d % NUM_USERS;
                        client.grant("document", docId, "owner", "user-" + ownerId);
                        knownGrants.put("document:" + docId + "#manage@user-" + ownerId, true);
                        knownGrants.put("document:" + docId + "#view@user-" + ownerId, true);

                        for (int u = 0; u < 3; u++) {
                            int editorId = (d * 3 + u + 100) % NUM_USERS;
                            client.grant("document", docId, "editor", "user-" + editorId);
                            knownGrants.put("document:" + docId + "#edit@user-" + editorId, true);
                            knownGrants.put("document:" + docId + "#view@user-" + editorId, true);
                        }
                        for (int u = 0; u < 5; u++) {
                            int viewerId = (d * 5 + u + 500) % NUM_USERS;
                            client.grant("document", docId, "viewer", "user-" + viewerId);
                            knownGrants.put("document:" + docId + "#view@user-" + viewerId, true);
                        }
                    } catch (Exception e) {
                        // Seed errors are non-fatal
                    }
                }
            }));
        }
        seedPool.shutdown();
        for (var f : futures) f.get();
        seedPool.awaitTermination(5, TimeUnit.MINUTES);
    }

    // ================================================================
    //  压测主循环
    // ================================================================

    static void runLoadTest() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicBoolean running = new AtomicBoolean(true);
        long testStart = System.currentTimeMillis();
        long testEnd = testStart + DURATION_SEC * 1000L;

        // 窗口指标收集线程
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();
        AtomicLong windowOps = new AtomicLong();
        AtomicLong windowErrs = new AtomicLong();
        AtomicLong windowChecks = new AtomicLong();
        AtomicLong windowWrites = new AtomicLong();
        ConcurrentLinkedQueue<Long> windowLatencies = new ConcurrentLinkedQueue<>();

        reporter.scheduleAtFixedRate(() -> {
            long ops = windowOps.getAndSet(0);
            long errs = windowErrs.getAndSet(0);
            long chk = windowChecks.getAndSet(0);
            long wr = windowWrites.getAndSet(0);

            List<Long> lats = new ArrayList<>();
            Long l;
            while ((l = windowLatencies.poll()) != null) lats.add(l);
            Collections.sort(lats);

            double p50 = percentile(lats, 50) / 1_000.0;
            double p95 = percentile(lats, 95) / 1_000.0;
            double p99 = percentile(lats, 99) / 1_000.0;
            double max = lats.isEmpty() ? 0 : lats.getLast() / 1_000.0;
            long elapsed = (System.currentTimeMillis() - testStart) / 1000;

            System.out.printf("%-8s %,8d %8d %8.1f %8.1f %8.1f %8.1f%n",
                    elapsed + "s", ops, errs, p50, p95, p99, max);

            windows.add(new WindowMetrics(elapsed, ops, errs, chk, wr, p50, p95, p99, max));
        }, 5, 5, TimeUnit.SECONDS);

        // 工作线程
        List<Future<?>> workers = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            workers.add(pool.submit(() -> {
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                while (running.get() && System.currentTimeMillis() < testEnd) {
                    long start = System.nanoTime();
                    boolean isError = false;
                    String opType = "check";

                    try {
                        double roll = rng.nextDouble();

                        if (roll < 0.50) {
                            // 50% — check view (最常见)
                            doCheckView(rng);
                            windowChecks.incrementAndGet();
                        } else if (roll < 0.70) {
                            // 20% — check edit
                            doCheckEdit(rng);
                            windowChecks.incrementAndGet();
                        } else if (roll < 0.80) {
                            // 10% — check manage
                            doCheckManage(rng);
                            windowChecks.incrementAndGet();
                        } else if (roll < 0.85) {
                            // 5% — checkAll (多权限)
                            doCheckAll(rng);
                            windowChecks.incrementAndGet();
                        } else if (roll < 0.90) {
                            // 5% — lookup subjects
                            opType = "lookup";
                            doLookupSubjects(rng);
                            lookupOps.increment();
                        } else if (roll < 0.95) {
                            // 5% — grant
                            opType = "write";
                            doGrant(rng);
                            writeOps.increment();
                            windowWrites.incrementAndGet();
                        } else {
                            // 5% — revoke
                            opType = "write";
                            doRevoke(rng);
                            writeOps.increment();
                            windowWrites.incrementAndGet();
                        }
                    } catch (Exception e) {
                        isError = true;
                        errors.increment();
                    }

                    long elapsed = System.nanoTime() - start;
                    windowLatencies.add(elapsed / 1000); // 微秒
                    totalOps.increment();
                    if (opType.equals("check")) checkOps.increment();
                    windowOps.incrementAndGet();
                    if (isError) windowErrs.incrementAndGet();
                }
            }));
        }

        // 等待结束
        for (var f : workers) f.get();
        running.set(false);
        reporter.shutdown();
        reporter.awaitTermination(10, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    // ================================================================
    //  操作实现
    // ================================================================

    static void doCheckView(ThreadLocalRandom rng) {
        int docId = zipfianDoc(rng);
        int userId = rng.nextInt(NUM_USERS);
        boolean result = client.check("document", "doc-" + docId, "view", "user-" + userId);
        // 正确性校验
        Boolean expected = knownGrants.get("document:doc-" + docId + "#view@user-" + userId);
        if (expected != null) {
            if (result == expected) correctChecks.increment(); else wrongChecks.increment();
        }
    }

    static void doCheckEdit(ThreadLocalRandom rng) {
        int docId = zipfianDoc(rng);
        int userId = rng.nextInt(NUM_USERS);
        client.check("document", "doc-" + docId, "edit", "user-" + userId);
    }

    static void doCheckManage(ThreadLocalRandom rng) {
        int docId = zipfianDoc(rng);
        int userId = rng.nextInt(NUM_USERS);
        client.check("document", "doc-" + docId, "manage", "user-" + userId);
    }

    static void doCheckAll(ThreadLocalRandom rng) {
        int docId = zipfianDoc(rng);
        int userId = rng.nextInt(NUM_USERS);
        client.checkAll("document", "doc-" + docId, "user-" + userId, "view", "edit", "manage");
    }

    static void doLookupSubjects(ThreadLocalRandom rng) {
        int docId = zipfianDoc(rng);
        // 不设 limit — SpiceDB 版本不支持 OptionalConcreteLimit
        client.on("document").resource("doc-" + docId).who().withPermission("view").fetch();
    }

    static void doGrant(ThreadLocalRandom rng) {
        int docId = rng.nextInt(NUM_DOCS);
        int userId = rng.nextInt(NUM_USERS);
        String[] rels = {"viewer", "commenter", "editor"};
        String rel = rels[rng.nextInt(rels.length)];
        client.grant("document", "doc-" + docId, rel, "user-" + userId);
        knownGrants.put("document:doc-" + docId + "#view@user-" + userId, true);
    }

    static void doRevoke(ThreadLocalRandom rng) {
        int docId = rng.nextInt(NUM_DOCS);
        int userId = rng.nextInt(NUM_USERS);
        try {
            client.revoke("document", "doc-" + docId, "viewer", "user-" + userId);
            knownGrants.remove("document:doc-" + docId + "#view@user-" + userId);
        } catch (Exception ignored) {}
    }

    // Zipfian 热点分布：20% 的文档承受 80% 的流量
    static int zipfianDoc(ThreadLocalRandom rng) {
        if (rng.nextDouble() < 0.8) {
            return rng.nextInt(NUM_DOCS / 5); // 热门 20%
        }
        return rng.nextInt(NUM_DOCS);
    }

    // ================================================================
    //  报告
    // ================================================================

    static void printReport() {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                      压 测 报 告                              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        long total = totalOps.sum();
        long errs = errors.sum();
        long checks = checkOps.sum();
        long writes = writeOps.sum();
        long lookups = lookupOps.sum();

        // 总体指标
        System.out.println();
        System.out.println("┌─ 总体指标 ─────────────────────────────────────────────────┐");
        System.out.printf("│  总操作数:     %,15d                              │%n", total);
        System.out.printf("│  总 QPS:       %,15.0f                              │%n", (double) total / DURATION_SEC);
        System.out.printf("│  错误数:       %,15d  (%.2f%%)                     │%n", errs, errs * 100.0 / Math.max(total, 1));
        System.out.printf("│  持续时间:     %15ds                              │%n", DURATION_SEC);
        System.out.printf("│  并发线程:     %15d                              │%n", THREADS);
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        // 操作分布
        System.out.println();
        System.out.println("┌─ 操作分布 ─────────────────────────────────────────────────┐");
        System.out.printf("│  Check:   %,12d  (%5.1f%%)                          │%n", checks, checks * 100.0 / Math.max(total, 1));
        System.out.printf("│  Write:   %,12d  (%5.1f%%)                          │%n", writes, writes * 100.0 / Math.max(total, 1));
        System.out.printf("│  Lookup:  %,12d  (%5.1f%%)                          │%n", lookups, lookups * 100.0 / Math.max(total, 1));
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        // 延迟汇总
        if (!windows.isEmpty()) {
            double avgP50 = windows.stream().mapToDouble(w -> w.p50us).average().orElse(0);
            double avgP95 = windows.stream().mapToDouble(w -> w.p95us).average().orElse(0);
            double avgP99 = windows.stream().mapToDouble(w -> w.p99us).average().orElse(0);
            double maxMax = windows.stream().mapToDouble(w -> w.maxUs).max().orElse(0);
            double avgQps = windows.stream().mapToDouble(w -> w.ops / 5.0).average().orElse(0);
            double peakQps = windows.stream().mapToDouble(w -> w.ops / 5.0).max().orElse(0);

            System.out.println();
            System.out.println("┌─ 延迟统计 (ms) ────────────────────────────────────────────┐");
            System.out.printf("│  P50:     %8.1f ms                                      │%n", avgP50);
            System.out.printf("│  P95:     %8.1f ms                                      │%n", avgP95);
            System.out.printf("│  P99:     %8.1f ms                                      │%n", avgP99);
            System.out.printf("│  Max:     %8.1f ms                                      │%n", maxMax);
            System.out.println("└──────────────────────────────────────────────────────────────┘");

            System.out.println();
            System.out.println("┌─ 吞吐量 ───────────────────────────────────────────────────┐");
            System.out.printf("│  平均 QPS:  %,10.0f                                     │%n", avgQps);
            System.out.printf("│  峰值 QPS:  %,10.0f                                     │%n", peakQps);
            System.out.println("└──────────────────────────────────────────────────────────────┘");
        }

        // SDK 内部指标
        var metrics = client.metrics().snapshot();
        System.out.println();
        System.out.println("┌─ SDK 缓存 ────────────────────────────────────────────────┐");
        System.out.printf("│  缓存命中率:   %8.1f%%                                    │%n", metrics.cacheHitRate() * 100);
        System.out.printf("│  命中:         %,12d                                  │%n", metrics.cacheHits());
        System.out.printf("│  未命中:       %,12d                                  │%n", metrics.cacheMisses());
        System.out.printf("│  驱逐:         %,12d                                  │%n", metrics.cacheEvictions());
        System.out.printf("│  缓存大小:     %,12d                                  │%n", metrics.cacheSize());
        System.out.printf("│  合并请求:     %,12d                                  │%n", metrics.coalescedRequests());
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        System.out.println();
        System.out.println("┌─ SDK 弹性 ────────────────────────────────────────────────┐");
        System.out.printf("│  熔断器状态:   %-10s                                    │%n", metrics.circuitBreakerState());
        System.out.printf("│  总请求:       %,12d                                  │%n", metrics.totalRequests());
        System.out.printf("│  总错误:       %,12d  (%.2f%%)                       │%n",
                metrics.totalErrors(), metrics.errorRate() * 100);
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        // 正确性
        long correct = correctChecks.sum();
        long wrong = wrongChecks.sum();
        long verifiable = correct + wrong;
        System.out.println();
        System.out.println("┌─ 正确性校验 ──────────────────────────────────────────────┐");
        System.out.printf("│  可校验 check: %,12d                                  │%n", verifiable);
        System.out.printf("│  正确:         %,12d                                  │%n", correct);
        System.out.printf("│  错误:         %,12d                                  │%n", wrong);
        System.out.printf("│  正确率:       %8.2f%%                                    │%n",
                verifiable == 0 ? 100.0 : correct * 100.0 / verifiable);
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        // QPS 时序图
        if (windows.size() > 2) {
            System.out.println();
            System.out.println("┌─ QPS 时序 ─────────────────────────────────────────────────┐");
            double maxQps = windows.stream().mapToDouble(w -> w.ops / 5.0).max().orElse(1);
            for (var w : windows) {
                double qps = w.ops / 5.0;
                int barLen = (int) (qps / maxQps * 40);
                System.out.printf("│ %3ds │%s %,.0f%n", w.windowStart, "█".repeat(barLen), qps);
            }
            System.out.println("└──────────────────────────────────────────────────────────────┘");
        }

        System.out.println();
        System.out.println("压测完成。");
    }

    // ================================================================
    //  工具
    // ================================================================

    static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = Math.min((int) (sorted.size() * p / 100.0), sorted.size() - 1);
        return sorted.get(idx);
    }
}
