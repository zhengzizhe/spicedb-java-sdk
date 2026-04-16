package com.authx.clustertest.matrix;

import org.HdrHistogram.Histogram;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B-class matrix — same scenario shape as {@link SdkMatrix} but driven
 * against a REAL SpiceDB cluster via gRPC. Expects:
 *  - cluster running (docker compose up)
 *  - schema loaded (schema-v2.zed)
 *  - WORKING_SET documents pre-granted to WORKING_SET users
 */
public final class RealMatrix {
    private static final int WORKING_SET = Integer.parseInt(
            System.getenv().getOrDefault("WORKING_SET", "10000"));    // 10k default, override via env
    private static final long CACHE_MAX_SIZE = Long.parseLong(
            System.getenv().getOrDefault("CACHE_MAX_SIZE", "1000000")); // 1M default
    private static final String RESOURCE_TYPE = "document";
    private static final String RELATION = "viewer";
    private static final String PERMISSION = "view";

    public static List<MatrixCell> runAll(String[] targets, String key,
                                          long perCellDurationMs) throws InterruptedException {
        return runAll(targets, key, perCellDurationMs, c -> {});
    }

    public static List<MatrixCell> runAll(String[] targets, String key,
                                          long perCellDurationMs,
                                          java.util.function.Consumer<MatrixCell> sink) throws InterruptedException {
        var raw = new ArrayList<MatrixCell>();
        var results = new java.util.AbstractList<MatrixCell>() {
            @Override public boolean add(MatrixCell c) {
                raw.add(c);
                System.out.println("[RealMatrix] ✓ " + c.name() + "  TPS=" + Math.round(c.tps()) +
                        "  p50=" + (c.p50us()/1000.0) + "ms  p99=" + (c.p99us()/1000.0) + "ms  err=" + c.errors());
                try { sink.accept(c); } catch (Exception e) { System.err.println("[RealMatrix] sink failed: " + e); }
                return true;
            }
            @Override public MatrixCell get(int i) { return raw.get(i); }
            @Override public int size() { return raw.size(); }
        };

        // Build a temp client to prime data into real SpiceDB
        var prime = RealMatrixClient.create(targets, key, false, false, 1000, Duration.ofMinutes(10));
        try {
            // Skip if last item was already primed
            if (prime.check(RESOURCE_TYPE, "real-" + (WORKING_SET-1), PERMISSION, "u-real-" + (WORKING_SET-1))) {
                System.out.println("[RealMatrix] Working set already primed, skipping.");
            } else {
                System.out.println("[RealMatrix] Priming " + WORKING_SET + " grants into real SpiceDB...");
                int parallel = 32;
                var pool = Executors.newFixedThreadPool(parallel);
                var latch = new java.util.concurrent.CountDownLatch(WORKING_SET);
                for (int i = 0; i < WORKING_SET; i++) {
                    final int idx = i;
                    pool.submit(() -> {
                        try { prime.grant(RESOURCE_TYPE, "real-" + idx, RELATION, "u-real-" + idx); }
                        catch (Exception ignored) { }
                        finally { latch.countDown(); }
                    });
                }
                latch.await(5, TimeUnit.MINUTES);
                pool.shutdown();
                System.out.println("[RealMatrix] Prime done.");
            }
        } finally { prime.close(); }

        // ═══ 1. 缓存命中 vs 未命中 ═══
        results.add(realCache(targets, key, "B1A-纯命中",   perCellDurationMs, 1.00));
        results.add(realCache(targets, key, "B1B-95%命中",  perCellDurationMs, 0.95));
        results.add(realCache(targets, key, "B1C-50%命中",  perCellDurationMs, 0.50));
        results.add(realCache(targets, key, "B1D-10%命中",  perCellDurationMs, 0.10));
        results.add(realCache(targets, key, "B1E-纯未命中", perCellDurationMs, 0.00));

        // ═══ 2. QPS 阶梯（适合真实集群的范围） ═══
        int[] qpsTargets = {100, 500, 1_000, 5_000, 10_000, 50_000};
        for (int qps : qpsTargets) {
            results.add(realQps(targets, key, "B2-" + qps + "qps", perCellDurationMs, qps));
        }

        // ═══ 3. 读写混合 ═══
        double[] writeRatios = {0.0, 0.05, 0.20, 0.50, 1.00};
        for (double wr : writeRatios) {
            results.add(realMix(targets, key, "B3-write" + (int)(wr*100) + "%", perCellDurationMs, wr));
        }

        // ═══ 4. Cache on/off 对比（真实场景最有价值） ═══
        results.add(realCache(targets, key, "B4A-Cache关闭", perCellDurationMs, 1.00, false));
        results.add(realCache(targets, key, "B4B-Cache开启", perCellDurationMs, 1.00, true));

        // ═══ 5. 文件夹祖先继承深度 (真实 ancestor 链路) ═══
        int[] depths = {0, 3, 5, 10, 20};
        for (int d : depths) {
            results.add(realFolderDepth(targets, key, "B5-folder-depth" + d, perCellDurationMs, d, false));
        }
        // Cache-off variant — show pure SpiceDB cost growth with depth
        for (int d : depths) {
            results.add(realFolderDepth(targets, key, "B5-depth" + d + "-noCache", perCellDurationMs, d, true));
        }

        // ═══ 6. 协作者路径（user→group→space→folder→document） ═══
        results.add(realCollabPath(targets, key, "B6A-direct",        perCellDurationMs, CollabPath.DIRECT));
        results.add(realCollabPath(targets, key, "B6B-via-group",     perCellDurationMs, CollabPath.GROUP));
        results.add(realCollabPath(targets, key, "B6C-via-space",     perCellDurationMs, CollabPath.SPACE));
        results.add(realCollabPath(targets, key, "B6D-via-dept",      perCellDurationMs, CollabPath.DEPT));
        results.add(realCollabPath(targets, key, "B6E-full-chain",    perCellDurationMs, CollabPath.FULL));

        // ═══ 7. QPS × 深度 (拐点观察) ═══
        int[] depthQpsTargets = {1_000, 5_000, 10_000};
        for (int d : new int[]{0, 5, 20}) {
            for (int qps : depthQpsTargets) {
                results.add(realDepthQps(targets, key, "B7-d" + d + "-" + qps + "qps",
                        perCellDurationMs, d, qps));
            }
        }

        // ═══ 8. 一致性级别成本（cache off, 直击 SpiceDB+CRDB） ═══
        results.add(realConsistency(targets, key, "B8A-minimizeLatency", perCellDurationMs,
                com.authx.sdk.policy.ReadConsistency.minimizeLatency()));
        results.add(realConsistency(targets, key, "B8B-session", perCellDurationMs,
                com.authx.sdk.policy.ReadConsistency.session()));
        results.add(realConsistency(targets, key, "B8C-strong", perCellDurationMs,
                com.authx.sdk.policy.ReadConsistency.strong()));

        return results;
    }

