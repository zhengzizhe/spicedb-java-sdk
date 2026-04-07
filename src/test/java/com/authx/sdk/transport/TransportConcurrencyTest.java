package com.authx.sdk.transport;

import com.authx.sdk.cache.CaffeineCache;
import com.authx.sdk.event.DefaultTypedEventBus;
import com.authx.sdk.exception.AuthxConnectionException;
import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import com.authx.sdk.policy.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Concurrency tests for Transport decorators.
 * Validates correctness under parallel access: coalescing, caching singleFlight,
 * token tracking, pooled distribution, and retry budget.
 */
class TransportConcurrencyTest {

    private static final CheckResult OK = new CheckResult(Permissionship.HAS_PERMISSION, "tok-1", Optional.empty());

    // ---- Controllable slow delegate ----

    static class SlowDelegate extends InMemoryTransport {
        private final AtomicInteger checkCount = new AtomicInteger();
        private final long delayMs;

        SlowDelegate(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        public CheckResult check(CheckRequest request) {
            checkCount.incrementAndGet();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new CheckResult(Permissionship.HAS_PERMISSION, null, Optional.empty());
        }

        int checkCount() {
            return checkCount.get();
        }
    }

    // ---- Helpers ----

    private static CheckRequest sameRequest() {
        return CheckRequest.of(
                ResourceRef.of("document", "doc-1"),
                Permission.of("view"),
                SubjectRef.user("alice"),
                Consistency.minimizeLatency());
    }

    private static CheckRequest requestWithIndex(int i) {
        return CheckRequest.of(
                ResourceRef.of("document", "doc-" + i),
                Permission.of("view"),
                SubjectRef.user("alice"),
                Consistency.minimizeLatency());
    }

