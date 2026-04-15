package com.authx.clustertest.matrix;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * SDK-only matrix benchmark using {@link MatrixClient} (InMemoryTransport
 * + cache + optional coalescing). Measures pure SDK overhead with no
 * network in the path.
 */
public final class SdkMatrix {

    private static final int RESOURCE_SPACE = 10_000;

    public static List<MatrixCell> runAll(long perCellDurationMs) throws InterruptedException {
        var runner = new MatrixRunner();
        var results = new ArrayList<MatrixCell>();

        // ── Matrix 1: hit-rate × concurrency (5 × 4 = 20 cells, uniform distribution) ──
        double[] hitRates = {0.0, 0.5, 0.8, 0.95, 0.99};
        int[] concurrencies = {1, 10, 100, 500};
        for (double hr : hitRates) {
            for (int t : concurrencies) {
                results.add(runReadCell(runner, hr, t, perCellDurationMs, "uniform", true, false));
            }
        }

        // ── Matrix 2: distribution effect (3 cells, threads=100, hit-rate=0.95) ──
        results.add(runReadCell(runner, 0.95, 100, perCellDurationMs, "uniform",     true, false));
        results.add(runReadCell(runner, 0.95, 100, perCellDurationMs, "zipfian-1.5", true, false));
        results.add(runReadCell(runner, 0.95, 100, perCellDurationMs, "single-hot",  true, false));

        // ── Matrix 3: coalescing on/off (single-hot key, 100 threads, no cache) ──
        // With cache OFF, every call hits the inner transport. Coalescing
        // should dramatically reduce inner calls when many threads request
        // the same key concurrently.
        results.add(runReadCell(runner, 1.0, 100, perCellDurationMs, "single-hot", false, false));
        results.add(runReadCell(runner, 1.0, 100, perCellDurationMs, "single-hot", false, true));

        // ── Matrix 4: cache on vs off (uniform, threads=100, primed) ──
        results.add(runReadCell(runner, 1.0, 100, perCellDurationMs, "uniform", true,  false));
        results.add(runReadCell(runner, 1.0, 100, perCellDurationMs, "uniform", false, false));

        // ── Matrix 5: QPS-target ladder (rate-limited, response time) ──
        var qpsRunner = new QpsRunner();
        int[] qpsTargets = {1_000, 10_000, 100_000, 500_000, 1_000_000, 2_000_000};
        for (int qps : qpsTargets) {
            results.add(runQpsCell(qpsRunner, 0.95, qps, perCellDurationMs, "uniform"));
        }

        // ── Matrix 6: TTL impact (4 cells, uniform 100 threads, all primed) ──
        // TTLs: 1s / 30s / 5min / infinite. With perCellDurationMs=5000 and a
        // 1s TTL, most entries will expire mid-run and need re-fetch. Shorter
        // TTL = more misses = more transport calls.
        long[][] ttls = {
                {1, 1_000},      // 1 second
                {30, 30_000},    // 30 seconds
                {300, 300_000},  // 5 minutes
                {86400, 86_400_000}  // 1 day (effectively infinite for this run)
        };
        for (long[] ttl : ttls) {
            results.add(runTtlCell(runner, 1.0, 100, perCellDurationMs, ttl[1], ttl[0]));
        }

        // ── Matrix 7: cache-size impact (4 cells, uniform 100 threads) ──
        // With 10k primed keys + uniform distribution, cache smaller than 10k
        // means frequent evictions and re-fetches.
        long[] sizes = {1_000, 5_000, 10_000, 100_000};
        for (long size : sizes) {
            results.add(runCacheSizeCell(runner, 1.0, 100, perCellDurationMs, size));
        }

        // ── Matrix 8: read/write mix (5 cells, 100 threads, 95% cache hit) ──
        // Pure read (0% write) vs pure write (100% write) and 3 intermediate
        // ratios. Writes force cache invalidation for the affected resource,
        // so higher write % reduces effective hit rate.
        double[] writeRatios = {0.0, 0.05, 0.20, 0.50, 1.00};
        for (double wr : writeRatios) {
            results.add(runMixCell(runner, 100, perCellDurationMs, wr));
        }

        return results;
    }

    /** TTL-variant cell: runs for longer than TTL so cache churn is measurable. */
    private static MatrixCell runTtlCell(MatrixRunner runner, double hitRate, int threads,
                                          long durationMs, long ttlMs, long ttlSec) throws InterruptedException {
        var client = MatrixClient.create(true, false, 100_000, java.time.Duration.ofMillis(ttlMs));
        client.prime(RESOURCE_SPACE);
        client.warmCache(RESOURCE_SPACE);
        try {
            var name = String.format("ttl.ttl=%ds.hr=%.2f.t=%d", ttlSec, hitRate, threads);
            return runner.run(name, "READ", "uniform-ttl-" + ttlSec + "s", hitRate, threads, durationMs,
                    rng -> {
                        int idx = rng.nextInt(RESOURCE_SPACE);
                        client.check("primed-" + idx, "view", "u-" + idx);
                    },
                    () -> {
                        var s = client.cacheStats();
                        long total = s.hitCount() + s.missCount();
                        return total == 0 ? 0.0 : (double) s.hitCount() / total;
                    });
        } finally { client.close(); }
    }

