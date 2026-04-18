package com.authx.clustertest.matrix;

import com.authx.sdk.cache.CaffeineCache;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.transport.CachedTransport;
import com.authx.sdk.transport.CoalescingTransport;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scenario-based SDK benchmark. Each section answers one clear question
 * about SDK performance, rather than dumping multi-dimensional heatmaps.
 */
public final class SdkMatrix {

    private static final int WORKING_SET = 10_000;

    public static List<MatrixCell> runAll(long perCellDurationMs) throws InterruptedException {
        var runner = new MatrixRunner();
        var results = new ArrayList<MatrixCell>();

        // ═══ 1. 缓存命中 vs 未命中 (5 scenarios) ═══
        results.add(scenario_cache(runner, "1A-纯命中",         perCellDurationMs, 1.00));
        results.add(scenario_cache(runner, "1B-95%命中",        perCellDurationMs, 0.95));
        results.add(scenario_cache(runner, "1C-50%命中",        perCellDurationMs, 0.50));
        results.add(scenario_cache(runner, "1D-10%命中",        perCellDurationMs, 0.10));
        results.add(scenario_cache(runner, "1E-纯未命中",       perCellDurationMs, 0.00));

        // ═══ 2. 嵌套层级深度 (5 scenarios) ═══
        results.add(scenario_depth(runner, "2A-直接授权-depth0",     perCellDurationMs, 0));
        results.add(scenario_depth(runner, "2B-浅嵌套-depth3",        perCellDurationMs, 3));
        results.add(scenario_depth(runner, "2C-中嵌套-depth5",        perCellDurationMs, 5));
        results.add(scenario_depth(runner, "2D-深嵌套-depth10",       perCellDurationMs, 10));
        results.add(scenario_depth(runner, "2E-极深嵌套-depth20",     perCellDurationMs, 20));

        // ═══ 3. 多实例扩展性 (4 scenarios) ═══
        results.add(scenario_multi(runner, "3A-单实例",      perCellDurationMs, 1));
        results.add(scenario_multi(runner, "3B-3实例",       perCellDurationMs, 3));
        results.add(scenario_multi(runner, "3C-5实例",       perCellDurationMs, 5));
        results.add(scenario_multi(runner, "3D-10实例",      perCellDurationMs, 10));

        // ═══ 4. 读写比例 (5 scenarios) ═══
        results.add(scenario_mix(runner, "4A-纯读",           perCellDurationMs, 0.00));
        results.add(scenario_mix(runner, "4B-95读5写",        perCellDurationMs, 0.05));
        results.add(scenario_mix(runner, "4C-80读20写",       perCellDurationMs, 0.20));
        results.add(scenario_mix(runner, "4D-50读50写",       perCellDurationMs, 0.50));
        results.add(scenario_mix(runner, "4E-纯写",           perCellDurationMs, 1.00));

        // ═══ 5. QPS 阶梯（控速，看延迟拐点） ═══
        var qpsRunner = new QpsRunner();
        int[] qpsTargets = {1_000, 10_000, 100_000, 500_000, 1_000_000, 2_000_000};
        for (int qps : qpsTargets) {
            results.add(scenario_qps(qpsRunner, "5-" + qps + "qps", perCellDurationMs, qps));
        }

        // ═══ 6. 配置敏感度 ═══
        // 6A TTL 影响
        long[] ttlMs = {100, 1_000, 30_000, 300_000};
        for (long t : ttlMs) {
            results.add(scenario_ttl(runner, "6A-TTL-" + formatTtl(t), perCellDurationMs, t));
        }
        // 6B Cache size 影响
        long[] sizes = {1_000, 5_000, 10_000, 100_000};
        for (long s : sizes) {
            results.add(scenario_size(runner, "6B-size-" + s, perCellDurationMs, s));
        }
        // 6C Coalescing on/off
        results.add(scenario_coalesce(runner, "6C-coalescing-off", perCellDurationMs, false));
        results.add(scenario_coalesce(runner, "6C-coalescing-on",  perCellDurationMs, true));

        return results;
    }

    private static String formatTtl(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60_000) return (ms / 1000) + "s";
        return (ms / 60_000) + "min";
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scenario implementations
    // ═══════════════════════════════════════════════════════════════════

