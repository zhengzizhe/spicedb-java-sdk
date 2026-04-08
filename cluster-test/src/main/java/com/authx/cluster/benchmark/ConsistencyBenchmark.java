package com.authx.cluster.benchmark;

import com.authx.cluster.generator.DataModel;
import com.authx.sdk.AuthxClient;
import org.HdrHistogram.Histogram;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * B3 — Cache consistency: measure Watch invalidation delay across instances.
 *
 * Writes on this instance, polls another instance until it sees the change.
 */
@Component
public class ConsistencyBenchmark {

    private static final System.Logger LOG = System.getLogger(ConsistencyBenchmark.class.getName());
    private static final int ITERATIONS = 50;

    private final AuthxClient client;
    private final int nodeIndex;

    public ConsistencyBenchmark(AuthxClient client,
                                @Value("${cluster.node-index:0}") int nodeIndex) {
        this.client = client;
        this.nodeIndex = nodeIndex;
    }

    public BenchmarkResult run() {
        // Pick a peer instance port
        int[] ports = {8091, 8092, 8093};
        int peerPort = ports[(nodeIndex + 1) % 3];

        var histogram = new Histogram(60_000_000L, 3);
        var errors = new AtomicInteger();
        var httpClient = HttpClient.newHttpClient();

        LOG.log(System.Logger.Level.INFO, "[B3-consistency] Starting {0} iterations, peer port: {1}",
                ITERATIONS, peerPort);

        long startTime = System.nanoTime();

        for (int i = 0; i < ITERATIONS; i++) {
            String docId = "consistency-test-" + System.nanoTime();
            String userId = DataModel.userId(ThreadLocalRandom.current().nextInt(DataModel.USER_COUNT));

            try {
                // Write: grant viewer on a new doc
                client.on("document").resource(docId).grant("viewer").to(userId);

                // Poll peer until it sees the permission (or timeout at 30s)
                long pollStart = System.nanoTime();
                boolean seen = false;
                for (int attempt = 0; attempt < 300; attempt++) {
                    try {
                        var req = HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + peerPort +
                                        "/test/check?type=document&id=" + docId +
                                        "&permission=view&user=" + userId))
                                .GET().build();
                        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                        if (resp.body().contains("true")) {
                            seen = true;
                            break;
                        }
                    } catch (Exception ignored) {
                        // Peer might not be running — fall back to local check
                    }
                    Thread.sleep(100);
                }

                if (seen) {
                    long delayUs = (System.nanoTime() - pollStart) / 1_000;
                    histogram.recordValue(Math.min(delayUs, 60_000_000L));
                } else {
                    errors.incrementAndGet();
                }

                // Cleanup
                client.on("document").resource(docId).revoke("viewer").from(userId);

            } catch (Exception e) {
                errors.incrementAndGet();
                LOG.log(System.Logger.Level.WARNING, "[B3] Iteration {0} failed: {1}", i, e.getMessage());
            }
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        return new BenchmarkResult("B3-consistency", ITERATIONS,
                ITERATIONS * 1000.0 / Math.max(durationMs, 1),
                histogram.getValueAtPercentile(50),
                histogram.getValueAtPercentile(99),
                histogram.getValueAtPercentile(99.9),
                errors.get(),
                errors.get() > 0 ? (double) errors.get() / ITERATIONS : 0,
                durationMs);
    }
}
