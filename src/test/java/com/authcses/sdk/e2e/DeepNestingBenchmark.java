package com.authcses.sdk.e2e;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.model.Consistency;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmark: 10-level folder nesting with inherited permissions.
 *
 * Structure:
 *   folder:root → folder:L1 → folder:L2 → ... → folder:L9 → document:deep-doc
 *
 * Permissions granted at root propagate 10 levels down via parent->edit/view.
 * Tests SpiceDB's recursive permission computation performance.
 */
public class DeepNestingBenchmark {

    static final String ENDPOINT = "http://localhost:8090";
    static final String API_KEY = "ak_30ac4b596f8bd12c5bf4202faccd36bc";
    static final String NAMESPACE = "dev";
    static final int DEPTH = 10;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 10-Level Nested Folder Benchmark ===\n");

        try (var client = AuthCsesClient.builder()
                .connection(c -> c.target("localhost:50051").presharedKey("dev-token")
                        .requestTimeout(Duration.ofSeconds(15)))
                .cache(c -> c.enabled(false))
                .features(f -> f.telemetry(false).coalescing(false))
                .build()) {

            // --- Step 1: Build the nesting chain ---
            System.out.println("[Setup] Building 10-level folder chain...");
            buildNestingChain(client);

            // --- Step 2: Grant permissions at root ---
            System.out.println("[Setup] Granting permissions at root level...");
            grantRootPermissions(client);

            // --- Step 3: Verify inheritance ---
            System.out.println("\n--- Verify permission inheritance ---");
            verifyInheritance(client);

            // --- Step 4: Benchmark check at each depth ---
            System.out.println("\n--- Check latency by depth (single thread, 1000 iterations each) ---");
            benchmarkByDepth(client, 1000);

            // --- Step 5: Concurrent benchmark at deepest level ---
            System.out.println("\n--- Concurrent check at depth 10 (document through 10 folders) ---");
            benchmarkConcurrent(client, "document", "deep-doc-0", "view",
                    20, 2000, "20 threads, no cache");

            // --- Step 6: Multiple nesting chains ---
            System.out.println("\n--- Building 50 parallel 10-level chains ---");
            buildParallelChains(client, 50);

            System.out.println("\n--- Concurrent check across 50 chains (depth 10) ---");
            benchmarkConcurrentMultiChain(client, 50, 20, 2000, "50 chains × 20 threads");

            // --- Step 7: With cache ---
            System.out.println("\n--- Same test WITH L1 cache ---");
            try (var cachedClient = AuthCsesClient.builder()
                    .connection(c -> c.target("localhost:50051").presharedKey("dev-token"))
                    .cache(c -> c.enabled(true))
                    .features(f -> f.telemetry(false))
                    .build()) {
                // Warm cache
                for (int c = 0; c < 50; c++) {
                    cachedClient.resource("document", "deep-doc-" + c)
                            .check("view").by("root-viewer");
                }
                benchmarkConcurrentMultiChain(cachedClient, 50, 20, 2000, "50 chains × 20 threads (cached)");
            }

            // --- Cleanup ---
            System.out.println("\n[Cleanup] Removing test data...");
            cleanup(client, 50);
            System.out.println("[Cleanup] Done.");
        }

