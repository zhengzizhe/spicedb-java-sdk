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

        return results;
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
