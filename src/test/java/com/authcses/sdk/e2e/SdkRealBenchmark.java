package com.authcses.sdk.e2e;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.model.Consistency;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real SpiceDB performance benchmark through the full SDK stack.
 * Platform (localhost:8090) → /sdk/connect → SpiceDB (localhost:50051).
 *
 * Run: java SdkRealBenchmark
 */
public class SdkRealBenchmark {

    static final String ENDPOINT = "http://localhost:8090";
    static final String API_KEY = "ak_30ac4b596f8bd12c5bf4202faccd36bc";
    static final String NAMESPACE = "dev";

    public static void main(String[] args) throws Exception {
        System.out.println("=== SDK Real SpiceDB Benchmark ===\n");

        // --- Phase 0: Setup test data ---
        System.out.println("[Setup] Seeding test data...");
        try (var setupClient = buildClient(false)) {
            seedData(setupClient, 100, 20);
        }
        System.out.println("[Setup] Done: 100 docs × 20 users (~20% editor rate)\n");

        // --- Benchmark 1: No cache (every check hits SpiceDB) ---
        System.out.println("--- 1. No cache (every request → SpiceDB gRPC) ---");
        try (var client = buildClient(false)) {
            runCheckBenchmark("No cache", client, 10, 5_000, 100, 20);
        }

        // --- Benchmark 2: With L1 cache (warm) ---
        System.out.println("\n--- 2. L1 Cache enabled (Caffeine, TTL=10s) ---");
        try (var client = buildClient(true)) {
            // Warm cache
            warmCache(client, 100, 20);
            System.out.println("  [Cache warmed: " + 100 * 20 + " entries]");
            runCheckBenchmark("L1 Cache warm", client, 10, 5_000, 100, 20);
        }

        // --- Benchmark 3: Write throughput ---
        System.out.println("\n--- 3. Write throughput (grant/revoke) ---");
        try (var client = buildClient(false)) {
            runWriteBenchmark("Write (grant+revoke)", client, 10, 2_000);
        }

        // --- Benchmark 4: Lookup throughput ---
        System.out.println("\n--- 4. Lookup resources ---");
        try (var client = buildClient(false)) {
            runLookupBenchmark("Lookup resources", client, 10, 1_000, 20);
        }

        // --- Benchmark 5: High concurrency check (50 threads) ---
        System.out.println("\n--- 5. High concurrency check (50 threads, no cache) ---");
        try (var client = buildClient(false)) {
            runCheckBenchmark("50-thread check", client, 50, 2_000, 100, 20);
        }

        // --- Benchmark 6: High concurrency check with cache ---
        System.out.println("\n--- 6. High concurrency check (50 threads, cache warm) ---");
        try (var client = buildClient(true)) {
            warmCache(client, 100, 20);
            runCheckBenchmark("50-thread cached", client, 50, 2_000, 100, 20);
        }

        // --- Cleanup ---
        System.out.println("\n[Cleanup] Removing test data...");
        try (var client = buildClient(false)) {
            cleanupData(client, 100, 20);
        }
        System.out.println("[Cleanup] Done.");
        System.out.println("\n=== Benchmark Complete ===");
    }

    static AuthCsesClient buildClient(boolean cacheEnabled) {
        return AuthCsesClient.builder()
                .target("localhost:50051")
                
                .presharedKey("dev-token")
                .requestTimeout(Duration.ofSeconds(10))
                .cacheEnabled(cacheEnabled)
                
                .telemetryEnabled(false)  // don't pollute telemetry during benchmarks
                .build();
    }

