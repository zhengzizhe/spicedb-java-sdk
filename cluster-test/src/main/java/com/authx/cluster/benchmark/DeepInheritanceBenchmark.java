package com.authx.cluster.benchmark;

import com.authx.cluster.generator.DataModel;
import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * B4 — Deep inheritance: check permissions on documents in 20-layer folder chains.
 * Exercises SpiceDB's ancestor parallel dispatch.
 */
@Component
public class DeepInheritanceBenchmark {

    private final AuthxClient client;
    private final BenchmarkRunner runner;

    public DeepInheritanceBenchmark(AuthxClient client, BenchmarkRunner runner) {
        this.client = client;
        this.runner = runner;
    }

    public BenchmarkResult run() {
        var docFactory = client.on("document");

        return runner.run("B4-deep-inheritance", () -> {
            var rng = ThreadLocalRandom.current();
            // Documents in deep folders: the generator distributes docs evenly across folders.
            // Folders are organized in chains up to depth 20.
            // Picking random docs exercises various tree depths.
            String docId = DataModel.docId(rng.nextInt(DataModel.DOC_COUNT));
            String userId = DataModel.userId(rng.nextInt(DataModel.USER_COUNT));
            docFactory.check(docId, "view", userId);
        });
    }
}
