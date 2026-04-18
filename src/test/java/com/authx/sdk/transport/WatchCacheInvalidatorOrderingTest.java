package com.authx.sdk.transport;

import com.authx.sdk.cache.CaffeineCache;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.enums.Permissionship;
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

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SR:C2 — Cache invalidation precedes listener dispatch.
 *
 * <p>Asserts the happens-before invariant documented in
 * {@link WatchCacheInvalidator#processResponse}: when a user listener is
 * invoked for a relationship change, the corresponding cache entry for that
 * {@code (resourceType, resourceId)} MUST already be invalidated.
 *
 * <p>Why it matters: if a listener fired BEFORE the cache invalidation ran,
 * a consumer acting on the listener event (e.g. re-checking permission, or
 * reading a fan-out cache) could observe the old cached decision, mixing
 * "new world via listener" with "old world via cache" — a read-your-writes
 * violation.
 */
class WatchCacheInvalidatorOrderingTest {

    /**
     * Pushes N single-update WatchResponses through the stream (each for a
     * distinct pre-populated cache key). In the listener we immediately look
     * up the cache by the event's resource key. If the ordering invariant
     * holds, the cache must report {@code null} every time.
     *
     * <p>N=1000 is chosen to exercise enough (a) listener-dispatch parallelism
     * vs (b) watch-thread invalidation to make any reordering measurable,
     * while keeping the test under the 30s gradle per-test default.
     */
    @Test
    void invalidateBeforeDispatch_1000ConcurrentPairs() throws Exception {
        final int N = 1000;

        // Real cache — we need CaffeineCache.getIfPresent to be the actual
        // observation surface.
        var cache = new CaffeineCache<CheckKey, CheckResult>(
                N * 2L, Duration.ofMinutes(5), CheckKey::resourceIndex);

        // Outbound queue of responses the mock server will pump to the client.
        BlockingQueue<WatchResponse> outbound = new LinkedBlockingQueue<>();
        CountDownLatch clientConnected = new CountDownLatch(1);
        AtomicReference<Thread> pumpThread = new AtomicReference<>();

        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> observer) {
                        clientConnected.countDown();
                        Thread t = new Thread(() -> {
                            try {
                                while (!Thread.currentThread().isInterrupted()) {
                                    WatchResponse r = outbound.take();
                                    observer.onNext(r);
                                }
                            } catch (InterruptedException ignored) {
                            } catch (Exception ignored) {
                                // stream closed
                            }
                        }, "watch-pump");
                        t.setDaemon(true);
                        t.start();
                        pumpThread.set(t);
                    }
                })
                .build().start();

        ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor().build();
        var invalidator = new WatchCacheInvalidator(
                channel, "test-key", cache, new SdkMetrics());

        AtomicInteger violations = new AtomicInteger(0);
        AtomicInteger eventsSeen = new AtomicInteger(0);

        invalidator.addListener(change -> {
            // At the moment this listener runs, the SR:C2 invariant requires
            // that the cache entry for (resourceType, resourceId) has ALREADY
            // been invalidated. We reconstruct the CheckKey we pre-populated
            // and assert the cache no longer has it.
            var key = CheckKey.of(
                    ResourceRef.of(change.resourceType(), change.resourceId()),
                    Permission.of("view"),
                    SubjectRef.of("user", "alice", null));
            if (cache.getIfPresent(key) != null) {
                violations.incrementAndGet();
            }
            eventsSeen.incrementAndGet();
        });

        invalidator.start();
        assertThat(clientConnected.await(5, TimeUnit.SECONDS))
                .as("Watch client should connect to mock server").isTrue();

        // Pre-populate N cache entries; enqueue N matching Watch responses.
        for (int i = 0; i < N; i++) {
            String resId = "doc-" + i;
            var key = CheckKey.of(
                    ResourceRef.of("document", resId),
                    Permission.of("view"),
                    SubjectRef.of("user", "alice", null));
            cache.put(key, new CheckResult(
                    Permissionship.HAS_PERMISSION, "tok-pre-" + i, Optional.empty()));

            RelationshipUpdate update = RelationshipUpdate.newBuilder()
                    .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                    .setRelationship(Relationship.newBuilder()
                            .setResource(ObjectReference.newBuilder()
                                    .setObjectType("document").setObjectId(resId))
                            .setRelation("viewer")
                            .setSubject(SubjectReference.newBuilder()
                                    .setObject(ObjectReference.newBuilder()
                                            .setObjectType("user").setObjectId("alice"))))
                    .build();
            outbound.put(WatchResponse.newBuilder()
                    .addUpdates(update)
                    .setChangesThrough(ZedToken.newBuilder().setToken("t-" + i))
                    .build());
        }

        // Wait until the listener has seen all N events (or timeout).
        long deadline = System.currentTimeMillis() + 15_000;
        while (eventsSeen.get() < N && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }

        assertThat(eventsSeen.get())
                .as("Listener should have received all %d events", N)
                .isEqualTo(N);
        assertThat(violations.get())
                .as("SR:C2 invariant: listener must never see a cache hit for its event's key")
                .isZero();

        invalidator.close();
        if (pumpThread.get() != null) pumpThread.get().interrupt();
        channel.shutdownNow();
        server.shutdownNow();
    }
}
