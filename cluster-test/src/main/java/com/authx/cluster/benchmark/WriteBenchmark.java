package com.authx.cluster.benchmark;

import com.authx.cluster.generator.DataModel;
import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * B2 — Write benchmark: grant + revoke throughput.
 */
@Component
public class WriteBenchmark {

    private final AuthxClient client;
    private final BenchmarkRunner runner;

    public WriteBenchmark(AuthxClient client, BenchmarkRunner runner) {
        this.client = client;
        this.runner = runner;
    }

    public BenchmarkResult run() {
        var docFactory = client.on("document");

        return runner.run("B2-write", () -> {
            var rng = ThreadLocalRandom.current();
            String docId = DataModel.docId(rng.nextInt(DataModel.DOC_COUNT));
            String userId = DataModel.userId(rng.nextInt(DataModel.USER_COUNT));

            docFactory.grant(docId, "viewer", userId);
            docFactory.revoke(docId, "viewer", userId);
        });
    }
}