        System.out.println("\n=== Done ===");
    }

    /**
     * Build: root → L1 → L2 → ... → L9 → deep-doc-0
     */
    static void buildNestingChain(AuthCsesClient client) {
        buildChain(client, 0);
    }

    static void buildChain(AuthCsesClient client, int chainId) {
        // L0's parent = root
        client.resource("folder", "L0-" + chainId)
                .grant("parent").toSubjects("folder:root");

        // L1..L9
        for (int i = 1; i < DEPTH; i++) {
            client.resource("folder", "L" + i + "-" + chainId)
                    .grant("parent").toSubjects("folder:L" + (i - 1) + "-" + chainId);
        }

        // document at bottom
        client.resource("document", "deep-doc-" + chainId)
                .grant("parent").toSubjects("folder:L" + (DEPTH - 1) + "-" + chainId);
    }

    static void buildParallelChains(AuthCsesClient client, int count) {
        for (int c = 1; c < count; c++) { // chain 0 already built
            buildChain(client, c);
        }
        System.out.println("  Built " + count + " chains, each " + DEPTH + " levels deep");
    }

    static void grantRootPermissions(AuthCsesClient client) {
        // editor at root → inherits edit+view all the way down
        client.resource("folder", "root").grant("editor").to("root-editor");
        // viewer at root → inherits view all the way down
        client.resource("folder", "root").grant("viewer").to("root-viewer");
        // owner at root → inherits all permissions down
        client.resource("folder", "root").grant("owner").to("root-owner");
        // direct editor at L5 → inherits only from L5 down
        client.resource("folder", "L5-0").grant("editor").to("mid-editor");

        System.out.println("  root-editor: edit+view at all levels");
        System.out.println("  root-viewer: view at all levels");
        System.out.println("  root-owner:  all permissions at all levels");
        System.out.println("  mid-editor:  edit+view from L5 down only");
    }

    static void verifyInheritance(AuthCsesClient client) {
        String[][] checks = {
                {"folder", "root", "view", "root-viewer", "true", "direct viewer"},
                {"folder", "L0-0", "view", "root-viewer", "true", "depth 1 inherit"},
                {"folder", "L4-0", "view", "root-viewer", "true", "depth 5 inherit"},
                {"folder", "L9-0", "view", "root-viewer", "true", "depth 10 inherit"},
                {"document", "deep-doc-0", "view", "root-viewer", "true", "doc through 10 folders"},
                {"document", "deep-doc-0", "edit", "root-editor", "true", "edit through 10 folders"},
                {"document", "deep-doc-0", "delete", "root-owner", "true", "delete through 10 folders"},
                {"document", "deep-doc-0", "edit", "mid-editor", "true", "mid-editor from L5"},
                {"folder", "L3-0", "edit", "mid-editor", "false", "mid-editor above L5"},
                {"document", "deep-doc-0", "view", "nobody", "false", "no access"},
        };

        for (var c : checks) {
            CheckResult r = client.resource(c[0], c[1]).check(c[2])
                    .withConsistency(Consistency.full()).by(c[3]);
            String expected = c[4];
            boolean pass = String.valueOf(r.hasPermission()).equals(expected);
            System.out.printf("  %s %s(%s, %s).%s by %s = %s %s%n",
                    pass ? "✓" : "✗", c[0], c[1], c[2], c[2], c[3],
                    r.hasPermission(), pass ? "" : "(EXPECTED " + expected + ")");
        }
    }

    static void benchmarkByDepth(AuthCsesClient client, int iterations) {
        // Check view by root-viewer at each depth
        String[][] targets = {
                {"folder", "root"},       // depth 0 (direct)
                {"folder", "L0-0"},       // depth 1
                {"folder", "L2-0"},       // depth 3
                {"folder", "L4-0"},       // depth 5
                {"folder", "L6-0"},       // depth 7
                {"folder", "L9-0"},       // depth 10
                {"document", "deep-doc-0"}, // depth 10 + doc
        };

        for (var t : targets) {
            long[] nanos = new long[iterations];
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                client.resource(t[0], t[1]).check("view").by("root-viewer");
                nanos[i] = System.nanoTime() - start;
            }
            Arrays.sort(nanos);
            double p50 = nanos[(int) (iterations * 0.50)] / 1_000_000.0;
            double p95 = nanos[(int) (iterations * 0.95)] / 1_000_000.0;
            double p99 = nanos[(int) (iterations * 0.99)] / 1_000_000.0;
            double avg = Arrays.stream(nanos).average().orElse(0) / 1_000_000.0;
            String label = t[0].equals("document") ? "doc(depth=10)" : t[1].replace("-0", "");
            System.out.printf("  %-18s avg=%.2fms  p50=%.2fms  p95=%.2fms  p99=%.2fms%n",
                    label, avg, p50, p95, p99);
        }
    }

    static void benchmarkConcurrent(AuthCsesClient client, String type, String id,
                                     String permission, int threads, int opsPerThread,
                                     String label) throws Exception {
        int totalOps = threads * opsPerThread;
        long[] latencies = new long[totalOps];
        AtomicLong idx = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        long startTime = System.nanoTime();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try { barrier.await(); } catch (Exception e) { return; }
                for (int i = 0; i < opsPerThread; i++) {
                    long opStart = System.nanoTime();
                    client.resource(type, id).check(permission).by("root-viewer");
                    long opNanos = System.nanoTime() - opStart;
                    int pos = (int) idx.getAndIncrement();
                    if (pos < latencies.length) latencies[pos] = opNanos;
                }
            }));
        }
        for (var f : futures) f.get();
        long elapsed = System.nanoTime() - startTime;
        pool.shutdown();

        printResults(label, threads, opsPerThread, elapsed, latencies, (int) idx.get());
    }

    static void benchmarkConcurrentMultiChain(AuthCsesClient client, int chains,
                                               int threads, int opsPerThread,
                                               String label) throws Exception {
        int totalOps = threads * opsPerThread;
        long[] latencies = new long[totalOps];
        AtomicLong idx = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        long startTime = System.nanoTime();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try { barrier.await(); } catch (Exception e) { return; }
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                    int chainId = rng.nextInt(chains);
                    long opStart = System.nanoTime();
                    client.resource("document", "deep-doc-" + chainId)
                            .check("view").by("root-viewer");
                    long opNanos = System.nanoTime() - opStart;
                    int pos = (int) idx.getAndIncrement();
                    if (pos < latencies.length) latencies[pos] = opNanos;
                }
            }));
        }
        for (var f : futures) f.get();
        long elapsed = System.nanoTime() - startTime;
        pool.shutdown();

        printResults(label, threads, opsPerThread, elapsed, latencies, (int) idx.get());
    }

    static void cleanup(AuthCsesClient client, int chains) {
        try {
            client.resource("folder", "root").revoke("editor").from("root-editor");
            client.resource("folder", "root").revoke("viewer").from("root-viewer");
            client.resource("folder", "root").revoke("owner").from("root-owner");
            client.resource("folder", "L5-0").revoke("editor").from("mid-editor");
        } catch (Exception ignored) {}

        for (int c = 0; c < chains; c++) {
            try {
                client.resource("document", "deep-doc-" + c).revoke("parent")
                        .fromSubjects("folder:L" + (DEPTH - 1) + "-" + c);
                for (int i = DEPTH - 1; i >= 1; i--) {
                    client.resource("folder", "L" + i + "-" + c).revoke("parent")
                            .fromSubjects("folder:L" + (i - 1) + "-" + c);
                }
                client.resource("folder", "L0-" + c).revoke("parent")
                        .fromSubjects("folder:root");
            } catch (Exception ignored) {}
        }
    }

    static void printResults(String name, int threads, int opsPerThread,
                              long elapsedNanos, long[] latencies, int captured) {
        long[] sorted = Arrays.copyOf(latencies, captured);
        Arrays.sort(sorted);
        double elapsedMs = elapsedNanos / 1_000_000.0;
        int totalOps = threads * opsPerThread;
        double qps = totalOps / (elapsedMs / 1000.0);
        double p50 = sorted[(int) (captured * 0.50)] / 1_000_000.0;
        double p95 = sorted[(int) (captured * 0.95)] / 1_000_000.0;
        double p99 = sorted[(int) (captured * 0.99)] / 1_000_000.0;
        double avg = Arrays.stream(sorted).average().orElse(0) / 1_000_000.0;

        System.out.printf("  %s: %d threads × %,d ops = %,d total%n", name, threads, opsPerThread, totalOps);
        System.out.printf("  Elapsed: %.0fms | QPS: %,.0f%n", elapsedMs, qps);
        System.out.printf("  Latency: avg=%.2fms  p50=%.2fms  p95=%.2fms  p99=%.2fms%n", avg, p50, p95, p99);
    }
}