    /**
     * Run {@code taskCount} tasks concurrently, all starting at the same instant via CyclicBarrier.
     */
    private <T> List<Future<T>> runConcurrently(int taskCount, Callable<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CyclicBarrier barrier = new CyclicBarrier(taskCount);
        List<Future<T>> futures = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return task.call();
            }));
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        return futures;
    }

    /**
     * Like runConcurrently but each task gets its own index.
     */
    private <T> List<Future<T>> runConcurrentlyIndexed(int taskCount, java.util.function.IntFunction<Callable<T>> taskFactory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CyclicBarrier barrier = new CyclicBarrier(taskCount);
        List<Future<T>> futures = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            final int idx = i;
            Callable<T> inner = taskFactory.apply(idx);
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return inner.call();
            }));
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        return futures;
    }

    // ======================================================================
    // 1. CoalescingTransport — same request coalesced
    // ======================================================================

    @Test
    void coalescing_concurrentSameRequest_onlyOneDelegateCall() throws Exception {
        var delegate = new SlowDelegate(100);
        var transport = new CoalescingTransport(delegate);

        List<Future<CheckResult>> futures = runConcurrently(30, () -> transport.check(sameRequest()));

        // Collect results
        Set<Permissionship> results = new HashSet<>();
        for (var f : futures) {
            results.add(f.get().permissionship());
        }

        assertThat(delegate.checkCount()).isEqualTo(1);
        assertThat(results).containsExactly(Permissionship.HAS_PERMISSION);
    }

    // ======================================================================
    // 2. CoalescingTransport — different requests NOT coalesced
    // ======================================================================

    @Test
    void coalescing_differentRequests_notCoalesced() throws Exception {
        var delegate = new SlowDelegate(50);
        var transport = new CoalescingTransport(delegate);

        List<Future<CheckResult>> futures = runConcurrentlyIndexed(30, i ->
                () -> transport.check(requestWithIndex(i)));

        // Wait for all to complete
        for (var f : futures) {
            f.get();
        }

        assertThat(delegate.checkCount()).isEqualTo(30);
    }

    // ======================================================================
    // 3. CoalescingTransport — exception shared to all waiters
    // ======================================================================

    @Test
    void coalescing_exceptionSharedToAllWaiters() throws Exception {
        var callCount = new AtomicInteger();
        SdkTransport failingDelegate = new InMemoryTransport() {
            @Override
            public CheckResult check(CheckRequest request) {
                callCount.incrementAndGet();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new AuthxConnectionException("boom");
            }
        };
        var transport = new CoalescingTransport(failingDelegate);

        List<Future<CheckResult>> futures = runConcurrently(30, () -> transport.check(sameRequest()));

        int exceptionCount = 0;
        for (var f : futures) {
            try {
                f.get();
                fail("Expected exception");
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(AuthxConnectionException.class);
                exceptionCount++;
            }
        }

        assertThat(exceptionCount).isEqualTo(30);
        assertThat(callCount.get()).isEqualTo(1);
    }

    // ======================================================================
    // 4. CachedTransport — singleFlight with CaffeineCache
    // ======================================================================

    @Test
    void cachedTransport_singleFlight_withCaffeineCache() throws Exception {
        var delegate = new SlowDelegate(50);
        var cache = new CaffeineCache<CheckKey, CheckResult>(
                10_000, Duration.ofSeconds(30), CheckKey::resourceIndex);
        var transport = new CachedTransport(delegate, cache);

        List<Future<CheckResult>> futures = runConcurrently(30, () ->
                transport.check(sameRequest()));

        Set<Permissionship> results = new HashSet<>();
        for (var f : futures) {
            results.add(f.get().permissionship());
        }

        // singleFlight: only one thread calls delegate, others wait for the same result
        assertThat(delegate.checkCount()).isEqualTo(1);
        assertThat(results).containsExactly(Permissionship.HAS_PERMISSION);
    }

    // ======================================================================
    // 5. CachedTransport — write invalidation concurrent with reads
    // ======================================================================

    @Test
    void cachedTransport_writeInvalidation_concurrent() throws Exception {
        var delegate = new InMemoryTransport();
        // Seed a relationship so check returns HAS_PERMISSION
        delegate.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "doc-1"),
                        Relation.of("view"),
                        SubjectRef.user("alice"))));

        var cache = new CaffeineCache<CheckKey, CheckResult>(
                10_000, Duration.ofSeconds(30), CheckKey::resourceIndex);
        var transport = new CachedTransport(delegate, cache);

        // Populate cache
        var warmup = transport.check(sameRequest());
        assertThat(warmup.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);

        // Now delete the relationship via delegate directly (simulating external change)
        delegate.deleteRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.DELETE,
                        ResourceRef.of("document", "doc-1"),
                        Relation.of("view"),
                        SubjectRef.user("alice"))));

        // Write through CachedTransport to trigger cache invalidation
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "doc-1"),
                        Relation.of("editor"),
                        SubjectRef.user("bob"))));

        // After invalidation, the cached HAS_PERMISSION for (doc-1, view, alice) should be gone.
        // A fresh check should hit the delegate, which no longer has the (view, alice) relationship.
        var afterWrite = transport.check(sameRequest());
        assertThat(afterWrite.permissionship()).isEqualTo(Permissionship.NO_PERMISSION);
    }

    // ======================================================================
    // 6. TokenTracker — per-resource-type isolation
    // ======================================================================

    @Test
    void tokenTracker_perResourceType_noInterference() throws Exception {
        var tracker = new TokenTracker();
        int threadCount = 100;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                if (idx % 2 == 0) {
                    tracker.recordWrite("document", "doc-token-" + idx);
                } else {
                    tracker.recordWrite("folder", "folder-token-" + idx);
                }
            }));
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        for (var f : futures) {
            f.get(); // propagate exceptions
        }

        // Resolve session consistency for each type
        var docConsistency = tracker.resolve(ReadConsistency.session(), "document");
        var folderConsistency = tracker.resolve(ReadConsistency.session(), "folder");

        // Both should resolve to AtLeast (not MinimizeLatency), meaning tokens were recorded
        assertThat(docConsistency).isInstanceOf(Consistency.AtLeast.class);
        assertThat(folderConsistency).isInstanceOf(Consistency.AtLeast.class);

        // The tokens must belong to the correct resource type
        String docToken = ((Consistency.AtLeast) docConsistency).zedToken();
        String folderToken = ((Consistency.AtLeast) folderConsistency).zedToken();

        assertThat(docToken).startsWith("doc-token-");
        assertThat(folderToken).startsWith("folder-token-");
    }

    // ======================================================================
    // 8. ResilientTransport — retry budget limits retries under load
    // ======================================================================

    @Test
    void retryBudget_limitsRetries_underLoad() throws Exception {
        var callCount = new AtomicInteger();
        SdkTransport failingDelegate = new InMemoryTransport() {
            @Override
            public CheckResult check(CheckRequest request) {
                callCount.incrementAndGet();
                throw new AuthxConnectionException("always fails");
            }
        };

        var policy = PolicyRegistry.builder()
                .defaultPolicy(ResourcePolicy.builder()
                        .retry(RetryPolicy.builder()
                                .maxAttempts(3)
                                .baseDelay(Duration.ofMillis(1))
                                .maxDelay(Duration.ofMillis(5))
                                .build())
                        .circuitBreaker(CircuitBreakerPolicy.disabled())
                        .build())
                .build();

        var transport = new ResilientTransport(failingDelegate, policy, new DefaultTypedEventBus());

        int requestCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CyclicBarrier barrier = new CyclicBarrier(requestCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                try {
                    transport.check(sameRequest());
                } catch (AuthxConnectionException ignored) {
                    // expected
                }
            }));
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        for (var f : futures) {
            f.get(); // propagate unexpected exceptions
        }

        // Without budget: 100 requests * 3 attempts = 300 delegate calls.
        // With 20% retry budget, total retries should be significantly less than 200 extra calls.
        // The budget kicks in once we have >= 25 requests in the 1-second window.
        // Allow generous tolerance since timing-dependent: total calls < 100 * 2
        assertThat(callCount.get())
                .as("retry budget should prevent full retry fan-out; total calls should be well under 300")
                .isLessThan(requestCount * 2);
    }
}
