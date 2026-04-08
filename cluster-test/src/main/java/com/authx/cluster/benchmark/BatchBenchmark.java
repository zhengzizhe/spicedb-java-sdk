package com.authx.cluster.benchmark;

import com.authx.cluster.generator.DataModel;
import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * B5 — Batch operations: grant/revoke 100 relations per batch.
 */
@Component
public class BatchBenchmark {

    private static final int BATCH_SIZE = 100;

    private final AuthxClient client;
    private final BenchmarkRunner runner;

    public BatchBenchmark(AuthxClient client, BenchmarkRunner runner) {
        this.client = client;
        this.runner = runner;
    }

    public BenchmarkResult run() {
        return runner.run("B5-batch", () -> {
            var rng = ThreadLocalRandom.current();
            String docId = DataModel.docId(rng.nextInt(DataModel.DOC_COUNT));
            var handle = client.on("document").resource(docId);

            // Batch grant
            var batch = handle.batch();
            for (int i = 0; i < BATCH_SIZE; i++) {
                String userId = DataModel.userId(rng.nextInt(DataModel.USER_COUNT));
                batch.grant("viewer").to(userId);
            }
            batch.execute();

            // Batch revoke (same doc, different random users — intentionally not matching)
            var revokeBatch = handle.batch();
            for (int i = 0; i < BATCH_SIZE; i++) {
                String userId = DataModel.userId(rng.nextInt(DataModel.USER_COUNT));
                revokeBatch.revoke("viewer").from(userId);
            }
            revokeBatch.execute();
        });
    }
}
