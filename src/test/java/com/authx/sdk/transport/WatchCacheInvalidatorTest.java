package com.authx.sdk.transport;

import com.authx.sdk.cache.Cache;
import com.authx.sdk.cache.CaffeineCache;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.RelationshipChange;
import com.authx.sdk.model.enums.Permissionship;
import com.authzed.api.v1.*;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class WatchCacheInvalidatorTest {

    @Test
    void close_stopsWatchThread() {
        Cache<CheckKey, CheckResult> noop = Cache.noop();
        var invalidator = new WatchCacheInvalidator(
                ManagedChannelBuilder.forTarget("localhost:0").usePlaintext().build(),
                "test-key",
                noop,
                new SdkMetrics());
        invalidator.start();

        assertThat(invalidator.isRunning()).isTrue();
        invalidator.close();
        assertThat(invalidator.isRunning()).isFalse();
    }

    @Test
    void permanentError_unimplemented_stopsWatch() throws Exception {
        // Server that returns UNIMPLEMENTED for Watch
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        responseObserver.onError(Status.UNIMPLEMENTED
                                .withDescription("Watch not supported").asRuntimeException());
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var metrics = new SdkMetrics();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), metrics);
        invalidator.start();

        // Wait for watchLoop to detect permanent error and stop
        assertThat(awaitCondition(() -> !invalidator.isRunning(), 5000))
                .as("Watch should stop on UNIMPLEMENTED").isTrue();

        // No reconnect attempts for permanent errors
        assertThat(metrics.watchReconnects()).isZero();

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void permanentError_unauthenticated_stopsWatch() throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        responseObserver.onError(Status.UNAUTHENTICATED
                                .withDescription("Bad key").asRuntimeException());
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var invalidator = new WatchCacheInvalidator(channel, "bad-key", Cache.noop(), new SdkMetrics());
        invalidator.start();

        assertThat(awaitCondition(() -> !invalidator.isRunning(), 5000))
                .as("Watch should stop on UNAUTHENTICATED").isTrue();

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void transientError_reconnectsUpToLimit() throws Exception {
        // Server that always returns UNAVAILABLE (never connects successfully)
        AtomicInteger attemptCount = new AtomicInteger(0);
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        attemptCount.incrementAndGet();
                        responseObserver.onError(Status.UNAVAILABLE
                                .withDescription("Server busy").asRuntimeException());
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var metrics = new SdkMetrics();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), metrics);
        invalidator.start();

        // Never connected → MAX_FAILURES_NEVER_CONNECTED = 3
        assertThat(awaitCondition(() -> !invalidator.isRunning(), 15000))
                .as("Watch should stop after 3 failures (never connected)").isTrue();

        // At least 3 attempts, reconnect metrics recorded
        assertThat(attemptCount.get()).isGreaterThanOrEqualTo(3);
        assertThat(metrics.watchReconnects()).isGreaterThanOrEqualTo(2); // first failure + reconnects

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void watchResponse_invalidatesCache() throws Exception {
        // Prepare cache with a known entry
        var cache = new CaffeineCache<CheckKey, CheckResult>(1000, Duration.ofMinutes(5), CheckKey::resourceIndex);
        var key = CheckKey.of(ResourceRef.of("document", "doc-1"), Permission.of("view"), SubjectRef.of("user", "alice", null));
        var result = new CheckResult(Permissionship.HAS_PERMISSION, "token1", Optional.empty());
        cache.put(key, result);
        assertThat(cache.getIfPresent(key)).isNotNull();

        // Server sends one WatchResponse then holds stream open
        CountDownLatch responseSent = new CountDownLatch(1);
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        var update = RelationshipUpdate.newBuilder()
                                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                                .setRelationship(Relationship.newBuilder()
                                        .setResource(ObjectReference.newBuilder()
                                                .setObjectType("document").setObjectId("doc-1"))
                                        .setRelation("editor")
                                        .setSubject(SubjectReference.newBuilder()
                                                .setObject(ObjectReference.newBuilder()
                                                        .setObjectType("user").setObjectId("bob"))))
                                .build();
                        var resp = WatchResponse.newBuilder()
                                .addUpdates(update)
                                .setChangesThrough(ZedToken.newBuilder().setToken("tok-2"))
                                .build();
                        responseObserver.onNext(resp);
                        responseSent.countDown();
                        // Keep stream open — don't call onCompleted
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", cache, new SdkMetrics());
        invalidator.start();

        // Wait for the WatchResponse to be processed
        assertThat(responseSent.await(5, TimeUnit.SECONDS)).isTrue();
        // Give watch thread time to process the invalidation
        assertThat(awaitCondition(() -> cache.getIfPresent(key) == null, 3000))
                .as("Cache entry for document:doc-1 should be invalidated").isTrue();

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void watchResponse_notifiesListeners() throws Exception {
        CountDownLatch responseSent = new CountDownLatch(1);
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        var update = RelationshipUpdate.newBuilder()
                                .setOperation(RelationshipUpdate.Operation.OPERATION_DELETE)
                                .setRelationship(Relationship.newBuilder()
                                        .setResource(ObjectReference.newBuilder()
                                                .setObjectType("folder").setObjectId("f-1"))
                                        .setRelation("viewer")
                                        .setSubject(SubjectReference.newBuilder()
                                                .setObject(ObjectReference.newBuilder()
                                                        .setObjectType("user").setObjectId("charlie"))))
                                .build();
                        responseObserver.onNext(WatchResponse.newBuilder()
                                .addUpdates(update)
                                .setChangesThrough(ZedToken.newBuilder().setToken("tok-3"))
                                .build());
                        responseSent.countDown();
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics());

        // Register listener
        AtomicReference<RelationshipChange> received = new AtomicReference<>();
        CountDownLatch listenerCalled = new CountDownLatch(1);
        invalidator.addListener(change -> {
            received.set(change);
            listenerCalled.countDown();
        });

        invalidator.start();

        assertThat(listenerCalled.await(5, TimeUnit.SECONDS)).isTrue();
        var change = received.get();
        assertThat(change).isNotNull();
        assertThat(change.operation()).isEqualTo(RelationshipChange.Operation.DELETE);
        assertThat(change.resourceType()).isEqualTo("folder");
        assertThat(change.resourceId()).isEqualTo("f-1");
        assertThat(change.relation()).isEqualTo("viewer");
        assertThat(change.subjectType()).isEqualTo("user");
        assertThat(change.subjectId()).isEqualTo("charlie");
        assertThat(change.zedToken()).isEqualTo("tok-3");

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void listenerException_doesNotCrashWatchThread() throws Exception {
        CountDownLatch secondListenerCalled = new CountDownLatch(1);
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        var update = RelationshipUpdate.newBuilder()
                                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                                .setRelationship(Relationship.newBuilder()
                                        .setResource(ObjectReference.newBuilder()
                                                .setObjectType("doc").setObjectId("1"))
                                        .setRelation("viewer")
                                        .setSubject(SubjectReference.newBuilder()
                                                .setObject(ObjectReference.newBuilder()
                                                        .setObjectType("user").setObjectId("x"))))
                                .build();
                        responseObserver.onNext(WatchResponse.newBuilder()
                                .addUpdates(update)
                                .setChangesThrough(ZedToken.newBuilder().setToken("t1"))
                                .build());
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics());

        // First listener throws
        invalidator.addListener(change -> { throw new RuntimeException("boom"); });
        // Second listener should still be called
        invalidator.addListener(change -> secondListenerCalled.countDown());

        invalidator.start();

        assertThat(secondListenerCalled.await(5, TimeUnit.SECONDS))
                .as("Second listener should be called despite first throwing").isTrue();
        assertThat(invalidator.isRunning()).isTrue();

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void reconnect_resumesFromLastToken() throws Exception {
        // Server that sends one response, then errors, then checks resume token on reconnect
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicReference<String> resumeToken = new AtomicReference<>();
        CountDownLatch secondCallReceived = new CountDownLatch(1);

        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        int call = callCount.incrementAndGet();
                        if (call == 1) {
                            // First call: send a response with token, then error
                            var update = RelationshipUpdate.newBuilder()
                                    .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                                    .setRelationship(Relationship.newBuilder()
                                            .setResource(ObjectReference.newBuilder()
                                                    .setObjectType("doc").setObjectId("1"))
                                            .setRelation("v")
                                            .setSubject(SubjectReference.newBuilder()
                                                    .setObject(ObjectReference.newBuilder()
                                                            .setObjectType("user").setObjectId("a"))))
                                    .build();
                            responseObserver.onNext(WatchResponse.newBuilder()
                                    .addUpdates(update)
                                    .setChangesThrough(ZedToken.newBuilder().setToken("resume-tok-42"))
                                    .build());
                            responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                        } else {
                            // Second call: capture the resume token
                            if (request.hasOptionalStartCursor()) {
                                resumeToken.set(request.getOptionalStartCursor().getToken());
                            }
                            secondCallReceived.countDown();
                            // Hold stream open
                        }
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics());
        invalidator.start();

        assertThat(secondCallReceived.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(resumeToken.get()).isEqualTo("resume-tok-42");

        invalidator.close();
        server.shutdownNow();
    }

    // ---- Helper ----

    private static boolean awaitCondition(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
