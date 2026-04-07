package com.authx.sdk;

import com.authx.sdk.cache.CaffeineCache;
import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.model.*;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.transport.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * SDK concurrency performance benchmark.
 * Tests throughput and latency under concurrent load through the full transport chain.
 *
 * Run: ./gradlew :sdk-core:test --tests "*.SdkConcurrencyBenchmark" --rerun
 */
public class SdkConcurrencyBenchmark {

    public static void main(String[] args) throws Exception {
        System.out.println("=== SDK Concurrency Benchmark ===\n");

        // Setup: 100 documents, 50 users, pre-grant relationships
        int docCount = 100;
        int userCount = 50;

        // --- Benchmark 1: InMemory baseline (no middleware) ---
        System.out.println("--- 1. InMemory Transport (raw, no cache/circuit/coalescing) ---");
        var rawTransport = new InMemoryTransport();
        seedData(rawTransport, docCount, userCount);
        runBenchmark("InMemory raw", rawTransport, docCount, userCount);

        // --- Benchmark 2: With Caffeine Cache ---
        System.out.println("\n--- 2. InMemory + L1 Cache (Caffeine, TTL=10s) ---");
        var cachedTransport = new CachedTransport(
                new InMemoryTransport(), new CaffeineCache<>(100_000, Duration.ofSeconds(10), com.authx.sdk.model.CheckKey::resourceIndex));
        seedData(cachedTransport, docCount, userCount);
        // Warm up cache
        warmCache(cachedTransport, docCount, userCount);
        runBenchmark("InMemory + Cache", cachedTransport, docCount, userCount);

        // --- Benchmark 3: With Coalescing ---
        System.out.println("\n--- 3. InMemory + Coalescing (same request dedup) ---");
        var inner3 = new InMemoryTransport();
        seedData(inner3, docCount, userCount);
        var coalescingTransport = new CoalescingTransport(inner3);
        runBenchmark("InMemory + Coalescing", coalescingTransport, docCount, userCount);

        // --- Benchmark 4: Full chain ---
        System.out.println("\n--- 4. Full Chain (Coalescing → Cache → Resilient → InMemory) ---");
        var inner4 = new InMemoryTransport();
        seedData(inner4, docCount, userCount);
        SdkTransport fullChain = inner4;
        fullChain = new ResilientTransport(fullChain, PolicyRegistry.withDefaults(), new DefaultTypedEventBus());
        fullChain = new CachedTransport(fullChain, new CaffeineCache<>(100_000, Duration.ofSeconds(10), com.authx.sdk.model.CheckKey::resourceIndex));
        fullChain = new CoalescingTransport(fullChain);
        warmCache(fullChain, docCount, userCount);
        runBenchmark("Full chain", fullChain, docCount, userCount);

        // --- Benchmark 5: Full chain, cache cold (worst case) ---
        System.out.println("\n--- 5. Full Chain, cold cache (cache miss every time) ---");
        var inner5 = new InMemoryTransport();
        seedData(inner5, docCount, userCount);
        SdkTransport coldChain = inner5;
        coldChain = new ResilientTransport(coldChain, PolicyRegistry.withDefaults(), new DefaultTypedEventBus());
        coldChain = new CachedTransport(coldChain, new CaffeineCache<>(100_000, Duration.ofMillis(1), com.authx.sdk.model.CheckKey::resourceIndex)); // 1ms TTL = always miss
        coldChain = new CoalescingTransport(coldChain);
        runBenchmark("Full chain cold", coldChain, docCount, userCount);

        // --- Benchmark 6: High contention (all threads check same resource) ---
        System.out.println("\n--- 6. High contention: 200 threads checking same (resource, permission, user) ---");
        var inner6 = new InMemoryTransport();
        seedData(inner6, 1, 1);
        SdkTransport contentionChain = inner6;
        contentionChain = new ResilientTransport(contentionChain, PolicyRegistry.withDefaults(), new DefaultTypedEventBus());
        contentionChain = new CachedTransport(contentionChain, new CaffeineCache<>(100_000, Duration.ofSeconds(10), com.authx.sdk.model.CheckKey::resourceIndex));
        contentionChain = new CoalescingTransport(contentionChain);
        warmCache(contentionChain, 1, 1);
        runContentionBenchmark("High contention", contentionChain);

        System.out.println("\n=== Done ===");
    }