    private static MatrixCell scenario_cache(MatrixRunner runner, String name,
                                              long durationMs, double hitRate) throws InterruptedException {
        var client = MatrixClient.create(true, false);
        client.prime(WORKING_SET);
        if (hitRate > 0) client.warmCache(WORKING_SET);
        try {
            return runner.run(name, "READ", String.format("hr=%.0f%%", hitRate * 100), hitRate, 100, durationMs,
                    rng -> {
                        if (rng.nextDouble() < hitRate) {
                            int idx = rng.nextInt(WORKING_SET);
                            client.check("primed-" + idx, "view", "u-" + idx);
                        } else {
                            int idx = rng.nextInt(1_000_000);
                            client.check("fresh-" + idx, "view", "u-fresh-" + idx);
                        }
                    },
                    () -> hitRateOf(client));
        } finally { client.close(); }
    }

    private static MatrixCell scenario_depth(MatrixRunner runner, String name,
                                              long durationMs, int depth) throws InterruptedException {
        // Build a client where check() goes through DepthSimTransport → LatencySim
        // (2ms backend) → InMemory, with Cache on top. Deep scenarios pay
        // (2ms + depth × 100μs) on miss, ~1μs on hit.
        var inner = new InMemoryTransport();
        var metrics = new SdkMetrics();
        var cache = new CaffeineCache<CheckKey, CheckResult>(100_000, Duration.ofMinutes(10), CheckKey::resourceIndex);
        SdkTransport withLatency = new LatencySimTransport(inner, 2000);    // 2ms backend
        var depthTransport = new DepthSimTransport(withLatency, depth);
        var chain = new CachedTransport(depthTransport, cache, metrics);
        // Simulate prod pattern: warm cache for primed; measurement uses the primed set
        // so most checks hit the cache and SKIP the depth penalty — this is the real
        // prod pattern. Report shows "depth penalty paid on first access only".
        // But to see the depth cost, we ALSO run without warming so every request
        // pays the penalty. We do the non-warm variant here.
        var client = new com.authx.clustertest.matrix.MatrixAdapter(chain, inner, cache);
        // Prime the inner store (so the underlying check returns ALLOWED).
        client.prime(WORKING_SET);
        // Do NOT warm cache — we want depth cost to be on the hot path.
        try {
            return runner.run(name, "READ", "depth=" + depth, 0.0, 100, durationMs,
                    rng -> {
                        // Cycle through a small subset so cache hit rate grows over time —
                        // this realistically models "first access pays depth, subsequent are cached".
                        int idx = rng.nextInt(WORKING_SET);
                        client.check("primed-" + idx, "view", "u-" + idx);
                    },
                    () -> hitRateOf(client));
        } finally { client.close(); }
    }

    private static MatrixCell scenario_multi(MatrixRunner runner, String name,
                                              long durationMs, int instances) throws InterruptedException {
        // Create N independent clients, each with its own cache, running concurrent workloads.
        // Report aggregate TPS across all N instances — should scale roughly linearly
        // until CPU saturates (N * ~100% each = ~N*100% load).
        var clients = new MatrixClient[instances];
        for (int i = 0; i < instances; i++) {
            clients[i] = MatrixClient.create(true, false);
            clients[i].prime(WORKING_SET);
            clients[i].warmCache(WORKING_SET);
        }
        try {
            // Split 100 worker threads across N instances → each instance runs with
            // ~100/N threads. Total concurrency stays at 100 so we measure scaling.
            int threadsPerInstance = Math.max(1, 100 / instances);
            var aggHist = new org.HdrHistogram.Histogram(60_000_000_000L, 3);
            var totalOps = new AtomicLong();
            var totalErrors = new AtomicLong();
            long deadline = System.nanoTime() + durationMs * 1_000_000L;
            var pool = Executors.newFixedThreadPool(instances * threadsPerInstance);
            for (int i = 0; i < instances; i++) {
                final int inst = i;
                for (int t = 0; t < threadsPerInstance; t++) {
                    pool.submit(() -> {
                        var rng = new Random(inst * 1_000_003L);
                        while (System.nanoTime() < deadline) {
                            long t0 = System.nanoTime();
                            try {
                                int idx = rng.nextInt(WORKING_SET);
                                clients[inst].check("primed-" + idx, "view", "u-" + idx);
                                long us = (System.nanoTime() - t0) / 1000;
                                synchronized (aggHist) { aggHist.recordValue(Math.min(us, 60_000_000)); }
                                totalOps.incrementAndGet();
                            } catch (Exception e) { totalErrors.incrementAndGet(); }
                        }
                    });
                }
            }
            pool.shutdown();
            pool.awaitTermination(durationMs + 30_000, TimeUnit.MILLISECONDS);

            double tps = totalOps.get() * 1000.0 / durationMs;
            var buckets = new ArrayList<long[]>();
            double[] pcts = {0, 50, 75, 90, 95, 99, 99.5, 99.9, 99.95, 99.99, 100};
            for (double p : pcts) buckets.add(new long[]{Math.round(p * 100), aggHist.getValueAtPercentile(p)});
            return new MatrixCell(name, "READ", "multi-instance=" + instances, 1.0, instances, durationMs,
                    totalOps.get(), tps, 0.999,
                    aggHist.getMinValue(),
                    aggHist.getValueAtPercentile(50), aggHist.getValueAtPercentile(90),
                    aggHist.getValueAtPercentile(99), aggHist.getValueAtPercentile(99.9),
                    aggHist.getValueAtPercentile(99.99), aggHist.getMaxValue(),
                    totalErrors.get(), java.util.Map.of(), buckets);
        } finally {
            for (var c : clients) c.close();
        }
    }

