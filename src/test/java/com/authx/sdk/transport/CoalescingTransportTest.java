package com.authx.sdk.transport;

import com.authx.sdk.exception.AuthxTimeoutException;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link CoalescingTransport} — request deduplication for concurrent identical checks.
 */
class CoalescingTransportTest {

    private InMemoryTransport inner;
    private SdkMetrics metrics;

    @BeforeEach
    void setup() {
        inner = new InMemoryTransport();
        metrics = new SdkMetrics();
        // Pre-populate: alice is editor on document:d1
        inner.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d1"),
                Relation.of("editor"),
                SubjectRef.of("user", "alice", null))));
    }

    @Test
    void singleRequestPassesThroughToDelegate() {
        com.authx.sdk.transport.CoalescingTransport transport = new CoalescingTransport(inner, metrics);
        com.authx.sdk.model.CheckResult result = transport.check(CheckRequest.of(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        assertThat(result.hasPermission()).isTrue();
        assertThat(metrics.coalescedRequests()).isZero();
    }

    @Test
    void concurrentIdenticalRequestsAreCoalesced() throws Exception {
        java.util.concurrent.atomic.AtomicInteger callCount = new AtomicInteger(0);
        java.util.concurrent.CountDownLatch latch = new CountDownLatch(1);

        // A slow delegate that counts invocations
        SdkTransport slowDelegate = new InMemoryTransport() {
            {
                writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "d1"),
                        Relation.of("editor"),
                        SubjectRef.of("user", "alice", null))));
            }

            @Override
            public CheckResult check(CheckRequest request) {
                callCount.incrementAndGet();
                try {
                    latch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.check(request);
            }
        };

        com.authx.sdk.transport.CoalescingTransport transport = new CoalescingTransport(slowDelegate, metrics);
        com.authx.sdk.model.CheckRequest request = CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency());

        // Launch multiple concurrent checks with the same request
        int threadCount = 5;
        java.util.concurrent.CyclicBarrier startBarrier = new CyclicBarrier(threadCount);
        java.util.concurrent.CompletableFuture[] futures = new CompletableFuture[threadCount];
        java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            futures[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    startBarrier.await(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return transport.check(request);
            }, executor);
        }

        // Give threads time to hit the coalescing map, then release
        Thread.sleep(200);
        latch.countDown();

        // All should complete successfully
        for (java.util.concurrent.CompletableFuture f : futures) {
            com.authx.sdk.model.CheckResult result = (CheckResult) f.get(5, TimeUnit.SECONDS);
            assertThat(result.hasPermission()).isTrue();
        }

        // Only one actual delegate call should have been made
        assertThat(callCount.get()).isEqualTo(1);
        // The other threads were coalesced
        assertThat(metrics.coalescedRequests()).isGreaterThanOrEqualTo(1);

        executor.shutdown();
    }

    @Test
    void differentRequestsAreNotCoalesced() {
        java.util.concurrent.atomic.AtomicInteger callCount = new AtomicInteger(0);
        SdkTransport countingDelegate = new InMemoryTransport() {
            {
                writeRelationships(List.of(
                        new SdkTransport.RelationshipUpdate(
                                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                                ResourceRef.of("document", "d1"),
                                Relation.of("editor"),
                                SubjectRef.of("user", "alice", null)),
                        new SdkTransport.RelationshipUpdate(
                                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                                ResourceRef.of("document", "d1"),
                                Relation.of("viewer"),
                                SubjectRef.of("user", "bob", null))));
            }

            @Override
            public CheckResult check(CheckRequest request) {
                callCount.incrementAndGet();
                return super.check(request);
            }
        };

        com.authx.sdk.transport.CoalescingTransport transport = new CoalescingTransport(countingDelegate, metrics);

        transport.check(CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));
        transport.check(CheckRequest.of("document", "d1", "viewer", "user", "bob", Consistency.minimizeLatency()));

        assertThat(callCount.get()).isEqualTo(2);
        assertThat(metrics.coalescedRequests()).isZero();
    }

    @Test
    void differentConsistencyLevelsAreNotCoalesced() {
        java.util.concurrent.atomic.AtomicInteger callCount = new AtomicInteger(0);
        SdkTransport countingDelegate = new InMemoryTransport() {
            {
                writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "d1"),
                        Relation.of("editor"),
                        SubjectRef.of("user", "alice", null))));
            }

            @Override
            public CheckResult check(CheckRequest request) {
                callCount.incrementAndGet();
                return super.check(request);
            }
        };

        com.authx.sdk.transport.CoalescingTransport transport = new CoalescingTransport(countingDelegate, metrics);

        transport.check(CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));
        transport.check(CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.full()));

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void delegateExceptionPropagatedToAllWaiters() throws Exception {
        java.util.concurrent.CountDownLatch latch = new CountDownLatch(1);

        SdkTransport failingDelegate = new InMemoryTransport() {
            @Override
            public CheckResult check(CheckRequest request) {
                try {
                    latch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("SpiceDB down");
            }
        };

        com.authx.sdk.transport.CoalescingTransport transport = new CoalescingTransport(failingDelegate, metrics);
        com.authx.sdk.model.CheckRequest request = CheckRequest.of("document", "d1", "editor", "user", "alice", Consistency.minimizeLatency());

        java.util.concurrent.CyclicBarrier startBarrier = new CyclicBarrier(3);
        java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(3);
        java.util.concurrent.CompletableFuture[] futures = new CompletableFuture[3];

        for (int i = 0; i < 3; i++) {
            futures[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    startBarrier.await(2, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return transport.check(request);
            }, executor);
        }

        Thread.sleep(200);
        latch.countDown();

        // All futures should fail with the same root cause
        for (java.util.concurrent.CompletableFuture f : futures) {
            assertThatThrownBy(() -> f.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("SpiceDB down");
        }

        executor.shutdown();
    }

    @Test
    void nonCheckOperationsPassThrough() {
        com.authx.sdk.transport.CoalescingTransport transport = new CoalescingTransport(inner, metrics);

        com.authx.sdk.model.GrantResult writeResult = transport.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                ResourceRef.of("document", "d2"),
                Relation.of("viewer"),
                SubjectRef.of("user", "bob", null))));

        assertThat(writeResult.count()).isEqualTo(1);
    }

    @Test
    void constructorWithoutMetricsWorks() {
        com.authx.sdk.transport.CoalescingTransport transport = new CoalescingTransport(inner);
        com.authx.sdk.model.CheckResult result = transport.check(CheckRequest.of(
                "document", "d1", "editor", "user", "alice", Consistency.minimizeLatency()));

        assertThat(result.hasPermission()).isTrue();
    }

    @Test
    void closeCleansDelegateAndInflightMap() {
        com.authx.sdk.transport.CoalescingTransport transport = new CoalescingTransport(inner, metrics);
        transport.close();
        // After close, inner transport should be cleared
        assertThat(inner.size()).isZero();
    }
}
