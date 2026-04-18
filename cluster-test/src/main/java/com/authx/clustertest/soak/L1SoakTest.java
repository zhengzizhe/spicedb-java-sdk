package com.authx.clustertest.soak;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L1 — long soak test. Drives a sustained, rate-limited read workload
 * (default 200 TPS) for {@code durationMinutes} minutes. Samples resource
 * usage every 30s via {@link ResourceSampler}.
 *
 * Returns a map with {@code samples} (list of {@link ResourceSampler.Sample})
 * and a {@code summary} containing ops, errors, and durationMs.
 */
@Component
public class L1SoakTest {

    private static final int DEFAULT_DURATION_MINUTES = 30;
    private static final int TARGET_TPS = 200;
    private static final int WORKER_THREADS = 20;
    private static final long SAMPLE_PERIOD_MS = 30_000L;

    private final AuthxClient client;
    private final ResourceSampler sampler;

    public L1SoakTest(AuthxClient client, ResourceSampler sampler) {
        this.client = client;
        this.sampler = sampler;
    }

    public Map<String, Object> run() throws InterruptedException {
        return run(DEFAULT_DURATION_MINUTES);
    }

    public Map<String, Object> run(int durationMinutes) throws InterruptedException {
        long durationMs = durationMinutes * 60_000L;
        long startMs = System.currentTimeMillis();
        long startSec = startMs / 1000;
        long deadline = System.nanoTime() + durationMs * 1_000_000L;

        AtomicLong ops = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        List<ResourceSampler.Sample> samples = new ArrayList<>();
        ConcurrentLinkedQueue<ResourceSampler.Sample> sampleQueue = new ConcurrentLinkedQueue<>();

        // Rate-limited worker: each of N workers sleeps such that combined TPS ~ TARGET_TPS.
        // sleepMs per op per worker = (1000 / TARGET_TPS) * WORKER_THREADS.
        long sleepMsPerOp = (1000L / TARGET_TPS) * WORKER_THREADS;

        var workers = Executors.newFixedThreadPool(WORKER_THREADS);
        for (int i = 0; i < WORKER_THREADS; i++) {
            workers.submit(() -> {
                Random rng = new Random(Thread.currentThread().threadId());
                while (System.nanoTime() < deadline) {
                    try {
                        String docId = "doc-" + rng.nextInt(500_000);
                        String userId = "user-" + rng.nextInt(10_000);
                        client.on("document").resource(docId).check("view")
                                .withConsistency(Consistency.minimizeLatency())
                                .by(userId);
                        ops.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    try {
                        Thread.sleep(sleepMsPerOp);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        ScheduledExecutorService samplerPool = Executors.newSingleThreadScheduledExecutor();
        samplerPool.scheduleAtFixedRate(
                () -> sampleQueue.add(sampler.sample(client, startSec)),
                0L, SAMPLE_PERIOD_MS, TimeUnit.MILLISECONDS);

        workers.shutdown();
        workers.awaitTermination(durationMs + 60_000L, TimeUnit.MILLISECONDS);

        samplerPool.shutdownNow();
        samplerPool.awaitTermination(5, TimeUnit.SECONDS);

        samples.addAll(sampleQueue);

        long elapsedMs = System.currentTimeMillis() - startMs;
        Map<String, Object> summary = Map.of(
                "durationMs", elapsedMs,
                "durationMinutes", durationMinutes,
                "targetTps", TARGET_TPS,
                "ops", ops.get(),
                "errors", errors.get(),
                "achievedTps", ops.get() * 1000.0 / Math.max(1, elapsedMs));

        return Map.of("samples", samples, "summary", summary);
    }
}
