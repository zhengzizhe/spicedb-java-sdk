package com.authx.clustertest.benchmark;

import org.HdrHistogram.Histogram;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Generic concurrent benchmark runner. Spawns a fixed thread pool, runs the
 * provided operation in a tight loop on each thread until the deadline, and
 * records per-operation latency into a shared HdrHistogram with microsecond
 * resolution.
 */
public class ScenarioRunner {

    /**
     * Run the given operation concurrently for the requested duration.
     *
     * @param name        scenario name (e.g. "B1-Read"), echoed into the result
     * @param threads     fixed thread pool size
     * @param durationMs  wall-clock duration in milliseconds
     * @param op          operation invoked repeatedly; receives a per-thread Random
     */
    public BenchmarkResult run(String name, int threads, long durationMs, Consumer<Random> op)
            throws InterruptedException {
        // Up to 60s per op at microsecond resolution, 3 significant figures.
        var hist = new Histogram(60_000_000_000L, 3);
        var ops = new AtomicLong();
        var errors = new AtomicLong();
        var errorsByType = new ConcurrentHashMap<String, Long>();
        var pool = Executors.newFixedThreadPool(threads);
        long deadline = System.nanoTime() + durationMs * 1_000_000L;

        for (int i = 0; i < threads; i++) {
            final long seed = System.nanoTime() ^ ((long) i << 32);
            pool.submit(() -> {
                var rng = new Random(seed);
                while (System.nanoTime() < deadline) {
                    long t0 = System.nanoTime();
                    try {
                        op.accept(rng);
                        long us = (System.nanoTime() - t0) / 1000;
                        synchronized (hist) {
                            hist.recordValue(Math.min(us, 60_000_000L));
                        }
                        ops.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        errorsByType.merge(e.getClass().getSimpleName(), 1L, Long::sum);
                    }
                }
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(durationMs + 30_000, TimeUnit.MILLISECONDS)) {
            pool.shutdownNow();
        }

        return new BenchmarkResult(
                name, threads, durationMs, ops.get(),
                ops.get() * 1000.0 / Math.max(1, durationMs),
                hist.getValueAtPercentile(50),
                hist.getValueAtPercentile(90),
                hist.getValueAtPercentile(99),
                hist.getValueAtPercentile(99.9),
                hist.getMaxValue(),
                errors.get(),
                Map.copyOf(new HashMap<>(errorsByType)));
    }
}
