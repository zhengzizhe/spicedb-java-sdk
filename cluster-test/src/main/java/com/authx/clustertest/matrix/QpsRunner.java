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
 * Closed-loop rate-limited runner: tries to hit a target QPS rate using a
 * token-bucket pacer. Captures both:
 * <ul>
 *   <li><b>Service time</b> — time spent inside op() once it starts (HdrHistogram).</li>
 *   <li><b>Response time</b> — wall-clock time from when the request was scheduled
 *       (according to the pacer) until op() completed. This catches "coordinated
 *       omission" where slow requests delay subsequent measurements.</li>
 * </ul>
 *
 * <p>The pacer schedules requests at uniform intervals; if the system can't keep
 * up, response times grow even when service times look fine. This is the
 * standard methodology used by wrk2 and Gil Tene.
 */
public class QpsRunner {

    public MatrixCell run(String name, String workload, String distribution,
                           double targetHitRate, int targetQps, long durationMs,
                           Consumer<Random> op,
                           MatrixRunner.CellHook hook) throws InterruptedException {

        var serviceHist = new Histogram(60_000_000_000L, 3);
        var responseHist = new Histogram(60_000_000_000L, 3);
        var ops = new AtomicLong();
        var errors = new AtomicLong();
        var errorsByType = new ConcurrentHashMap<String, Long>();

        // Use enough threads to absorb up to 100ms expected service time at target QPS
        int threads = Math.max(8, Math.min(targetQps / 10, 200));
        var pool = Executors.newFixedThreadPool(threads);

        long startNs = System.nanoTime();
        long deadlineNs = startNs + durationMs * 1_000_000L;
        long intervalNs = Math.max(1, 1_000_000_000L / targetQps);

        // Pacer: fire requests at uniform intervals.
        long scheduledFiredAt = startNs;
        while (scheduledFiredAt < deadlineNs) {
            final long thisScheduledAt = scheduledFiredAt;
            // Sleep until we should fire the next request (busy-wait for tight intervals)
            long now = System.nanoTime();
            if (thisScheduledAt > now) {
                long sleepNs = thisScheduledAt - now;
                if (sleepNs > 1_000_000) {
                    Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
                } else {
                    while (System.nanoTime() < thisScheduledAt) Thread.onSpinWait();
                }
            }

            pool.submit(() -> {
                long actualStart = System.nanoTime();
                try {
                    op.accept(new Random(actualStart));
                    long endNs = System.nanoTime();
                    long serviceUs = (endNs - actualStart) / 1000;
                    long responseUs = (endNs - thisScheduledAt) / 1000;
                    synchronized (serviceHist) { serviceHist.recordValue(Math.min(serviceUs, 60_000_000)); }
                    synchronized (responseHist) { responseHist.recordValue(Math.min(responseUs, 60_000_000)); }
                    ops.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    errorsByType.merge(e.getClass().getSimpleName(), 1L, Long::sum);
                }
            });

            scheduledFiredAt += intervalNs;
        }

        pool.shutdown();
        pool.awaitTermination(durationMs + 30_000, TimeUnit.MILLISECONDS);

        long actualOps = ops.get();
        double actualTps = actualOps * 1000.0 / durationMs;
        double actualHitRate = hook != null ? hook.actualHitRate() : 0.0;

        // Use response time histogram (catches coordinated omission)
        List<long[]> buckets = new ArrayList<>();
        double[] pcts = {0, 50, 75, 90, 95, 99, 99.5, 99.9, 99.95, 99.99, 100};
        for (double p : pcts) {
            buckets.add(new long[]{Math.round(p * 100), responseHist.getValueAtPercentile(p)});
        }

        // Encode "QPS target" as the threads field for display purposes
        return new MatrixCell(
                name, workload, distribution, targetHitRate,
                targetQps, durationMs,
                actualOps, actualTps, actualHitRate,
                responseHist.getMinValue(),
                responseHist.getValueAtPercentile(50),
                responseHist.getValueAtPercentile(90),
                responseHist.getValueAtPercentile(99),
                responseHist.getValueAtPercentile(99.9),
                responseHist.getValueAtPercentile(99.99),
                responseHist.getMaxValue(),
                errors.get(),
                Map.copyOf(errorsByType),
                buckets);
    }
}