    static void runBenchmark(String name, SdkTransport transport, int docCount, int userCount) throws Exception {
        int threads = 100;
        int opsPerThread = 10_000;
        int totalOps = threads * opsPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicLong totalNanos = new AtomicLong(0);
        long[] latencies = new long[totalOps];
        AtomicLong latencyIdx = new AtomicLong(0);

        long startTime = System.nanoTime();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    return;
                }

                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < opsPerThread; i++) {
                    String docId = "doc-" + rng.nextInt(docCount);
                    String userId = "user-" + rng.nextInt(userCount);

                    long opStart = System.nanoTime();
                    transport.check(CheckRequest.of("document", docId, "editor", "user", userId, Consistency.minimizeLatency()));
                    long opNanos = System.nanoTime() - opStart;

                    totalNanos.addAndGet(opNanos);
                    int idx = (int) latencyIdx.getAndIncrement();
                    if (idx < latencies.length) {
                        latencies[idx] = opNanos;
                    }
                }
            }));
        }

        for (var f : futures) f.get();
        long elapsed = System.nanoTime() - startTime;
        pool.shutdown();

        int captured = (int) Math.min(latencyIdx.get(), latencies.length);
        long[] sorted = Arrays.copyOf(latencies, captured);
        Arrays.sort(sorted);

        double elapsedMs = elapsed / 1_000_000.0;
        double qps = totalOps / (elapsedMs / 1000.0);
        double avgUs = (totalNanos.get() / (double) totalOps) / 1000.0;
        double p50Us = sorted[(int) (captured * 0.50)] / 1000.0;
        double p95Us = sorted[(int) (captured * 0.95)] / 1000.0;
        double p99Us = sorted[(int) (captured * 0.99)] / 1000.0;
        double maxUs = sorted[captured - 1] / 1000.0;

        System.out.printf("  %s: %d threads × %d ops = %,d total%n", name, threads, opsPerThread, totalOps);
        System.out.printf("  Elapsed: %.0f ms | QPS: %,.0f%n", elapsedMs, qps);
        System.out.printf("  Latency (µs): avg=%.1f  p50=%.1f  p95=%.1f  p99=%.1f  max=%.0f%n",
                avgUs, p50Us, p95Us, p99Us, maxUs);
    }

    static void runContentionBenchmark(String name, SdkTransport transport) throws Exception {
        int threads = 200;
        int opsPerThread = 50_000;
        int totalOps = threads * opsPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicLong totalNanos = new AtomicLong(0);
        long[] latencies = new long[totalOps];
        AtomicLong latencyIdx = new AtomicLong(0);

        long startTime = System.nanoTime();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    return;
                }
                for (int i = 0; i < opsPerThread; i++) {
                    long opStart = System.nanoTime();
                    transport.check(CheckRequest.of("document", "doc-0", "editor", "user", "user-0", Consistency.minimizeLatency()));
                    long opNanos = System.nanoTime() - opStart;

                    totalNanos.addAndGet(opNanos);
                    int idx = (int) latencyIdx.getAndIncrement();
                    if (idx < latencies.length) {
                        latencies[idx] = opNanos;
                    }
                }
            }));
        }

        for (var f : futures) f.get();
        long elapsed = System.nanoTime() - startTime;
        pool.shutdown();

        int captured = (int) Math.min(latencyIdx.get(), latencies.length);
        long[] sorted = Arrays.copyOf(latencies, captured);
        Arrays.sort(sorted);

        double elapsedMs = elapsed / 1_000_000.0;
        double qps = totalOps / (elapsedMs / 1000.0);
        double avgUs = (totalNanos.get() / (double) totalOps) / 1000.0;
        double p50Us = sorted[(int) (captured * 0.50)] / 1000.0;
        double p99Us = sorted[(int) (captured * 0.99)] / 1000.0;

        System.out.printf("  %s: %d threads × %d ops = %,d total%n", name, threads, opsPerThread, totalOps);
        System.out.printf("  Elapsed: %.0f ms | QPS: %,.0f%n", elapsedMs, qps);
        System.out.printf("  Latency (µs): avg=%.1f  p50=%.1f  p99=%.1f%n", avgUs, p50Us, p99Us);
    }

    static void seedData(SdkTransport transport, int docCount, int userCount) {
        // Each user gets editor on ~20% of docs
        List<SdkTransport.RelationshipUpdate> updates = new ArrayList<>();
        for (int d = 0; d < docCount; d++) {
            for (int u = 0; u < userCount; u++) {
                if ((d + u) % 5 == 0) { // ~20% hit rate
                    updates.add(new SdkTransport.RelationshipUpdate(
                            SdkTransport.RelationshipUpdate.Operation.TOUCH,
                            ResourceRef.of("document", "doc-" + d),
                            Relation.of("editor"),
                            SubjectRef.of("user", "user-" + u, null)));
                }
            }
        }
        transport.writeRelationships(updates);
    }

    static void warmCache(SdkTransport transport, int docCount, int userCount) {
        for (int d = 0; d < docCount; d++) {
            for (int u = 0; u < userCount; u++) {
                transport.check(CheckRequest.of("document", "doc-" + d, "editor", "user", "user-" + u, Consistency.minimizeLatency()));
            }
        }
    }
}
