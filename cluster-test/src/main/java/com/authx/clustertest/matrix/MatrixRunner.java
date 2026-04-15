package com.authx.clustertest.matrix;

import org.HdrHistogram.Histogram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Generic matrix benchmark runner: drives N concurrent threads against a
 * supplied operation, captures HdrHistogram percentiles + workload metadata.
 *
 * <p>The runner is workload-agnostic. The caller decides how many requests
 * to send and what each request does (cache hit vs miss vs write etc).
 */
public class MatrixRunner {

    /** Run one benchmark cell. */
    public MatrixCell run(String name, String workload, String distribution,
                           double targetHitRate, int threads, long durationMs,
                           Consumer<Random> op,
                           CellHook hook) throws InterruptedException {

        var hist = new Histogram(60_000_000_000L, 3);   // ns range, 3 sig figs
        var ops = new AtomicLong();
        var errors = new AtomicLong();
        var errorsByType = new ConcurrentHashMap<String, Long>();
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

        long actualOps = ops.get();
        double tps = actualOps * 1000.0 / durationMs;
        double actualHitRate = hook != null ? hook.actualHitRate() : 0.0;

        // Sample histogram for chart: 11 percentile points
        List<long[]> buckets = new ArrayList<>();
        double[] pcts = {0, 50, 75, 90, 95, 99, 99.5, 99.9, 99.95, 99.99, 100};
        for (double p : pcts) {
            buckets.add(new long[]{Math.round(p * 100), hist.getValueAtPercentile(p)});
        }

        return new MatrixCell(
                name, workload, distribution, targetHitRate, threads, durationMs,
                actualOps, tps, actualHitRate,
                hist.getMinValue(),
                hist.getValueAtPercentile(50),
                hist.getValueAtPercentile(90),
                hist.getValueAtPercentile(99),
                hist.getValueAtPercentile(99.9),
                hist.getValueAtPercentile(99.99),
                hist.getMaxValue(),
                errors.get(),
                Map.copyOf(errorsByType),
                buckets);
    }

    /** Optional callback from runner to workload — e.g. report current cache hit rate. */
    public interface CellHook {
        double actualHitRate();
    }
}