    // ---- Check benchmark ----
    static void runCheckBenchmark(String name, AuthCsesClient client, int threads, int opsPerThread,
                                   int docCount, int userCount) throws Exception {
        int totalOps = threads * opsPerThread;
        long[] latencies = new long[totalOps];
        AtomicLong idx = new AtomicLong(0);
        AtomicLong errors = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        long startTime = System.nanoTime();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try { barrier.await(); } catch (Exception e) { return; }
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                    String docId = "bench-doc-" + rng.nextInt(docCount);
                    String userId = "bench-user-" + rng.nextInt(userCount);
                    long opStart = System.nanoTime();
                    try {
                        client.resource("document", docId).check("editor").by(userId);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    long opNanos = System.nanoTime() - opStart;
                    int pos = (int) idx.getAndIncrement();
                    if (pos < latencies.length) latencies[pos] = opNanos;
                }
            }));
        }
        for (var f : futures) f.get();
        long elapsed = System.nanoTime() - startTime;
        pool.shutdown();

        printResults(name, threads, opsPerThread, elapsed, latencies, (int) idx.get(), errors.get());
    }

    // ---- Write benchmark ----
    static void runWriteBenchmark(String name, AuthCsesClient client, int threads, int opsPerThread) throws Exception {
        int totalOps = threads * opsPerThread;
        long[] latencies = new long[totalOps];
        AtomicLong idx = new AtomicLong(0);
        AtomicLong errors = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        long startTime = System.nanoTime();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                try { barrier.await(); } catch (Exception e) { return; }
                for (int i = 0; i < opsPerThread; i++) {
                    String docId = "bench-write-" + threadId + "-" + i;
                    long opStart = System.nanoTime();
                    try {
                        client.resource("document", docId).grant("reader").to("bench-writer");
                        client.resource("document", docId).revoke("reader").from("bench-writer");
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    long opNanos = System.nanoTime() - opStart;
                    int pos = (int) idx.getAndIncrement();
                    if (pos < latencies.length) latencies[pos] = opNanos;
                }
            }));
        }
        for (var f : futures) f.get();
        long elapsed = System.nanoTime() - startTime;
        pool.shutdown();

        printResults(name, threads, opsPerThread, elapsed, latencies, (int) idx.get(), errors.get());
    }

    // ---- Lookup benchmark ----
    static void runLookupBenchmark(String name, AuthCsesClient client, int threads, int opsPerThread,
                                    int userCount) throws Exception {
        int totalOps = threads * opsPerThread;
        long[] latencies = new long[totalOps];
        AtomicLong idx = new AtomicLong(0);
        AtomicLong errors = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        long startTime = System.nanoTime();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try { barrier.await(); } catch (Exception e) { return; }
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                    String userId = "bench-user-" + rng.nextInt(userCount);
                    long opStart = System.nanoTime();
                    try {
                        client.lookup("document").withPermission("editor").by(userId).fetch();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    long opNanos = System.nanoTime() - opStart;
                    int pos = (int) idx.getAndIncrement();
                    if (pos < latencies.length) latencies[pos] = opNanos;
                }
            }));
        }
        for (var f : futures) f.get();
        long elapsed = System.nanoTime() - startTime;
        pool.shutdown();

        printResults(name, threads, opsPerThread, elapsed, latencies, (int) idx.get(), errors.get());
    }

    // ---- Helpers ----

    static void seedData(AuthCsesClient client, int docCount, int userCount) {
        for (int d = 0; d < docCount; d++) {
            List<String> editors = new ArrayList<>();
            for (int u = 0; u < userCount; u++) {
                if ((d + u) % 5 == 0) {
                    editors.add("bench-user-" + u);
                }
            }
            if (!editors.isEmpty()) {
                client.resource("document", "bench-doc-" + d)
                        .grant("editor").to(editors);
            }
        }
    }

    static void warmCache(AuthCsesClient client, int docCount, int userCount) {
        for (int d = 0; d < docCount; d++) {
            for (int u = 0; u < userCount; u++) {
                client.resource("document", "bench-doc-" + d).check("editor").by("bench-user-" + u);
            }
        }
    }

    static void cleanupData(AuthCsesClient client, int docCount, int userCount) {
        for (int d = 0; d < docCount; d++) {
            for (int u = 0; u < userCount; u++) {
                if ((d + u) % 5 == 0) {
                    try {
                        client.resource("document", "bench-doc-" + d)
                                .revoke("editor").from("bench-user-" + u);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    static void printResults(String name, int threads, int opsPerThread,
                              long elapsedNanos, long[] latencies, int captured, long errors) {
        long[] sorted = Arrays.copyOf(latencies, captured);
        Arrays.sort(sorted);

        double elapsedMs = elapsedNanos / 1_000_000.0;
        int totalOps = threads * opsPerThread;
        double qps = totalOps / (elapsedMs / 1000.0);
        double p50Ms = sorted[(int) (captured * 0.50)] / 1_000_000.0;
        double p95Ms = sorted[(int) (captured * 0.95)] / 1_000_000.0;
        double p99Ms = sorted[(int) (captured * 0.99)] / 1_000_000.0;
        double maxMs = sorted[captured - 1] / 1_000_000.0;
        double avgMs = Arrays.stream(sorted).average().orElse(0) / 1_000_000.0;

        System.out.printf("  %s: %d threads × %,d ops = %,d total%n", name, threads, opsPerThread, totalOps);
        System.out.printf("  Elapsed: %.0f ms | QPS: %,.0f | Errors: %d%n", elapsedMs, qps, errors);
        System.out.printf("  Latency (ms): avg=%.2f  p50=%.2f  p95=%.2f  p99=%.2f  max=%.1f%n",
                avgMs, p50Ms, p95Ms, p99Ms, maxMs);
    }
}
