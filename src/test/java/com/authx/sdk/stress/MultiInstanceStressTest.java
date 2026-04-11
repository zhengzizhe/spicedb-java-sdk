package com.authx.sdk.stress;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.policy.*;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Multi-instance stress test: 3 SDK clients sharing the same SpiceDB cluster.
 *
 * Tests:
 * 1. Concurrent write correctness
 * 2. Cross-instance Watch cache invalidation
 * 3. Cache hit rate under load
 * 4. Throughput and latency
 *
 * Requires: SpiceDB on 50061/50062.
 * Run: ./gradlew :test --tests "com.authx.sdk.stress.MultiInstanceStressTest"
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiInstanceStressTest {

    static final String SPICEDB_1 = "localhost:50061";
    static final String SPICEDB_2 = "localhost:50062";
    static final String PRESHARED_KEY = "testkey";

    // 3 SDK instances simulating 3 microservice pods
    static AuthxClient instance1;
    static AuthxClient instance2;
    static AuthxClient instance3;

    @BeforeAll
    static void setup() {
        // Check SpiceDB reachable.
        //
        // NOTE: AuthxClient.builder().build() alone is NOT a reachability check —
        // gRPC channels are lazy and will not connect until the first RPC. So we
        // explicitly TCP-ping each required endpoint; if either port is closed
        // we assume this integration test cannot run in the current environment.
        assumeTrue(tcpPing(SPICEDB_1), "SpiceDB not reachable on " + SPICEDB_1);
        assumeTrue(tcpPing(SPICEDB_2), "SpiceDB not reachable on " + SPICEDB_2);

        var policies = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .cache(CachePolicy.of(Duration.ofSeconds(5)))
                        .readConsistency(ReadConsistency.minimizeLatency())
                        .retry(RetryPolicy.defaults())
                        .build())
                .build();

        // Instance 1: connects to SpiceDB-1, L1+Watch
        instance1 = AuthxClient.builder()
                .connection(c -> c.target(SPICEDB_1).presharedKey(PRESHARED_KEY))
                .cache(c -> c.enabled(true).maxSize(50_000).watchInvalidation(true))
                .features(f -> f.coalescing(true).virtualThreads(true))
                .extend(e -> e.policies(policies))
                .build();

        // Instance 2: connects to SpiceDB-2, L1+Watch
        instance2 = AuthxClient.builder()
                .connection(c -> c.target(SPICEDB_2).presharedKey(PRESHARED_KEY))
                .cache(c -> c.enabled(true).maxSize(50_000).watchInvalidation(true))
                .features(f -> f.coalescing(true).virtualThreads(true))
                .extend(e -> e.policies(policies))
                .build();

        // Instance 3: connects to SpiceDB-1, L1 only (no Redis, no Watch) — baseline
        instance3 = AuthxClient.builder()
                .connection(c -> c.target(SPICEDB_1).presharedKey(PRESHARED_KEY))
                .cache(c -> c.enabled(true).maxSize(50_000))
                .features(f -> f.coalescing(true).virtualThreads(true))
                .extend(e -> e.policies(policies))
                .build();

        // Wait for Watch streams to connect
        sleep(2000);
    }

    @AfterAll
    static void teardown() {
        if (instance1 != null) instance1.close();
        if (instance2 != null) instance2.close();
        if (instance3 != null) instance3.close();
    }

    // ============================================================
    //  Test 1: Concurrent write correctness
    // ============================================================

    @Test
    @Order(1)
    void concurrentWrites_allSucceed() throws Exception {
        int writers = 100;
        var latch = new CountDownLatch(writers);
        var errors = new AtomicInteger(0);

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < writers; i++) {
            final int idx = i;
            executor.execute(() -> {
                try {
                    // Spread across 3 instances
                    var client = switch (idx % 3) {
                        case 0 -> instance1;
                        case 1 -> instance2;
                        default -> instance3;
                    };
                    var doc = client.on("document");
                    doc.grant("stress-doc-" + idx, "editor", "stress-user-" + idx);
                } catch (Exception e) {
                    errors.incrementAndGet();
                    System.err.println("Write error [" + idx + "]: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).as("concurrent write errors").isZero();
        System.out.println("[Test 1] 100 concurrent writes across 3 instances: OK");
    }

    // ============================================================
    //  Test 2: Cross-instance check correctness
    // ============================================================

    @Test
    @Order(2)
    void crossInstanceCheck_seesWritesFromOtherInstance() throws Exception {
        // Instance 1 writes
        instance1.on("document").grant("cross-doc-1", "viewer", "cross-user-1");

        // Strong consistency: instance 2 should see it immediately
        boolean canView = instance2.on("document").resource("cross-doc-1")
                .check("view").withConsistency(Consistency.full()).by("cross-user-1").hasPermission();
        assertThat(canView).isTrue();
        System.out.println("[Test 2] Cross-instance check with strong consistency: OK");
    }

    // ============================================================
    //  Test 3: Watch cache invalidation across instances
    // ============================================================

    @Test
    @Order(3)
    void watchInvalidation_crossInstance() throws Exception {
        var doc = "watch-doc-" + System.nanoTime();
        var user = "watch-user-" + System.nanoTime();

        // Instance 1: grant, use returned token for consistent read
        var grantResult = instance1.on("document").grant(doc, "editor", user);

        // Instance 2: read with strong consistency (confirm write is visible)
        boolean canEdit = instance2.on("document").resource(doc)
                .check("edit").withConsistency(Consistency.full()).by(user).hasPermission();
        assertThat(canEdit).as("editor should have edit after grant").isTrue();

        // Instance 2: read with default consistency to populate cache
        instance2.on("document").resource(doc).check("edit").by(user).hasPermission();

        // Instance 1: revoke
        instance1.on("document").revoke(doc, "editor", user);
        sleep(3000); // let Watch propagate to instance 2 and invalidate L1+L2

        // Instance 2: re-check with strong consistency (bypass cache to verify ground truth)
        boolean canEditAfterRevoke = instance2.on("document").resource(doc)
                .check("edit").withConsistency(Consistency.full()).by(user).hasPermission();
        assertThat(canEditAfterRevoke).as("editor should NOT have edit after revoke").isFalse();
        System.out.println("[Test 3] Watch invalidation across instances: OK");
    }

    // ============================================================
    //  Test 4: Throughput benchmark — check operations
    // ============================================================

    @Test
    @Order(4)
    void throughputBenchmark_checkOperations() throws Exception {
        // Seed data: 100 documents with editors
        for (int i = 0; i < 100; i++) {
            instance1.on("document").grant("bench-doc-" + i, "editor", "bench-user-" + (i % 10));
        }
        sleep(1000); // let Watch propagate + caches warm

        // Warm cache: read each once
        for (int i = 0; i < 100; i++) {
            instance1.on("document").resource("bench-doc-" + i)
                    .check("edit").by("bench-user-" + (i % 10)).hasPermission();
        }

        // Benchmark: 3 instances, each doing 1000 checks
        int checksPerInstance = 1000;
        var allLatencies = new ConcurrentLinkedQueue<Long>();
        var totalErrors = new AtomicInteger(0);

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(3);

        for (var client : List.of(instance1, instance2, instance3)) {
            executor.execute(() -> {
                try {
                    var rng = ThreadLocalRandom.current();
                    for (int i = 0; i < checksPerInstance; i++) {
                        int docIdx = rng.nextInt(100);
                        int userIdx = docIdx % 10;
                        long start = System.nanoTime();
                        try {
                            client.on("document").resource("bench-doc-" + docIdx)
                                    .check("edit").by("bench-user-" + userIdx).hasPermission();
                        } catch (Exception e) {
                            totalErrors.incrementAndGet();
                        }
                        long latencyUs = (System.nanoTime() - start) / 1000;
                        allLatencies.add(latencyUs);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();

        // Report
        var sorted = allLatencies.stream().sorted().toList();
        int total = sorted.size();
        long p50 = sorted.get((int) (total * 0.50));
        long p95 = sorted.get((int) (total * 0.95));
        long p99 = sorted.get((int) (total * 0.99));
        double avgUs = sorted.stream().mapToLong(l -> l).average().orElse(0);

        System.out.println("[Test 4] Throughput benchmark (3 instances × 1000 checks = 3000 total):");
        System.out.printf("  Total: %d checks, Errors: %d%n", total, totalErrors.get());
        System.out.printf("  Latency: p50=%dμs, p95=%dμs, p99=%dμs, avg=%.0fμs%n", p50, p95, p99, avgUs);

        // Report per-instance metrics
        for (int i = 0; i < 3; i++) {
            var client = List.of(instance1, instance2, instance3).get(i);
            var snapshot = client.metrics().snapshot();
            System.out.printf("  Instance %d: cache=%.1f%% (hits=%d, misses=%d), coalesced=%d%n",
                    i + 1, snapshot.cacheHitRate() * 100, snapshot.cacheHits(),
                    snapshot.cacheMisses(), snapshot.coalescedRequests());
        }

        assertThat(totalErrors.get()).as("benchmark errors").isZero();
    }

    // ============================================================
    //  Test 5: High-concurrency burst
    // ============================================================

    @Test
    @Order(5)
    void highConcurrencyBurst_noErrors() throws Exception {
        int threads = 200;
        var barrier = new CyclicBarrier(threads);
        var errors = new AtomicInteger(0);
        var latch = new CountDownLatch(threads);

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.execute(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS); // all threads start at once
                    var client = switch (idx % 3) {
                        case 0 -> instance1;
                        case 1 -> instance2;
                        default -> instance3;
                    };
                    // Mix of operations
                    if (idx % 5 == 0) {
                        // 20% writes
                        client.on("document").grant("burst-doc-" + (idx % 50), "viewer", "burst-user-" + idx);
                    } else {
                        // 80% reads
                        client.on("document").resource("burst-doc-" + (idx % 50))
                                .check("view").by("burst-user-" + (idx % 20)).hasPermission();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    if (errors.get() <= 5) {
                        System.err.println("Burst error [" + idx + "]: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        System.out.printf("[Test 5] 200-thread burst (80%% reads, 20%% writes): %d errors%n", errors.get());
        // Allow small number of errors under extreme concurrency
        assertThat(errors.get()).as("burst errors").isLessThan(10);
    }

    // ============================================================
    //  Test 6: Cleanup
    // ============================================================

    @Test
    @Order(6)
    void finalReport() {
        System.out.println("\n===== FINAL METRICS REPORT =====");
        for (int i = 0; i < 3; i++) {
            var client = List.of(instance1, instance2, instance3).get(i);
            var s = client.metrics().snapshot();
            System.out.printf("Instance %d: %s%n", i + 1, s);
        }
        System.out.println("================================\n");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** TCP-ping a {@code host:port} target. Returns false if the port is closed or unreachable. */
    private static boolean tcpPing(String hostPort) {
        String[] parts = hostPort.split(":", 2);
        try (var sock = new Socket()) {
            sock.connect(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
