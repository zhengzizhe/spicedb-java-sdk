package com.authx.clustertest.benchmark;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.action.BatchBuilder;
import org.springframework.stereotype.Component;

/**
 * B5 — Batch writes:
 * each operation issues a single {@code WriteRelationships} RPC containing
 * 100 mixed grant/revoke entries on a random document. Exercises the batch
 * path and validates that bulk writes scale with fewer round-trips.
 */
@Component
public class B5BatchBenchmark {

    private static final int BATCH_SIZE = 100;

    private final AuthxClient client;

    public B5BatchBenchmark(AuthxClient c) { this.client = c; }

    public BenchmarkResult run(int threads, long durationMs) throws InterruptedException {
        return new ScenarioRunner().run("B5-Batch", threads, durationMs, rng -> {
            String docId = "doc-" + rng.nextInt(500_000);
            BatchBuilder batch = client.on("document").resource(docId).batch();
            for (int i = 0; i < BATCH_SIZE; i++) {
                String userId = "user-" + rng.nextInt(10_000);
                if (rng.nextBoolean()) {
                    batch.grant("viewer").to(userId);
                } else {
                    batch.revoke("viewer").from(userId);
                }
            }
            batch.execute();
        });
    }
}
