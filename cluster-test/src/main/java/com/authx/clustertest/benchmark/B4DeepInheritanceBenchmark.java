package com.authx.clustertest.benchmark;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;
import org.springframework.stereotype.Component;

/**
 * B4 — Deep inheritance:
 * checks {@code view} permission on documents that resolve through a deep
 * folder ancestor chain. We don't know which specific docs are in depth
 * 15-20 folders at SDK level, so we sample from the full doc range and rely
 * on the generator's distribution to produce many deep paths. This still
 * exercises the ancestor-dispatch fan-out latency that the benchmark targets.
 */
@Component
public class B4DeepInheritanceBenchmark {

    private final AuthxClient client;

    public B4DeepInheritanceBenchmark(AuthxClient c) { this.client = c; }

    public BenchmarkResult run(int threads, long durationMs) throws InterruptedException {
        return new ScenarioRunner().run("B4-DeepInheritance", threads, durationMs, rng -> {
            String docId = "doc-" + rng.nextInt(500_000);
            String userId = "user-" + rng.nextInt(10_000);
            client.on("document").resource(docId).check("view")
                    .withConsistency(Consistency.minimizeLatency())
                    .by(userId);
        });
    }
}
