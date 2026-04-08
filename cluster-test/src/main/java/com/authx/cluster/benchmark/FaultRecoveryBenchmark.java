package com.authx.cluster.benchmark;

import com.authx.cluster.generator.DataModel;
import com.authx.sdk.AuthxClient;
import org.HdrHistogram.Histogram;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B6 — Fault recovery: continuous reads while operator kills/restarts a SpiceDB node.
 *
 * This benchmark runs for 90 seconds total:
 * - 0-15s: baseline (normal operation)
 * - 15s: LOG instructs operator to kill spicedb-2
 * - 15-45s: observe error spike
 * - 45s: LOG instructs operator to restart spicedb-2
 * - 45-90s: observe recovery
 *
 * Actual docker commands are manual — the benchmark only observes.
 */
@Component
public class FaultRecoveryBenchmark {

    private static final System.Logger LOG = System.getLogger(FaultRecoveryBenchmark.class.getName());

    private final AuthxClient client;

    public FaultRecoveryBenchmark(AuthxClient client) {
        this.client = client;
    }

    public BenchmarkResult run() {
        var docFactory = client.on("document");
        var histogram = new Histogram(60_000_000L, 3);
        var ops = new AtomicLong();
        var errors = new AtomicLong();
        var running = new AtomicBoolean(true);

        int workerThreads = 20;
        long startTime = System.nanoTime();
        long totalDurationNanos = 90_000_000_000L; // 90 seconds

        LOG.log(System.Logger.Level.INFO, "[B6-fault-recovery] Starting 90s fault recovery test with {0} threads", workerThreads);
        LOG.log(System.Logger.Level.INFO, "[B6] Phase 1 (0-15s): Baseline — normal operation");

        // Worker threads
        for (int i = 0; i < workerThreads; i++) {
            Thread.ofVirtual().name("fault-bench-", i).start(() -> {
                while (running.get()) {
                    var rng = ThreadLocalRandom.current();
                    String docId = DataModel.docId(rng.nextInt(DataModel.DOC_COUNT));
                    String userId = DataModel.userId(rng.nextInt(DataModel.USER_COUNT));
                    long opStart = System.nanoTime();
                    try {
                        docFactory.check(docId, "view", userId);
                        long latencyUs = (System.nanoTime() - opStart) / 1_000;
                        ops.incrementAndGet();
                        synchronized (histogram) {
                            histogram.recordValue(Math.min(latencyUs, 60_000_000L));
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }
            });
        }

        // Monitor loop with phase transitions
        boolean killedLogged = false;
        boolean restartLogged = false;
        long lastLogTime = startTime;
        long lastLogOps = 0;
        long lastLogErrors = 0;

        while (System.nanoTime() - startTime < totalDurationNanos) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }

            long elapsed = (System.nanoTime() - startTime) / 1_000_000_000;

            if (elapsed >= 15 && !killedLogged) {
                killedLogged = true;
                LOG.log(System.Logger.Level.WARNING,
                        "\n╔═══════════════════════════════════════════════════════╗\n" +
                        "║  ACTION REQUIRED: Run `docker compose stop spicedb-2` ║\n" +
                        "╚═══════════════════════════════════════════════════════╝");
            }

            if (elapsed >= 45 && !restartLogged) {
                restartLogged = true;
                LOG.log(System.Logger.Level.WARNING,
                        "\n╔════════════════════════════════════════════════════════╗\n" +
                        "║  ACTION REQUIRED: Run `docker compose start spicedb-2` ║\n" +
                        "╚════════════════════════════════════════════════════════╝");
            }

            // Log every 5 seconds
            long now = System.nanoTime();
            if ((now - lastLogTime) >= 5_000_000_000L) {
                long currentOps = ops.get();
                long currentErrors = errors.get();
                double intervalTps = (currentOps - lastLogOps) * 1_000_000_000.0 / (now - lastLogTime);
                long intervalErrors = currentErrors - lastLogErrors;
                LOG.log(System.Logger.Level.INFO,
                        "[B6] {0}s — TPS: {1,number,#.0}  errors(interval): {2}  total errors: {3}",
                        elapsed, intervalTps, intervalErrors, currentErrors);
                lastLogTime = now;
                lastLogOps = currentOps;
                lastLogErrors = currentErrors;
            }
        }

        running.set(false);
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        long totalOps = ops.get();
        long totalErrors = errors.get();
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        long p50, p99, p999;
        synchronized (histogram) {
            p50 = histogram.getValueAtPercentile(50);
            p99 = histogram.getValueAtPercentile(99);
            p999 = histogram.getValueAtPercentile(99.9);
        }

        var result = new BenchmarkResult("B6-fault-recovery", totalOps,
                totalOps * 1000.0 / Math.max(durationMs, 1),
                p50, p99, p999, totalErrors,
                (double) totalErrors / Math.max(totalOps + totalErrors, 1), durationMs);

        LOG.log(System.Logger.Level.INFO,
                "[B6] Done — TPS: {0,number,#.0}  p50: {1}us  p99: {2}us  errors: {3} ({4,number,#.##%})",
                result.tps(), p50, p99, totalErrors, result.errorRate());

        return result;
    }
}
