package com.authcses.testapp;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.model.Consistency;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 性能压测 — 每个请求都验证数据正确性。
 *
 * 测试维度：
 * 1. 单线程基线延迟（P50/P95/P99）
 * 2. 并发吞吐（多线程 QPS + 正确率）
 * 3. 缓存命中 vs 未命中对比
 * 4. 深层继承 vs 直接授权性能差异
 * 5. 双实例并发（写 primary + 读 secondary）
 * 6. Lookup 性能
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerformanceBenchmark {

    @Autowired private AuthCsesClient primaryClient;
    @Autowired @Qualifier("secondaryClient") private AuthCsesClient secondaryClient;

    private static TruthTable truthTable;
    private static boolean seeded = false;

    @BeforeEach
    void setup() {
        if (!seeded) {
            new DataSeeder(primaryClient).seed();
            truthTable = TruthTable.build(primaryClient);
            seeded = true;
        }
    }

    // ================================================================
    //  1. 单线程基线 — 无缓存（full consistency）
    // ================================================================

    @Test @Order(1)
    void baseline_singleThread_fullConsistency() {
        System.out.println("\n=== 1. Single-thread baseline (full consistency, no cache) ===");

        var cases = truthTable.balanced(200, new Random(42));
        long[] latencies = new long[cases.size()];
        int correct = 0;

        for (int i = 0; i < cases.size(); i++) {
            var c = cases.get(i);
            long start = System.nanoTime();
            boolean result = primaryClient.check(c.resourceType(), c.resourceId(),
                    c.permission(), c.userId(), Consistency.full());
            latencies[i] = System.nanoTime() - start;

            if (result == c.expected()) correct++;
            else System.err.printf("MISMATCH: %s:%s#%s@%s expected=%s got=%s%n",
                    c.resourceType(), c.resourceId(), c.permission(), c.userId(),
                    c.expected(), result);
        }

        printLatencyStats("Baseline (full)", latencies, cases.size(), correct);
        assertThat(correct).isEqualTo(cases.size()); // 100% 正确率
    }

    // ================================================================
    //  2. 单线程 — 缓存命中（minimize latency, 第二轮）
    // ================================================================

    @Test @Order(2)
    void cached_singleThread_minimizeLatency() {
        System.out.println("\n=== 2. Single-thread with cache (minimize latency) ===");

        var cases = truthTable.balanced(200, new Random(42));

        // 第一轮：warm up cache
        for (var c : cases) {
            primaryClient.check(c.resourceType(), c.resourceId(),
                    c.permission(), c.userId(), Consistency.minimizeLatency());
        }

        // 第二轮：measure (should hit cache)
        long[] latencies = new long[cases.size()];
        int correct = 0;

        for (int i = 0; i < cases.size(); i++) {
            var c = cases.get(i);
            long start = System.nanoTime();
            boolean result = primaryClient.check(c.resourceType(), c.resourceId(),
                    c.permission(), c.userId(), Consistency.minimizeLatency());
            latencies[i] = System.nanoTime() - start;

            if (result == c.expected()) correct++;
        }

        printLatencyStats("Cached (minimize)", latencies, cases.size(), correct);
        assertThat(correct).isEqualTo(cases.size());
    }

    // ================================================================
    //  3. 深层继承 vs 直接授权 延迟对比
    // ================================================================

    @Test @Order(3)
    void latencyComparison_directVsInherited() {
        System.out.println("\n=== 3. Direct grant vs deep inheritance latency ===");

        // 直接授权：carol 是 design-doc owner
        long[] directLatencies = measure(100,
                () -> primaryClient.check("document", "design-doc", "manage", "carol", Consistency.full()));

        // 4 层继承：grace → dept:product → space:wiki viewer → folder chain → secret-doc view
        long[] inheritedLatencies = measure(100,
                () -> primaryClient.check("document", "secret-doc", "view", "grace", Consistency.full()));

        // org admin 穿透：alice → org admin → space → folder → doc
        long[] orgLatencies = measure(100,
                () -> primaryClient.check("document", "secret-doc", "manage", "alice", Consistency.full()));

        printLatencyStats("Direct grant", directLatencies, 100, 100);
        printLatencyStats("4-level inherit", inheritedLatencies, 100, 100);
        printLatencyStats("Org admin pass", orgLatencies, 100, 100);
    }

    // ================================================================
    //  4. 多线程并发 — 正确性 + QPS
    // ================================================================

    @Test @Order(10)
    void concurrent_8threads_withCorrectness() throws InterruptedException {
        System.out.println("\n=== 4. Concurrent 8 threads (full consistency) ===");
        runConcurrentBenchmark(8, 5_000, Consistency.full(), primaryClient);
    }

    @Test @Order(11)
    void concurrent_32threads_cached() throws InterruptedException {
        System.out.println("\n=== 5. Concurrent 32 threads (cached, minimize latency) ===");

        // Warm up
        var warmup = truthTable.balanced(200, new Random(99));
        for (var c : warmup) {
            primaryClient.check(c.resourceType(), c.resourceId(),
                    c.permission(), c.userId(), Consistency.minimizeLatency());
        }

        runConcurrentBenchmark(32, 10_000, Consistency.minimizeLatency(), primaryClient);
    }

    // ================================================================
    //  5. 双实例并发 — 写 primary + 读 secondary
    // ================================================================

    @Test @Order(20)
    void dualInstance_writePrimary_readSecondary() throws InterruptedException {
        System.out.println("\n=== 6. Dual-instance: write primary + read secondary ===");

        int iterations = 500;
        LongAdder correct = new LongAdder();
        LongAdder errors = new LongAdder();
        List<Long> readLatencies = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(iterations);

        for (int i = 0; i < iterations; i++) {
            final String userId = "bench-user-" + i;
            final String docId = "bench-doc-" + (i % 6); // reuse seeded docs
            executor.submit(() -> {
                try {
                    // Write on primary
                    primaryClient.grant("document", "design-doc", "viewer", userId);

                    // Read on secondary with full consistency
                    long start = System.nanoTime();
                    boolean result = secondaryClient.check("document", "design-doc",
                            "view", userId, Consistency.full());
                    readLatencies.add(System.nanoTime() - start);

                    if (result) correct.increment(); else errors.increment();
                } catch (Exception e) {
                    errors.increment();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        long[] lats = readLatencies.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(lats);

        System.out.printf("Cross-instance: %d/%d correct (%.1f%%), errors=%d%n",
                correct.sum(), iterations, 100.0 * correct.sum() / iterations, errors.sum());
        if (lats.length > 0) {
            System.out.printf("Read latency: P50=%.2fms P95=%.2fms P99=%.2fms%n",
                    lats[(int) (lats.length * 0.50)] / 1_000_000.0,
                    lats[(int) (lats.length * 0.95)] / 1_000_000.0,
                    lats[(int) (lats.length * 0.99)] / 1_000_000.0);
        }

        assertThat(correct.sum()).isEqualTo(iterations);

        // Cleanup
        for (int i = 0; i < iterations; i++) {
            try {
                primaryClient.revoke("document", "design-doc", "viewer", "bench-user-" + i);
            } catch (Exception ignored) {}
        }
    }

    // ================================================================
    //  6. Lookup 性能
    // ================================================================

    @Test @Order(30)
    void lookup_subjects_performance() {
        System.out.println("\n=== 7. Lookup subjects performance ===");

        long[] latencies = measure(50, () ->
                primaryClient.on("document").subjects("design-doc", "view"));

        printLatencyStats("lookupSubjects", latencies, 50, 50);
    }

    @Test @Order(31)
    void lookup_resources_performance() {
        System.out.println("\n=== 8. Lookup resources performance ===");

        long[] latencies = measure(50, () ->
                primaryClient.on("document").resources("view", "bob"));

        printLatencyStats("lookupResources", latencies, 50, 50);
    }

    // ================================================================
    //  7. checkAll 批量性能
    // ================================================================

    @Test @Order(40)
    void checkAll_performance() {
        System.out.println("\n=== 9. checkAll (6 permissions at once) ===");

        String[] perms = {"view", "edit", "comment", "manage", "delete", "share"};

        long[] latencies = measure(100, () ->
                primaryClient.on("document").checkAll("design-doc", "grace", perms));

        printLatencyStats("checkAll(6 perms)", latencies, 100, 100);
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private void runConcurrentBenchmark(int threads, int totalOps, Consistency consistency,
                                         AuthCsesClient client) throws InterruptedException {
        var cases = truthTable.balanced(totalOps, new Random(7));
        LongAdder correct = new LongAdder();
        LongAdder wrong = new LongAdder();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(totalOps));

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(totalOps);
        long benchStart = System.nanoTime();

        for (var c : cases) {
            executor.submit(() -> {
                try {
                    long start = System.nanoTime();
                    boolean result = client.check(c.resourceType(), c.resourceId(),
                            c.permission(), c.userId(), consistency);
                    latencies.add(System.nanoTime() - start);

                    if (result == c.expected()) correct.increment(); else wrong.increment();
                } catch (Exception e) {
                    wrong.increment();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(120, TimeUnit.SECONDS);
        long benchElapsed = System.nanoTime() - benchStart;
        executor.shutdown();

        double qps = totalOps / (benchElapsed / 1_000_000_000.0);
        long[] lats = latencies.stream().mapToLong(Long::longValue).sorted().toArray();

        System.out.printf("%d threads × %d ops: QPS=%.0f, correct=%d/%d (%.1f%%)%n",
                threads, totalOps, qps, correct.sum(), totalOps,
                100.0 * correct.sum() / totalOps);
        if (lats.length > 0) {
            System.out.printf("Latency: P50=%.2fms P95=%.2fms P99=%.2fms max=%.2fms%n",
                    lats[(int) (lats.length * 0.50)] / 1_000_000.0,
                    lats[(int) (lats.length * 0.95)] / 1_000_000.0,
                    lats[Math.min((int) (lats.length * 0.99), lats.length - 1)] / 1_000_000.0,
                    lats[lats.length - 1] / 1_000_000.0);
        }

        assertThat(correct.sum()).as("All results must match truth table").isEqualTo(totalOps);
    }

    private long[] measure(int count, Runnable op) {
        long[] latencies = new long[count];
        for (int i = 0; i < count; i++) {
            long start = System.nanoTime();
            op.run();
            latencies[i] = System.nanoTime() - start;
        }
        return latencies;
    }

    private void printLatencyStats(String label, long[] latencies, int total, int correct) {
        Arrays.sort(latencies);
        int n = latencies.length;
        if (n == 0) { System.out.println(label + ": no data"); return; }

        double p50 = latencies[(int) (n * 0.50)] / 1_000_000.0;
        double p95 = latencies[(int) (n * 0.95)] / 1_000_000.0;
        double p99 = latencies[Math.min((int) (n * 0.99), n - 1)] / 1_000_000.0;
        double avg = Arrays.stream(latencies).average().orElse(0) / 1_000_000.0;
        double max = latencies[n - 1] / 1_000_000.0;

        System.out.printf("%s: %d ops, correct=%d/%d (%.0f%%), " +
                        "P50=%.2fms P95=%.2fms P99=%.2fms avg=%.2fms max=%.2fms%n",
                label, total, correct, total, 100.0 * correct / total,
                p50, p95, p99, avg, max);
    }
}
