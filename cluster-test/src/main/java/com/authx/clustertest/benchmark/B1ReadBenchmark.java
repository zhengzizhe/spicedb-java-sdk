package com.authx.clustertest.benchmark;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;
import org.springframework.stereotype.Component;

/**
 * B1 — Read-heavy workload:
 * 70% check, 10% checkAll, 10% lookupSubjects (who can view this doc),
 * 5% lookupResources (which docs can user view), 5% readRelationships.
 * All reads use minimizeLatency consistency to exercise the cache.
 */
@Component
public class B1ReadBenchmark {

    private final AuthxClient client;

    public B1ReadBenchmark(AuthxClient c) { this.client = c; }

    public BenchmarkResult run(int threads, long durationMs) throws InterruptedException {
        return new ScenarioRunner().run("B1-Read", threads, durationMs, rng -> {
            int kind = rng.nextInt(100);
            String docId = "doc-" + rng.nextInt(500_000);
            String userId = "user-" + rng.nextInt(10_000);
            if (kind < 70) {
                client.on("document").resource(docId).check("view")
                        .withConsistency(Consistency.minimizeLatency())
                        .by(userId);
            } else if (kind < 80) {
                client.on("document").resource(docId).checkAll("view", "edit", "manage")
                        .withConsistency(Consistency.minimizeLatency())
                        .by(userId);
            } else if (kind < 90) {
                client.on("document").resource(docId).who()
                        .withPermission("view")
                        .withConsistency(Consistency.minimizeLatency())
                        .limit(50)
                        .fetch();
            } else if (kind < 95) {
                client.lookup("document")
                        .withPermission("view")
                        .by(userId)
                        .withConsistency(Consistency.minimizeLatency())
                        .limit(50)
                        .fetch();
            } else {
                client.on("document").resource(docId).relations()
                        .withConsistency(Consistency.minimizeLatency())
                        .fetch();
            }
        });
    }
}