    /** Cache-size-variant cell. */
    private static MatrixCell runCacheSizeCell(MatrixRunner runner, double hitRate, int threads,
                                                long durationMs, long maxSize) throws InterruptedException {
        var client = MatrixClient.create(true, false, maxSize, java.time.Duration.ofMinutes(10));
        client.prime(RESOURCE_SPACE);
        client.warmCache((int) Math.min(RESOURCE_SPACE, maxSize));
        try {
            var name = String.format("size.max=%d.hr=%.2f.t=%d", maxSize, hitRate, threads);
            return runner.run(name, "READ", "uniform-size-" + maxSize, hitRate, threads, durationMs,
                    rng -> {
                        int idx = rng.nextInt(RESOURCE_SPACE);
                        client.check("primed-" + idx, "view", "u-" + idx);
                    },
                    () -> {
                        var s = client.cacheStats();
                        long total = s.hitCount() + s.missCount();
                        return total == 0 ? 0.0 : (double) s.hitCount() / total;
                    });
        } finally { client.close(); }
    }

    /** Read/write mix cell: writeRatio fraction of ops are writes. */
    private static MatrixCell runMixCell(MatrixRunner runner, int threads, long durationMs,
                                          double writeRatio) throws InterruptedException {
        var client = MatrixClient.create(true, false, 100_000, java.time.Duration.ofMinutes(10));
        client.prime(RESOURCE_SPACE);
        client.warmCache(RESOURCE_SPACE);
        try {
            var name = String.format("mix.writeRatio=%.2f.t=%d", writeRatio, threads);
            return runner.run(name, writeRatio == 0 ? "READ" : (writeRatio == 1.0 ? "WRITE" : "MIXED"),
                    "mix-wr=" + (int)(writeRatio*100), 0.95, threads, durationMs,
                    rng -> {
                        int idx = rng.nextInt(RESOURCE_SPACE);
                        if (rng.nextDouble() < writeRatio) {
                            // Writes cause cache invalidation for the affected doc
                            client.write("primed-" + idx, "viewer", "u-new-" + rng.nextInt(10_000));
                        } else {
                            client.check("primed-" + idx, "view", "u-" + idx);
                        }
                    },
                    () -> {
                        var s = client.cacheStats();
                        long total = s.hitCount() + s.missCount();
                        return total == 0 ? 0.0 : (double) s.hitCount() / total;
                    });
        } finally { client.close(); }
    }

    /** Rate-limited cell: aim for target QPS, record response time (incl. queue wait). */
    private static MatrixCell runQpsCell(QpsRunner runner, double hitRate, int targetQps,
                                          long durationMs, String distribution) throws InterruptedException {
        var client = MatrixClient.create(true, false);
        client.prime(RESOURCE_SPACE);
        if (hitRate > 0) {
            int warmCount = (int) Math.min(RESOURCE_SPACE, hitRate * RESOURCE_SPACE);
            client.warmCache(warmCount);
        }
        try {
            var name = String.format("qps.dist=%s.hr=%.2f.target=%d", distribution, hitRate, targetQps);
            return runner.run(name, "QPS-TARGET", distribution, hitRate, targetQps, durationMs,
                    rng -> {
                        String docId, userId;
                        if (rng.nextDouble() < hitRate) {
                            int idx = pickPrimedIndex(rng, distribution, RESOURCE_SPACE);
                            docId = "primed-" + idx;
                            userId = "u-" + idx;
                        } else {
                            int idx = rng.nextInt(1_000_000);
                            docId = "fresh-" + idx;
                            userId = "u-fresh-" + idx;
                        }
                        client.check(docId, "view", userId);
                    },
                    () -> {
                        var s = client.cacheStats();
                        long total = s.hitCount() + s.missCount();
                        return total == 0 ? 0.0 : (double) s.hitCount() / total;
                    });
        } finally { client.close(); }
    }

    private static MatrixCell runReadCell(MatrixRunner runner, double hitRate, int threads,
                                          long durationMs, String distribution,
                                          boolean cacheEnabled, boolean coalescing) throws InterruptedException {
        var client = MatrixClient.create(cacheEnabled, coalescing);
        client.prime(RESOURCE_SPACE);
        if (cacheEnabled && hitRate > 0) {
            // Warm the cache so the first measurement window already has hits available.
            int warmCount = (int) Math.min(RESOURCE_SPACE, hitRate * RESOURCE_SPACE);
            client.warmCache(warmCount);
        }

        try {
            var name = String.format("read.dist=%s.hr=%.2f.t=%d.cache=%s.coalesce=%s",
                    distribution, hitRate, threads, cacheEnabled, coalescing);

            var cell = runner.run(name, "READ", distribution, hitRate, threads, durationMs,
                    rng -> {
                        String docId, userId;
                        if (rng.nextDouble() < hitRate) {
                            int idx = pickPrimedIndex(rng, distribution, RESOURCE_SPACE);
                            docId = "primed-" + idx;
                            userId = "u-" + idx;
                        } else {
                            int idx = rng.nextInt(1_000_000);
                            docId = "fresh-" + idx;
                            userId = "u-fresh-" + idx;
                        }
                        client.check(docId, "view", userId);
                    },
                    () -> {
                        var s = client.cacheStats();
                        long total = s.hitCount() + s.missCount();
                        return total == 0 ? 0.0 : (double) s.hitCount() / total;
                    });
            return cell;
        } finally {
            client.close();
        }
    }

    private static int pickPrimedIndex(Random rng, String distribution, int n) {
        return switch (distribution) {
            case "single-hot" -> 0;
            case "zipfian-1.5" -> {
                double u = rng.nextDouble();
                double v = Math.pow(1 - u, 1.0 / (1.0 - 1.5));
                yield Math.min((int) (n * v), n - 1);
            }
            default -> rng.nextInt(n);
        };
    }

    private SdkMatrix() {}
}
