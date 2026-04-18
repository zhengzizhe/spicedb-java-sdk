package com.authx.sdk;

import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.internal.SdkCaching;
import com.authx.sdk.internal.SdkConfig;
import com.authx.sdk.internal.SdkInfrastructure;
import com.authx.sdk.internal.SdkObservability;
import com.authx.sdk.lifecycle.LifecycleManager;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.policy.PolicyRegistry;
import com.authx.sdk.spi.HealthProbe;
import com.authx.sdk.spi.TelemetrySink;
import com.authx.sdk.telemetry.TelemetryReporter;
import com.authx.sdk.transport.InMemoryTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies virtual thread compatibility of the SDK.
 *
 * <p>The SDK uses virtual threads in 3 places when configured with
 * {@code .features(f -> f.virtualThreads(true))}:
 * <ol>
 *   <li>{@code Executors.newVirtualThreadPerTaskExecutor()} for the async executor</li>
 *   <li>{@code Thread.ofVirtual().name("authx-sdk-", 0).factory()} for the scheduler</li>
 *   <li>{@code Thread.ofVirtual().name("authx-sdk-telemetry-", 0).factory()} for telemetry</li>
 * </ol>
 *
 * <h3>Known gRPC Netty + virtual thread caveats</h3>
 * <ul>
 *   <li>gRPC Netty uses {@code synchronized} blocks internally that can <b>pin</b> virtual
 *       threads to carrier threads. This is a JDK limitation (JEP 491 in JDK 24+ will fix
 *       monitor pinning). The SDK mitigates this by using virtual threads only for SDK-level
 *       work (interceptors, listeners, telemetry), NOT for gRPC I/O — Netty's EventLoop
 *       is inherently single-threaded and would gain nothing from virtual threads.</li>
 *   <li>The SDK's {@link InMemoryTransport} does not use gRPC at all, so these tests
 *       validate the SDK's own virtual thread usage without triggering Netty pinning.</li>
 *   <li>In production with a real gRPC channel, carrier thread pinning may still occur
 *       during RPC calls that transit through Netty's synchronized event loop. This is
 *       harmless as long as the pinned duration is short (microseconds for buffer ops).
 *       The risk surfaces under extreme concurrency (thousands of concurrent RPCs) where
 *       carrier thread exhaustion could cause stalls.</li>
 * </ul>
 */
class VirtualThreadCompatibilityTest {

    private AuthxClient client;

    @BeforeEach
    void setup() {
        // Build a client with virtual threads enabled, using InMemoryTransport
        // to avoid needing a real SpiceDB instance.
        var bus = new DefaultTypedEventBus();
        var lm = new LifecycleManager(bus);
        lm.begin();
        lm.complete();

        // Use a virtual-thread executor — this is what the builder creates when
        // useVirtualThreads=true.
        Executor vtExecutor = Executors.newVirtualThreadPerTaskExecutor();

        var infra = new SdkInfrastructure(null, null, vtExecutor, lm);
        var observability = new SdkObservability(new SdkMetrics(), bus, null);
        var caching = new SdkCaching(null, null, null, null);
        var config = new SdkConfig("user", PolicyRegistry.withDefaults(), false, true);
        client = new AuthxClient(new InMemoryTransport(), infra, observability, caching, config, HealthProbe.up());
    }

    @AfterEach
    void teardown() {
        if (client != null) client.close();
    }

    // ================================================================
    //  Test 1: Basic build + operations on virtual threads
    // ================================================================

    @Test
    void clientWithVirtualThreads_basicOperationsWork() {
        var doc = client.on("document");
        doc.grant("doc-1", "editor", "alice");
        assertThat(doc.check("doc-1", "editor", "alice")).isTrue();
        assertThat(doc.check("doc-1", "editor", "bob")).isFalse();
    }

    // ================================================================
    //  Test 2: Concurrent checks from many virtual threads
    // ================================================================

    @Test
    void concurrentChecks_onVirtualThreads_allCorrect() throws Exception {
        // Seed data
        var doc = client.on("document");
        for (int i = 0; i < 100; i++) {
            doc.grant("doc-" + i, "viewer", "user-" + (i % 10));
        }

        int concurrency = 500;
        var errors = new AtomicInteger(0);
        var wrongResults = new AtomicInteger(0);
        var latch = new CountDownLatch(concurrency);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                final int idx = i;
                executor.execute(() -> {
                    try {
                        int docIdx = idx % 100;
                        int userIdx = docIdx % 10;

                        // This user SHOULD have permission
                        boolean allowed = doc.check("doc-" + docIdx, "viewer", "user-" + userIdx);
                        if (!allowed) wrongResults.incrementAndGet();

                        // This user should NOT have permission (off by one)
                        boolean denied = doc.check("doc-" + docIdx, "viewer", "user-" + ((userIdx + 5) % 10));
                        if (denied) wrongResults.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS))
                    .as("all virtual threads should complete within timeout")
                    .isTrue();
        }

        assertThat(errors.get()).as("no exceptions from virtual threads").isZero();
        assertThat(wrongResults.get()).as("all permission checks returned correct results").isZero();
    }

