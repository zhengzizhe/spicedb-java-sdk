package com.authx.sdk.transport;

import com.authx.sdk.cache.Cache;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.spi.DroppedListenerEvent;
import com.authx.sdk.spi.DuplicateDetector;
import com.authx.sdk.spi.QueueFullPolicy;
import com.authzed.api.v1.ObjectReference;
import com.authzed.api.v1.Relationship;
import com.authzed.api.v1.RelationshipUpdate;
import com.authzed.api.v1.SubjectReference;
import com.authzed.api.v1.WatchRequest;
import com.authzed.api.v1.WatchResponse;
import com.authzed.api.v1.WatchServiceGrpc;
import com.authzed.api.v1.ZedToken;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SR:C5 — Watch listener drop surface and backpressure policy.
 *
 * <p>Two scenarios:
 * <ul>
 *   <li>{@link #dropPolicy_invokesHandlerForEveryDrop()} — under
 *       {@link QueueFullPolicy#DROP} a saturation burst invokes the
 *       registered {@code watchListenerDropHandler} for every dropped
 *       event.</li>
 *   <li>{@link #backpressurePolicy_noDropsUnderSaturation()} — under
 *       {@link QueueFullPolicy#BLOCK_WITH_BACKPRESSURE} a slow listener
 *       stalls the watch thread (and therefore
 *       {@code ClientCall.request(1)}) so no event is dropped — everything
 *       eventually dispatches once the listener unblocks.</li>
 * </ul>
 */
class WatchListenerQueuePolicyTest {

    /**
     * Default executor queue capacity in {@link WatchCacheInvalidator}. Must
     * match {@code defaultListenerExecutor} — if that constant changes, this
     * test's BURST sizing has to change with it.
     */
    private static final int LISTENER_QUEUE_CAPACITY = 10_000;

    @Test
    void dropPolicy_invokesHandlerForEveryDrop() throws Exception {
        // Enough events to guarantee queue saturation. With the listener
        // blocked on latch, the watch thread enqueues tasks until the
        // 10 000-slot queue is full (plus 1 task is actively running in the
        // blocked listener), then every subsequent enqueue fires the
        // rejection handler.
        final int BURST = LISTENER_QUEUE_CAPACITY + 600;
        final int EXPECTED_MIN_DROPS = 500;  // headroom for the +1 running task

        CountDownLatch firstListenerCalled = new CountDownLatch(1);
        CountDownLatch unblockListener = new CountDownLatch(1);
        AtomicBoolean firstSeen = new AtomicBoolean(false);
        AtomicInteger listenerInvocations = new AtomicInteger(0);

        MockServer mock = MockServer.start(BURST);

        List<DroppedListenerEvent> dropped = new CopyOnWriteArrayList<>();
        var invalidator = new WatchCacheInvalidator(
                mock.channel, "test-key", Cache.noop(), new SdkMetrics(),
                DuplicateDetector.noop(), null,
                dropped::add,               // drop handler
                QueueFullPolicy.DROP);

        invalidator.addListener(change -> {
            if (firstSeen.compareAndSet(false, true)) {
                firstListenerCalled.countDown();
                try { unblockListener.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            listenerInvocations.incrementAndGet();
        });

        invalidator.start();

        // Wait for the listener to register the first event and block.
        assertThat(firstListenerCalled.await(5, TimeUnit.SECONDS))
                .as("first listener must fire before we check drops").isTrue();

        // Give the watch thread time to saturate the queue and accumulate drops.
        long deadline = System.currentTimeMillis() + 15_000;
        while (invalidator.droppedListenerEvents() < EXPECTED_MIN_DROPS
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        long drops = invalidator.droppedListenerEvents();
        assertThat(drops)
                .as("SR:C5 DROP: must drop at least (BURST - queue - 1) events")
                .isGreaterThanOrEqualTo(EXPECTED_MIN_DROPS);
        assertThat(dropped.size())
                .as("watchListenerDropHandler must be invoked for every dropped event")
                .isEqualTo((int) drops);

        // Structural check on the first dropped event payload.
        DroppedListenerEvent first = dropped.get(0);
        assertThat(first.resourceType()).isEqualTo("document");
        assertThat(first.resourceId()).startsWith("doc-");
        assertThat(first.zedToken()).startsWith("tok-");
        // queueDepth at the moment of drop should be ≈ queue capacity; exact
        // value depends on whether the rejection sampled size before or after
        // the running task popped, so give wider bounds.
        assertThat(first.queueDepth())
                .as("queueDepth at drop should be roughly queue capacity")
                .isBetween(LISTENER_QUEUE_CAPACITY - 100, LISTENER_QUEUE_CAPACITY);
        assertThat(first.timestamp()).isNotNull();

        unblockListener.countDown();
        invalidator.close();
        mock.shutdown();
    }

    @Test
    void backpressurePolicy_noDropsUnderSaturation() throws Exception {
        // Same burst, but under BLOCK_WITH_BACKPRESSURE we expect ZERO drops.
        // The watch thread should block on listenerQueue.put() once the queue
        // saturates, which stops it from calling ClientCall.request(1), which
        // stops SpiceDB from sending more events via HTTP/2 flow control.
        final int BURST = LISTENER_QUEUE_CAPACITY + 500;

        CountDownLatch firstListenerCalled = new CountDownLatch(1);
        CountDownLatch unblockListener = new CountDownLatch(1);
        AtomicBoolean firstSeen = new AtomicBoolean(false);
        AtomicInteger listenerInvocations = new AtomicInteger(0);
        List<DroppedListenerEvent> dropped = new CopyOnWriteArrayList<>();

        MockServer mock = MockServer.start(BURST);

        var invalidator = new WatchCacheInvalidator(
                mock.channel, "test-key", Cache.noop(), new SdkMetrics(),
                DuplicateDetector.noop(), null,
                dropped::add,
                QueueFullPolicy.BLOCK_WITH_BACKPRESSURE);

        invalidator.addListener(change -> {
            if (firstSeen.compareAndSet(false, true)) {
                firstListenerCalled.countDown();
                try { unblockListener.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            listenerInvocations.incrementAndGet();
        });

        invalidator.start();
        assertThat(firstListenerCalled.await(5, TimeUnit.SECONDS))
                .as("first listener must fire").isTrue();

        // Let the watch thread saturate the queue and block on put(). At this
        // point the listenerExecutor queue is at capacity, request(1) is not
        // being called, and the server is throttled by HTTP/2 flow control.
        Thread.sleep(750);

        // Invariant check while saturated: no drops under BACKPRESSURE.
        assertThat(invalidator.droppedListenerEvents())
                .as("BACKPRESSURE must not drop under saturation")
                .isZero();
        assertThat(dropped).as("drop handler must not be invoked").isEmpty();

        // Unblock — queue drains, watch thread resumes, server resumes, all events dispatch.
        unblockListener.countDown();

        long deadline = System.currentTimeMillis() + 20_000;
        while (listenerInvocations.get() < BURST
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(listenerInvocations.get())
                .as("all %d events must dispatch once listener unblocks", BURST)
                .isEqualTo(BURST);
        assertThat(invalidator.droppedListenerEvents())
                .as("final drop count must be zero under BLOCK_WITH_BACKPRESSURE")
                .isZero();

        invalidator.close();
        mock.shutdown();
    }

    // ─── Helpers ───

    private record MockServer(Server server, ManagedChannel channel) {
        void shutdown() {
            channel.shutdownNow();
            server.shutdownNow();
        }

        static MockServer start(int burst) throws Exception {
            String name = InProcessServerBuilder.generateName();
            Server server = InProcessServerBuilder.forName(name).directExecutor()
                    .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                        @Override
                        public void watch(WatchRequest request,
                                          StreamObserver<WatchResponse> obs) {
                            // Pump events from a dedicated thread so we don't
                            // block the test's gRPC executor. Under BACKPRESSURE
                            // this thread itself will eventually block in
                            // onNext() when HTTP/2 window is exhausted — that's
                            // the intended shape.
                            Thread t = new Thread(() -> {
                                try {
                                    for (int i = 0; i < burst; i++) {
                                        var update = RelationshipUpdate.newBuilder()
                                                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                                                .setRelationship(Relationship.newBuilder()
                                                        .setResource(ObjectReference.newBuilder()
                                                                .setObjectType("document")
                                                                .setObjectId("doc-" + i))
                                                        .setRelation("viewer")
                                                        .setSubject(SubjectReference.newBuilder()
                                                                .setObject(ObjectReference.newBuilder()
                                                                        .setObjectType("user").setObjectId("alice"))))
                                                .build();
                                        obs.onNext(WatchResponse.newBuilder()
                                                .addUpdates(update)
                                                .setChangesThrough(ZedToken.newBuilder().setToken("tok-" + i))
                                                .build());
                                    }
                                } catch (Exception ignored) {
                                    // Stream cancelled / closed mid-burst is expected.
                                }
                            }, "burst-pump");
                            t.setDaemon(true);
                            t.start();
                        }
                    })
                    .build().start();
            ManagedChannel channel = InProcessChannelBuilder.forName(name)
                    .directExecutor().build();
            return new MockServer(server, channel);
        }
    }
}
