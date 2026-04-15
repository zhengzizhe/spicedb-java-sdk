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
        var results = new ArrayList<MatrixCell>();

        // Build a temp client to prime data into real SpiceDB
        System.out.println("[RealMatrix] Priming " + WORKING_SET + " grants into real SpiceDB...");
        var prime = RealMatrixClient.create(targets, key, false, false, 1000, Duration.ofMinutes(10));
        try {
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
        } finally { prime.close(); }
        System.out.println("[RealMatrix] Prime done.");

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

        return results;
    }

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
        var hist = new Histogram(60_000_000_000L, 3);
        var ops = new AtomicLong();
        var errors = new AtomicLong();
        var errorsByType = new java.util.concurrent.ConcurrentHashMap<String, Long>();
        long deadline = System.nanoTime() + durationMs * 1_000_000L;
        var pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final long seed = i * 1_000_003L;
            pool.submit(() -> {
                var rng = new Random(seed);
                while (System.nanoTime() < deadline) {
                    long t0 = System.nanoTime();
                    try {
                        op.accept(rng);
                        long us = (System.nanoTime() - t0) / 1000;
                        synchronized (hist) { hist.recordValue(Math.min(us, 60_000_000)); }
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

        return cellFrom(name, workload, distribution, hitRate, threads, durationMs,
                ops.get(), hist, errors.get(), errorsByType, client);
    }

    private static MatrixCell qpsTimedRun(String name, String workload, String distribution,
                                           double hitRate, int targetQps, long durationMs,
                                           java.util.function.Consumer<Random> op,
                                           RealMatrixClient client) throws InterruptedException {
        int threads = Math.max(8, Math.min(targetQps / 10, 200));
        var hist = new Histogram(60_000_000_000L, 3);
        var ops = new AtomicLong();
        var errors = new AtomicLong();
        var errorsByType = new java.util.concurrent.ConcurrentHashMap<String, Long>();
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
                    synchronized (hist) { hist.recordValue(Math.min(responseUs, 60_000_000)); }
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

    private RealMatrix() {}
}
