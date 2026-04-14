package com.authx.clustertest.benchmark;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * B3 — Cross-instance consistency:
 * on each iteration, (a) grant or revoke a relation, then (b) immediately read
 * back the same (doc, user) pair with {@link Consistency#full()} and verify
 * that the newly written state is visible. Stale reads (whether
 * just-granted-but-missing or just-revoked-but-still-there) are surfaced in
 * the result's error-by-type map under {@code "StaleRead"}.
 */
@Component
public class B3ConsistencyBenchmark {

    private final AuthxClient client;

    public B3ConsistencyBenchmark(AuthxClient c) { this.client = c; }

    public BenchmarkResult run(int threads, long durationMs) throws InterruptedException {
        AtomicLong stale = new AtomicLong();
        BenchmarkResult base = new ScenarioRunner().run("B3-Consistency", threads, durationMs, rng -> {
            String docId = "doc-" + rng.nextInt(500_000);
            String userId = "user-" + rng.nextInt(10_000);
            boolean grant = rng.nextBoolean();
            if (grant) {
                client.on("document").resource(docId).grant("viewer").to(userId);
                boolean has = client.on("document").resource(docId).check("view")
                        .withConsistency(Consistency.full())
                        .by(userId)
                        .hasPermission();
                if (!has) stale.incrementAndGet();
            } else {
                client.on("document").resource(docId).revoke("viewer").from(userId);
                // After revoke, we can't guarantee absence of `view` (other paths
                // like editor/owner may still grant it) — so compare via direct
                // relationship read on viewer relation only.
                boolean stillViewer = client.on("document").resource(docId).relations("viewer")
                        .withConsistency(Consistency.full())
                        .fetch()
                        .stream()
                        .anyMatch(t -> userId.equals(t.subjectId()));
                if (stillViewer) stale.incrementAndGet();
            }
        });

        // Merge stale-count into errorsByType for surfacing in the result JSON.
        var merged = new java.util.HashMap<>(base.errorsByType());
        merged.merge("StaleRead", stale.get(), Long::sum);
        return new BenchmarkResult(
                base.scenario(), base.threads(), base.durationMs(), base.ops(), base.tps(),
                base.p50us(), base.p90us(), base.p99us(), base.p999us(), base.maxUs(),
                base.errors() + stale.get(),
                java.util.Map.copyOf(merged));
    }
}