    private enum CollabPath { DIRECT, GROUP, SPACE, DEPT, FULL }

    private static MatrixCell realCache(String[] targets, String key, String name,
                                         long durationMs, double hitRate) throws InterruptedException {
        return realCache(targets, key, name, durationMs, hitRate, true);
    }

    private static MatrixCell realCache(String[] targets, String key, String name,
                                         long durationMs, double hitRate,
                                         boolean cacheEnabled) throws InterruptedException {
        var client = RealMatrixClient.create(targets, key, cacheEnabled, false, CACHE_MAX_SIZE, Duration.ofMinutes(30));
        try {
            // Warm cache to match target hit rate by pre-touching the primed set
            if (cacheEnabled && hitRate > 0) {
                int warmupThreads = 32;
                var pool = Executors.newFixedThreadPool(warmupThreads);
                var latch = new java.util.concurrent.CountDownLatch(WORKING_SET);
                for (int i = 0; i < WORKING_SET; i++) {
                    final int idx = i;
                    pool.submit(() -> {
                        try { client.check(RESOURCE_TYPE, "real-" + idx, PERMISSION, "u-real-" + idx); }
                        catch (Exception ignored) { }
                        finally { latch.countDown(); }
                    });
                }
                latch.await(2, TimeUnit.MINUTES);
                pool.shutdown();
            }

            return timedRun(name, "READ", String.format("hr=%.0f%%", hitRate*100), hitRate, durationMs,
                    rng -> {
                        if (rng.nextDouble() < hitRate) {
                            int idx = rng.nextInt(WORKING_SET);
                            client.check(RESOURCE_TYPE, "real-" + idx, PERMISSION, "u-real-" + idx);
                        } else {
                            int idx = rng.nextInt(1_000_000);
                            client.check(RESOURCE_TYPE, "fresh-" + idx, PERMISSION, "u-fresh-" + idx);
                        }
                    },
                    client);
        } finally { client.close(); }
    }