    // ================================================================
    //  Test 3: Mixed read/write from virtual threads (no deadlock)
    // ================================================================

    @Test
    void mixedReadWrite_onVirtualThreads_noDeadlock() throws Exception {
        int concurrency = 200;
        var errors = new AtomicInteger(0);
        var latch = new CountDownLatch(concurrency);
        var barrier = new CyclicBarrier(concurrency);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                final int idx = i;
                executor.execute(() -> {
                    try {
                        barrier.await(5, TimeUnit.SECONDS);
                        var doc = client.on("document");
                        if (idx % 3 == 0) {
                            // Write
                            doc.grant("mixed-doc-" + (idx % 20), "editor", "mixed-user-" + idx);
                        } else {
                            // Read
                            doc.check("mixed-doc-" + (idx % 20), "viewer", "mixed-user-" + (idx % 10));
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS))
                    .as("mixed read/write should complete without deadlock")
                    .isTrue();
        }

        assertThat(errors.get()).as("no exceptions during mixed read/write").isZero();
    }

    // ================================================================
    //  Test 4: Verify async executor runs callbacks on virtual threads
    // ================================================================

    @Test
    void asyncExecutor_callbacksRunOnVirtualThreads() throws Exception {
        // The client's asyncExecutor is a virtual-thread-per-task executor.
        // ResourceFactory.grantAsync() and checkAsync() use it for callbacks.
        // Verify that the callback thread is indeed a virtual thread.
        var threadRef = new AtomicReference<Thread>();
        var latch = new CountDownLatch(1);

        // Use the async executor directly (it's exposed via the client internals
        // through ResourceFactory). We simulate what checkAsync does.
        Executor asyncExec = client.asyncExecutor();
        asyncExec.execute(() -> {
            threadRef.set(Thread.currentThread());
            latch.countDown();
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadRef.get()).isNotNull();
        assertThat(threadRef.get().isVirtual())
                .as("async executor should run tasks on virtual threads")
                .isTrue();
    }

    // ================================================================
    //  Test 5: Scheduler with virtual thread factory
    // ================================================================

    @Test
    void schedulerWithVirtualThreadFactory_executesPeriodicTasks() throws Exception {
        // Create a scheduler using the same pattern as AuthxClientBuilder line ~480
        ThreadFactory tf = Thread.ofVirtual().name("authx-sdk-test-", 0).factory();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(tf);

        var executionCount = new AtomicInteger(0);
        var threadRef = new AtomicReference<Thread>();
        var latch = new CountDownLatch(3);

        scheduler.scheduleAtFixedRate(() -> {
            threadRef.compareAndSet(null, Thread.currentThread());
            executionCount.incrementAndGet();
            latch.countDown();
        }, 0, 50, TimeUnit.MILLISECONDS);

        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("scheduled task should fire at least 3 times")
                .isTrue();

        scheduler.shutdown();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);

        assertThat(executionCount.get()).isGreaterThanOrEqualTo(3);
        assertThat(threadRef.get().isVirtual())
                .as("scheduler thread should be virtual")
                .isTrue();
        assertThat(threadRef.get().getName()).startsWith("authx-sdk-test-");
    }

    // ================================================================
    //  Test 6: TelemetryReporter on virtual threads
    // ================================================================

    @Test
    void telemetryReporter_flushesOnVirtualThread() throws Exception {
        var flushThreadRef = new AtomicReference<Thread>();
        var receivedBatches = Collections.synchronizedList(new ArrayList<List<Map<String, Object>>>());
        var flushLatch = new CountDownLatch(1);

        TelemetrySink capturingSink = batch -> {
            flushThreadRef.compareAndSet(null, Thread.currentThread());
            receivedBatches.add(batch);
            flushLatch.countDown();
        };

        // useVirtualThreads=true triggers virtual thread factory for the scheduler
        var reporter = new TelemetryReporter(capturingSink, 100, 5, 100, true);

        // Record enough events to trigger a batch flush
        for (int i = 0; i < 10; i++) {
            reporter.record("CHECK", "doc", String.valueOf(i), "user", "alice",
                    "view", "ALLOWED", 5, "trace-" + i);
        }

        assertThat(flushLatch.await(5, TimeUnit.SECONDS))
                .as("telemetry should flush within timeout")
                .isTrue();

        assertThat(receivedBatches).isNotEmpty();
        assertThat(flushThreadRef.get()).isNotNull();
        assertThat(flushThreadRef.get().isVirtual())
                .as("telemetry flush should execute on a virtual thread")
                .isTrue();
        assertThat(flushThreadRef.get().getName()).startsWith("authx-sdk-telemetry-");

        reporter.close();
    }

    // ================================================================
    //  Test 7: TelemetryReporter close() flushes remaining on VT
    // ================================================================

    @Test
    void telemetryReporter_closeFlushesRemainingEvents_onVirtualThreads() {
        var received = Collections.synchronizedList(new ArrayList<List<Map<String, Object>>>());
        TelemetrySink sink = received::add;

        var reporter = new TelemetryReporter(sink, 100, 50, 60_000, true);
        // Record fewer than batchSize so only close() triggers the flush
        for (int i = 0; i < 5; i++) {
            reporter.record("WRITE", "doc", String.valueOf(i), "user", "bob",
                    "editor", "OK", 10, "");
        }
        assertThat(reporter.pendingCount()).isGreaterThan(0);

        reporter.close();

        assertThat(received).isNotEmpty();
        int totalEvents = received.stream().mapToInt(List::size).sum();
        assertThat(totalEvents).isEqualTo(5);
    }

    // ================================================================
    //  Test 8: High-concurrency burst — no carrier thread exhaustion
    // ================================================================

    @Test
    void highConcurrencyBurst_noCarrierThreadExhaustion() throws Exception {
        // Spawn many more virtual threads than available carrier threads
        // to verify the SDK's InMemoryTransport + ConcurrentHashMap usage
        // does not cause pinning-related stalls.
        //
        // NOTE: With a real gRPC transport, Netty's synchronized blocks could
        // pin virtual threads to carrier threads. InMemoryTransport uses only
        // ConcurrentHashMap (lock-free reads, striped locks for writes) which
        // should not cause pinning. If this test passes but a similar test
        // with a real gRPC channel stalls, that confirms the Netty pinning issue.
        int concurrency = 1000;
        var completed = new AtomicInteger(0);
        var errors = new AtomicInteger(0);
        var latch = new CountDownLatch(concurrency);

        // Seed some data first
        var doc = client.on("document");
        for (int i = 0; i < 50; i++) {
            doc.grant("burst-doc-" + i, "viewer", "burst-user-" + (i % 5));
        }

        long startNanos = System.nanoTime();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                final int idx = i;
                executor.execute(() -> {
                    try {
                        // Mix operations
                        if (idx % 10 == 0) {
                            doc.grant("burst-doc-" + (idx % 50), "commenter", "burst-vt-" + idx);
                        } else {
                            doc.check("burst-doc-" + (idx % 50), "viewer", "burst-user-" + (idx % 5));
                        }
                        completed.incrementAndGet();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(15, TimeUnit.SECONDS))
                    .as("1000 virtual threads should complete without carrier exhaustion")
                    .isTrue();
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(errors.get()).as("no errors during burst").isZero();
        assertThat(completed.get()).isEqualTo(concurrency);
        // If this takes > 10s for in-memory ops, something is seriously wrong
        // (likely carrier thread starvation from pinning)
        assertThat(elapsedMs).as("burst should complete quickly for in-memory transport").isLessThan(10_000);
    }

    // ================================================================
    //  Test 9: Virtual thread identity — tasks get distinct threads
    // ================================================================

    @Test
    void virtualThreadExecutor_eachTaskGetsDistinctThread() throws Exception {
        int tasks = 50;
        var threadIds = Collections.synchronizedList(new ArrayList<Long>());
        var latch = new CountDownLatch(tasks);

        Executor asyncExec = client.asyncExecutor();
        for (int i = 0; i < tasks; i++) {
            asyncExec.execute(() -> {
                threadIds.add(Thread.currentThread().threadId());
                latch.countDown();
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // Virtual-thread-per-task executor creates a new thread per task
        // so we should have many distinct thread IDs (not all identical)
        long distinctCount = threadIds.stream().distinct().count();
        assertThat(distinctCount)
                .as("virtual-thread-per-task should create multiple distinct threads")
                .isGreaterThan(1);
    }
}