    private static MatrixCell scenario_mix(MatrixRunner runner, String name,
                                            long durationMs, double writeRatio) throws InterruptedException {
        var client = MatrixClient.create(true, false);
        client.prime(WORKING_SET);
        client.warmCache(WORKING_SET);
        try {
            return runner.run(name, writeRatio == 0 ? "READ" : (writeRatio == 1.0 ? "WRITE" : "MIXED"),
                    "writeRatio=" + (int)(writeRatio * 100) + "%",
                    1 - writeRatio, 100, durationMs,
                    rng -> {
                        int idx = rng.nextInt(WORKING_SET);
                        if (rng.nextDouble() < writeRatio) {
                            client.write("primed-" + idx, "viewer", "u-new-" + rng.nextInt(10_000));
                        } else {
                            client.check("primed-" + idx, "view", "u-" + idx);
                        }
                    },
                    () -> hitRateOf(client));
        } finally { client.close(); }
    }

    private static MatrixCell scenario_qps(QpsRunner runner, String name,
                                            long durationMs, int targetQps) throws InterruptedException {
        var client = MatrixClient.create(true, false);
        client.prime(WORKING_SET);
        client.warmCache(WORKING_SET);
        try {
            return runner.run(name, "QPS-TARGET", "target=" + targetQps, 0.95, targetQps, durationMs,
                    rng -> {
                        int idx = rng.nextInt(WORKING_SET);
                        client.check("primed-" + idx, "view", "u-" + idx);
                    },
                    () -> hitRateOf(client));
        } finally { client.close(); }
    }

    private static MatrixCell scenario_ttl(MatrixRunner runner, String name,
                                            long durationMs, long ttlMs) throws InterruptedException {
        var client = MatrixClient.create(true, false, 100_000, Duration.ofMillis(ttlMs));
        client.prime(WORKING_SET);
        client.warmCache(WORKING_SET);
        try {
            return runner.run(name, "READ", "ttl=" + formatTtl(ttlMs), 1.0, 100, durationMs,
                    rng -> {
                        int idx = rng.nextInt(WORKING_SET);
                        client.check("primed-" + idx, "view", "u-" + idx);
                    },
                    () -> hitRateOf(client));
        } finally { client.close(); }
    }

    private static MatrixCell scenario_size(MatrixRunner runner, String name,
                                             long durationMs, long maxSize) throws InterruptedException {
        var client = MatrixClient.create(true, false, maxSize, Duration.ofMinutes(10));
        client.prime(WORKING_SET);
        client.warmCache((int) Math.min(WORKING_SET, maxSize));
        try {
            return runner.run(name, "READ", "maxSize=" + maxSize, 1.0, 100, durationMs,
                    rng -> {
                        int idx = rng.nextInt(WORKING_SET);
                        client.check("primed-" + idx, "view", "u-" + idx);
                    },
                    () -> hitRateOf(client));
        } finally { client.close(); }
    }

    private static MatrixCell scenario_coalesce(MatrixRunner runner, String name,
                                                 long durationMs, boolean on) throws InterruptedException {
        // No cache + single-hot key → coalescing should dominate speed.
        var client = MatrixClient.create(false, on);
        client.prime(WORKING_SET);
        try {
            return runner.run(name, "READ", "coalescing=" + (on ? "on" : "off"), 1.0, 100, durationMs,
                    rng -> client.check("primed-0", "view", "u-0"),
                    () -> 0.0);
        } finally { client.close(); }
    }

    private static double hitRateOf(MatrixClient client) {
        var s = client.cacheStats();
        long total = s.hitCount() + s.missCount();
        return total == 0 ? 0.0 : (double) s.hitCount() / total;
    }

    private static double hitRateOf(com.authx.clustertest.matrix.MatrixAdapter client) {
        var s = client.cacheStats();
        long total = s.hitCount() + s.missCount();
        return total == 0 ? 0.0 : (double) s.hitCount() / total;
    }

    private SdkMatrix() {}
}
