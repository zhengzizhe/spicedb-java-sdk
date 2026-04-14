package com.authx.clustertest.benchmark;

import com.authx.sdk.AuthxClient;
import org.springframework.stereotype.Component;

/**
 * B2 — Write-heavy workload:
 * 60% grant, 30% revoke, 10% deleteByFilter (revokeAll for all relations
 * a specific user holds on the doc).
 */
@Component
public class B2WriteBenchmark {

    private static final String[] RELATIONS = {"viewer", "editor", "owner"};

    private final AuthxClient client;

    public B2WriteBenchmark(AuthxClient c) { this.client = c; }

    public BenchmarkResult run(int threads, long durationMs) throws InterruptedException {
        return new ScenarioRunner().run("B2-Write", threads, durationMs, rng -> {
            int kind = rng.nextInt(100);
            String docId = "doc-" + rng.nextInt(500_000);
            String userId = "user-" + rng.nextInt(10_000);
            String relation = RELATIONS[rng.nextInt(RELATIONS.length)];
            if (kind < 60) {
                client.on("document").resource(docId).grant(relation).to(userId);
            } else if (kind < 90) {
                client.on("document").resource(docId).revoke(relation).from(userId);
            } else {
                // deleteByFilter: revoke all relations the user holds on this doc
                client.on("document").resource(docId).revokeAll().from(userId);
            }
        });
    }
}
