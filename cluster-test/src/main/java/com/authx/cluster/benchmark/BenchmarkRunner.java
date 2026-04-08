package com.authx.cluster.benchmark;

import org.HdrHistogram.Histogram;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class BenchmarkRunner {

    private static final System.Logger LOG = System.getLogger(BenchmarkRunner.class.getName());

    private final int threads;
    private final int durationSeconds;
    private final int warmupSeconds;

    public BenchmarkRunner(
            @Value("${benchmark.threads:100}") int threads,
            @Value("${benchmark.duration-seconds:60}") int durationSeconds,
            @Value("${benchmark.warmup-seconds:10}") int warmupSeconds) {
        this.threads = threads;
        this.durationSeconds = durationSeconds;
        this.warmupSeconds = warmupSeconds;
    }

    /**
     * Run a benchmark scenario. The operation is called repeatedly by N virtual threads
     * for the configured duration. Latency is tracked with HdrHistogram.
     *
     * @param scenarioName name for reporting
     * @param operation    the work to execute per iteration (should be a single logical op)
     * @return aggregated results
     */
    public BenchmarkResult run(String scenarioName, Runnable operation) {
        var histogram = new Histogram(60_000_000L, 3); // max 60s in microseconds
        var errors = new AtomicLong();
        var ops = new AtomicLong();
        var running = new AtomicBoolean(true);
        var warmedUp = new AtomicBoolean(false);

        long startTime = System.nanoTime();
        long warmupEndNanos = startTime + (long) warmupSeconds * 1_000_000_000L;
        long endNanos = startTime + (long) (warmupSeconds + durationSeconds) * 1_000_000_000L;

        LOG.log(System.Logger.Level.INFO, "[{0}] Starting — {1} threads, {2}s warmup + {3}s duration",
                scenarioName, threads, warmupSeconds, durationSeconds);

        var latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().name("bench-" + scenarioName + "-", i).start(() -> {
                try {
                    while (running.get()) {
                        long opStart = System.nanoTime();
                        try {
                            operation.run();
                        } catch (Exception e) {
                            errors.incrementAndGet();
                            continue;
                        }
                        long latencyUs = (System.nanoTime() - opStart) / 1_000;
                        ops.incrementAndGet();

                        if (warmedUp.get()) {
                            synchronized (histogram) {
                                histogram.recordValue(Math.min(latencyUs, 60_000_000L));
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Monitor loop: log TPS every 5s, manage warmup/end
        long lastLogTime = System.nanoTime();
        long lastLogOps = 0;

        while (System.nanoTime() < endNanos) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }

            long now = System.nanoTime();

            // Warmup transition
            if (!warmedUp.get() && now >= warmupEndNanos) {
                warmedUp.set(true);
                ops.set(0);
                errors.set(0);
                LOG.log(System.Logger.Level.INFO, "[{0}] Warmup complete, measuring...", scenarioName);
                lastLogTime = now;
                lastLogOps = 0;
            }

            // Log TPS every 5 seconds
            if (warmedUp.get() && (now - lastLogTime) >= 5_000_000_000L) {
                long currentOps = ops.get();
                double intervalTps = (currentOps - lastLogOps) * 1_000_000_000.0 / (now - lastLogTime);
                LOG.log(System.Logger.Level.INFO, "[{0}] TPS: {1,number,#.0}  ops: {2}  errors: {3}",
                        scenarioName, intervalTps, currentOps, errors.get());
                lastLogTime = now;
                lastLogOps = currentOps;
            }
        }

        running.set(false);
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        long totalOps = ops.get();
        long totalErrors = errors.get();
        long actualDurationMs = (System.nanoTime() - warmupEndNanos) / 1_000_000;
        double tps = totalOps * 1000.0 / Math.max(actualDurationMs, 1);

        long p50, p99, p999;
        synchronized (histogram) {
            p50 = histogram.getValueAtPercentile(50);
            p99 = histogram.getValueAtPercentile(99);
            p999 = histogram.getValueAtPercentile(99.9);
        }

        var result = new BenchmarkResult(scenarioName, totalOps, tps, p50, p99, p999,
                totalErrors, totalOps > 0 ? (double) totalErrors / (totalOps + totalErrors) : 0,
                actualDurationMs);

        LOG.log(System.Logger.Level.INFO,
                "[{0}] Done — TPS: {1,number,#.0}  p50: {2}us  p99: {3}us  p999: {4}us  errors: {5} ({6,number,#.##%})",
                scenarioName, tps, p50, p99, p999, totalErrors, result.errorRate());

        return result;
    }
}