    private static MatrixCell realQps(String[] targets, String key, String name,
                                       long durationMs, int targetQps) throws InterruptedException {
        var client = RealMatrixClient.create(targets, key, true, false, CACHE_MAX_SIZE, Duration.ofMinutes(30));
        try {
            // Warm
            int warmupThreads = 32;
            var pool = Executors.newFixedThreadPool(warmupThreads);
            var latch = new java.util.concurrent.CountDownLatch(WORKING_SET);
            for (int i = 0; i < WORKING_SET; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try { client.check(RESOURCE_TYPE, "real-" + idx, PERMISSION, "u-real-" + idx); }
                    catch (Exception ignored) { }
                    finally { latch.countDown(); }
                });
            }
            latch.await(2, TimeUnit.MINUTES);
            pool.shutdown();

            return qpsTimedRun(name, "QPS-TARGET", "target=" + targetQps, 0.95, targetQps, durationMs,
                    rng -> {
                        int idx = rng.nextInt(WORKING_SET);
                        client.check(RESOURCE_TYPE, "real-" + idx, PERMISSION, "u-real-" + idx);
                    },
                    client);
        } finally { client.close(); }
    }

    private static MatrixCell realMix(String[] targets, String key, String name,
                                       long durationMs, double writeRatio) throws InterruptedException {
        var client = RealMatrixClient.create(targets, key, true, false, CACHE_MAX_SIZE, Duration.ofMinutes(30));
        try {
            // Warm
            int warmupThreads = 32;
            var pool = Executors.newFixedThreadPool(warmupThreads);
            var latch = new java.util.concurrent.CountDownLatch(WORKING_SET);
            for (int i = 0; i < WORKING_SET; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try { client.check(RESOURCE_TYPE, "real-" + idx, PERMISSION, "u-real-" + idx); }
                    catch (Exception ignored) { }
                    finally { latch.countDown(); }
                });
            }
            latch.await(2, TimeUnit.MINUTES);
            pool.shutdown();

            return timedRun(name,
                    writeRatio == 0 ? "READ" : (writeRatio == 1.0 ? "WRITE" : "MIXED"),
                    "writeRatio=" + (int)(writeRatio*100) + "%", 1-writeRatio, durationMs,
                    rng -> {
                        int idx = rng.nextInt(WORKING_SET);
                        if (rng.nextDouble() < writeRatio) {
                            client.grant(RESOURCE_TYPE, "real-" + idx, RELATION, "u-new-" + rng.nextInt(10_000));
                        } else {
                            client.check(RESOURCE_TYPE, "real-" + idx, PERMISSION, "u-real-" + idx);
                        }
                    },
                    client);
        } finally { client.close(); }
    }

    // ── Inline timed runners (avoid dependency coupling) ──

    private static MatrixCell timedRun(String name, String workload, String distribution,
                                        double hitRate, long durationMs,
                                        java.util.function.Consumer<Random> op,
                                        RealMatrixClient client) throws InterruptedException {
        int threads = 100;
        var ops = new AtomicLong();
        var errors = new AtomicLong();
        var errorsByType = new java.util.concurrent.ConcurrentHashMap<String, Long>();
        var localHists = new java.util.concurrent.ConcurrentLinkedQueue<Histogram>();
        long deadline = System.nanoTime() + durationMs * 1_000_000L;
        var pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final long seed = i * 1_000_003L;
            pool.submit(() -> {
                var localHist = new Histogram(60_000_000_000L, 3);
                localHists.add(localHist);
                var rng = new Random(seed);
                while (System.nanoTime() < deadline) {
                    long t0 = System.nanoTime();
                    try {
                        op.accept(rng);
                        long us = (System.nanoTime() - t0) / 1000;
                        localHist.recordValue(Math.min(us, 60_000_000));
                        ops.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        errorsByType.merge(e.getClass().getSimpleName(), 1L, Long::sum);
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(durationMs + 30_000, TimeUnit.MILLISECONDS);

        var hist = new Histogram(60_000_000_000L, 3);
        for (var h : localHists) hist.add(h);
        return cellFrom(name, workload, distribution, hitRate, threads, durationMs,
                ops.get(), hist, errors.get(), errorsByType, client);
    }

    private static MatrixCell qpsTimedRun(String name, String workload, String distribution,
                                           double hitRate, int targetQps, long durationMs,
                                           java.util.function.Consumer<Random> op,
                                           RealMatrixClient client) throws InterruptedException {
        int threads = Math.max(8, Math.min(targetQps / 10, 200));
        var ops = new AtomicLong();
        var errors = new AtomicLong();
        var errorsByType = new java.util.concurrent.ConcurrentHashMap<String, Long>();
        var localHists = new java.util.concurrent.ConcurrentLinkedQueue<Histogram>();
        var threadLocalHist = ThreadLocal.withInitial(() -> {
            var h = new Histogram(60_000_000_000L, 3);
            localHists.add(h);
            return h;
        });
        long startNs = System.nanoTime();
        long deadlineNs = startNs + durationMs * 1_000_000L;
        long intervalNs = Math.max(1, 1_000_000_000L / targetQps);

        var pool = Executors.newFixedThreadPool(threads);
        long scheduled = startNs;
        while (scheduled < deadlineNs) {
            final long thisScheduled = scheduled;
            long now = System.nanoTime();
            if (thisScheduled > now) {
                long sleepNs = thisScheduled - now;
                if (sleepNs > 1_000_000) Thread.sleep(sleepNs/1_000_000, (int)(sleepNs%1_000_000));
                else while (System.nanoTime() < thisScheduled) Thread.onSpinWait();
            }
            pool.submit(() -> {
                long actual = System.nanoTime();
                try {
                    op.accept(new Random(actual));
                    long responseUs = (System.nanoTime() - thisScheduled) / 1000;
                    threadLocalHist.get().recordValue(Math.min(responseUs, 60_000_000));
                    ops.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    errorsByType.merge(e.getClass().getSimpleName(), 1L, Long::sum);
                }
            });
            scheduled += intervalNs;
        }
        pool.shutdown();
        pool.awaitTermination(durationMs + 30_000, TimeUnit.MILLISECONDS);

        var hist = new Histogram(60_000_000_000L, 3);
        for (var h : localHists) hist.add(h);
        return cellFrom(name, workload, distribution, hitRate, targetQps, durationMs,
                ops.get(), hist, errors.get(), errorsByType, client);
    }

    private static MatrixCell cellFrom(String name, String workload, String distribution,
                                        double hitRate, int concurrency, long durationMs,
                                        long ops, Histogram hist, long errors,
                                        java.util.Map<String, Long> errorsByType,
                                        RealMatrixClient client) {
        double tps = ops * 1000.0 / durationMs;
        var stats = client.cacheStats();
        long total = stats.hitCount() + stats.missCount();
        double actualHit = total == 0 ? 0.0 : (double) stats.hitCount() / total;

        var buckets = new ArrayList<long[]>();
        double[] pcts = {0, 50, 75, 90, 95, 99, 99.5, 99.9, 99.95, 99.99, 100};
        for (double p : pcts) buckets.add(new long[]{Math.round(p*100), hist.getValueAtPercentile(p)});

        return new MatrixCell(name, workload, distribution, hitRate, concurrency, durationMs,
                ops, tps, actualHit,
                hist.getMinValue(), hist.getValueAtPercentile(50), hist.getValueAtPercentile(90),
                hist.getValueAtPercentile(99), hist.getValueAtPercentile(99.9),
                hist.getValueAtPercentile(99.99), hist.getMaxValue(),
                errors, java.util.Map.copyOf(errorsByType), buckets);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Hierarchy / collaborator scenarios (real schema-v2.zed)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Build folder ancestor chain of {@code depth} levels, place doc at leaf,
     * grant viewer at root. Each check traverses {@code depth} ancestor edges
     * in real SpiceDB.
     */
    private static MatrixCell realFolderDepth(String[] targets, String key, String name,
                                               long durationMs, int depth, boolean cacheOff)
            throws InterruptedException {
        String tag = "d" + depth;
        // Use distinct id-space per scenario so cache state of one doesn't bleed into the next
        String prefix = "fd" + (cacheOff ? "n" : "c") + "-d" + depth + "-";
        int chains = Math.min(WORKING_SET, 2_000);   // 2k chains × depth edges = O(20k)–O(40k) writes

        var prime = RealMatrixClient.create(targets, key, false, false, 1000, Duration.ofMinutes(10));
        try {
            if (prime.check("document", prefix + (chains-1), PERMISSION, "u-fd-" + (chains-1))) {
                System.out.println("[RealMatrix] depth=" + depth + " already primed, skipping.");
            } else {
                System.out.println("[RealMatrix] Building " + chains + " folder chains @ depth=" + depth);
                int parallel = 32;
                var pool = Executors.newFixedThreadPool(parallel);
                var latch = new java.util.concurrent.CountDownLatch(chains);
                for (int c = 0; c < chains; c++) {
                    final int chainIdx = c;
                    pool.submit(() -> {
                        try {
                            for (int lvl = 1; lvl <= depth; lvl++) {
                                for (int anc = 0; anc < lvl; anc++) {
                                    prime.grantSubject("folder", prefix + chainIdx + "-l" + lvl,
                                            "ancestor", "folder:" + prefix + chainIdx + "-l" + anc);
                                }
                            }
                            String leaf = depth == 0 ? prefix + chainIdx + "-l0" : prefix + chainIdx + "-l" + depth;
                            prime.grantSubject("document", prefix + chainIdx, "folder", "folder:" + leaf);
                            prime.grant("folder", prefix + chainIdx + "-l0", "viewer", "u-fd-" + chainIdx);
                        } catch (Exception ignored) { }
                        finally { latch.countDown(); }
                    });
                }
                latch.await(10, TimeUnit.MINUTES);
                pool.shutdown();
            }
        } finally { prime.close(); }

        var client = RealMatrixClient.create(targets, key, !cacheOff, false, CACHE_MAX_SIZE, Duration.ofMinutes(30));
        try {
            return timedRun(name, "READ", "depth=" + depth + (cacheOff ? ",cache=off" : ""),
                    cacheOff ? 0.0 : 1.0, durationMs,
                    rng -> {
                        int idx = rng.nextInt(chains);
                        client.check("document", prefix + idx, PERMISSION, "u-fd-" + idx);
                    },
                    client);
        } finally { client.close(); }
    }

    /**
     * Collaborator path scenarios — exercise different relation paths to
     * the same document. Permission resolution depth and dispatch fan-out
     * differs per path.
     */
    private static MatrixCell realCollabPath(String[] targets, String key, String name,
                                              long durationMs, CollabPath path)
            throws InterruptedException {
        String prefix = "cp-" + path.name().toLowerCase() + "-";
        int n = Math.min(WORKING_SET, 2_000);

        var prime = RealMatrixClient.create(targets, key, false, false, 1000, Duration.ofMinutes(10));
        try {
            if (prime.check("document", prefix + (n-1), PERMISSION, "u-" + prefix + (n-1))) {
                System.out.println("[RealMatrix] " + path + " already primed, skipping.");
            } else {
            System.out.println("[RealMatrix] Building " + n + " collab paths: " + path);
            int parallel = 32;
            var pool = Executors.newFixedThreadPool(parallel);
            var latch = new java.util.concurrent.CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        String user = "u-" + prefix + idx;
                        String doc = prefix + idx;
                        switch (path) {
                            case DIRECT -> {
                                prime.grant("document", doc, "viewer", user);
                            }
                            case GROUP -> {
                                String grp = prefix + "g-" + idx;
                                prime.grant("group", grp, "member", user);
                                prime.grantSubject("document", doc, "viewer",
                                        "group:" + grp + "#member");
                            }
                            case SPACE -> {
                                String sp = prefix + "s-" + idx;
                                String fld = prefix + "f-" + idx;
                                prime.grant("space", sp, "viewer", user);
                                prime.grantSubject("folder", fld, "space", "space:" + sp);
                                prime.grantSubject("document", doc, "folder", "folder:" + fld);
                            }
                            case DEPT -> {
                                String dept = prefix + "d-" + idx;
                                String grp = prefix + "g-" + idx;
                                prime.grant("department", dept, "member", user);
                                prime.grantSubject("group", grp, "member",
                                        "department:" + dept + "#all_members");
                                prime.grantSubject("document", doc, "viewer",
                                        "group:" + grp + "#member");
                            }
                            case FULL -> {
                                // user → dept → group → space → folder(depth 5) → doc
                                String dept = prefix + "d-" + idx;
                                String grp = prefix + "g-" + idx;
                                String sp = prefix + "s-" + idx;
                                prime.grant("department", dept, "member", user);
                                prime.grantSubject("group", grp, "member",
                                        "department:" + dept + "#all_members");
                                prime.grantSubject("space", sp, "member",
                                        "group:" + grp + "#member");
                                // folder chain depth 5, doc at leaf
                                int d = 5;
                                for (int lvl = 1; lvl <= d; lvl++) {
                                    for (int anc = 0; anc < lvl; anc++) {
                                        prime.grantSubject("folder", prefix + "f-" + idx + "-l" + lvl,
                                                "ancestor", "folder:" + prefix + "f-" + idx + "-l" + anc);
                                    }
                                }
                                prime.grantSubject("folder", prefix + "f-" + idx + "-l0", "space",
                                        "space:" + sp);
                                prime.grantSubject("document", doc, "folder",
                                        "folder:" + prefix + "f-" + idx + "-l" + d);
                            }
                        }
                    } catch (Exception ignored) { }
                    finally { latch.countDown(); }
                });
            }
            latch.await(10, TimeUnit.MINUTES);
            pool.shutdown();
            }
        } finally { prime.close(); }

        var client = RealMatrixClient.create(targets, key, true, false, CACHE_MAX_SIZE, Duration.ofMinutes(30));
        try {
            return timedRun(name, "READ", "path=" + path, 1.0, durationMs,
                    rng -> {
                        int idx = rng.nextInt(n);
                        client.check("document", prefix + idx, PERMISSION, "u-" + prefix + idx);
                    },
                    client);
        } finally { client.close(); }
    }

    /**
     * QPS × depth crossover — fixed depth, target QPS pacing. Reveals where
     * SpiceDB starts queuing as depth grows.
     */
    private static MatrixCell realDepthQps(String[] targets, String key, String name,
                                            long durationMs, int depth, int targetQps)
            throws InterruptedException {
        String prefix = "dq-d" + depth + "-";
        int chains = Math.min(WORKING_SET, 2_000);

        // Build (or rebuild) chain dataset for this depth
        var prime = RealMatrixClient.create(targets, key, false, false, 1000, Duration.ofMinutes(10));
        try {
            if (prime.check("document", prefix + (chains-1), PERMISSION, "u-dq-" + (chains-1))) {
                System.out.println("[RealMatrix] B7 d=" + depth + " already primed, skipping.");
            } else {
                int parallel = 32;
                var pool = Executors.newFixedThreadPool(parallel);
                var latch = new java.util.concurrent.CountDownLatch(chains);
                for (int c = 0; c < chains; c++) {
                    final int chainIdx = c;
                    pool.submit(() -> {
                        try {
                            for (int lvl = 1; lvl <= depth; lvl++) {
                                for (int anc = 0; anc < lvl; anc++) {
                                    prime.grantSubject("folder", prefix + chainIdx + "-l" + lvl,
                                            "ancestor", "folder:" + prefix + chainIdx + "-l" + anc);
                                }
                            }
                            String leaf = depth == 0 ? prefix + chainIdx + "-l0" : prefix + chainIdx + "-l" + depth;
                            prime.grantSubject("document", prefix + chainIdx, "folder", "folder:" + leaf);
                            prime.grant("folder", prefix + chainIdx + "-l0", "viewer", "u-dq-" + chainIdx);
                        } catch (Exception ignored) { }
                        finally { latch.countDown(); }
                    });
                }
                latch.await(10, TimeUnit.MINUTES);
                pool.shutdown();
            }
        } finally { prime.close(); }

        var client = RealMatrixClient.create(targets, key, true, false, CACHE_MAX_SIZE, Duration.ofMinutes(30));
        try {
            // Warm cache so we measure steady-state pacing, not first-touch penalty
            int warmup = Math.min(chains, 500);
            for (int i = 0; i < warmup; i++) {
                try { client.check("document", prefix + i, PERMISSION, "u-dq-" + i); }
                catch (Exception ignored) { }
            }
            return qpsTimedRun(name, "QPS-TARGET",
                    "depth=" + depth + ",target=" + targetQps, 0.95, targetQps, durationMs,
                    rng -> {
                        int idx = rng.nextInt(chains);
                        client.check("document", prefix + idx, PERMISSION, "u-dq-" + idx);
                    },
                    client);
        } finally { client.close(); }
    }

    /**
     * Same workload across the three consistency levels with cache OFF, so the
     * cost difference is the actual SpiceDB+CRDB read path (snapshot lookup vs
     * primary read). Cache on would mask the difference entirely.
     */
    private static MatrixCell realConsistency(String[] targets, String key, String name,
                                               long durationMs,
                                               com.authx.sdk.policy.ReadConsistency consistency)
            throws InterruptedException {
        var client = RealMatrixClient.create(targets, key,
                false /* cache off */, false, 1000, Duration.ofMinutes(30), consistency);
        try {
            return timedRun(name, "READ", "consistency=" + consistency.getClass().getSimpleName(),
                    0.0, durationMs,
                    rng -> {
                        int idx = rng.nextInt(WORKING_SET);
                        client.check(RESOURCE_TYPE, "real-" + idx, PERMISSION, "u-real-" + idx);
                    },
                    client);
        } finally { client.close(); }
    }

    private RealMatrix() {}
}
