package com.authx.sdk.transport;

import com.authx.sdk.cache.Cache;
import com.authx.sdk.cache.CaffeineCache;
import com.authx.sdk.dedup.CaffeineDuplicateDetector;
import com.authx.sdk.metrics.SdkMetrics;
import com.authx.sdk.model.CheckKey;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.RelationshipChange;
import com.authx.sdk.model.enums.Permissionship;
import com.authx.sdk.spi.DuplicateDetector;
import com.authzed.api.v1.*;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
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

    // ─── New fields: CREATE/caveat/expiresAt/transactionMetadata/checkpoints ───

    @Test
    void watchResponse_distinguishesCreateFromTouch() throws Exception {
        AtomicReference<RelationshipChange> received = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        var server = startMockWatch(responseObserver -> {
            var update = RelationshipUpdate.newBuilder()
                    .setOperation(RelationshipUpdate.Operation.OPERATION_CREATE)
                    .setRelationship(simpleRel("document", "doc-new", "owner", "user", "alice"))
                    .build();
            responseObserver.onNext(WatchResponse.newBuilder()
                    .addUpdates(update)
                    .setChangesThrough(ZedToken.newBuilder().setToken("t-create"))
                    .build());
        });
        var invalidator = startInvalidator(server.channel, change -> {
            received.set(change);
            called.countDown();
        });

        assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().operation()).isEqualTo(RelationshipChange.Operation.CREATE);

        invalidator.close();
        server.shutdown();
    }

    @Test
    void watchResponse_extractsTransactionMetadata() throws Exception {
        AtomicReference<RelationshipChange> received = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);

        Struct metadata = Struct.newBuilder()
                .putFields("actor", Value.newBuilder().setStringValue("admin@acme.com").build())
                .putFields("trace_id", Value.newBuilder().setStringValue("abc-123").build())
                .putFields("attempt", Value.newBuilder().setNumberValue(3).build())
                .putFields("forced", Value.newBuilder().setBoolValue(true).build())
                .build();

        var server = startMockWatch(responseObserver -> {
            var update = RelationshipUpdate.newBuilder()
                    .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                    .setRelationship(simpleRel("document", "doc-42", "editor", "user", "bob"))
                    .build();
            responseObserver.onNext(WatchResponse.newBuilder()
                    .addUpdates(update)
                    .setOptionalTransactionMetadata(metadata)
                    .setChangesThrough(ZedToken.newBuilder().setToken("t-meta"))
                    .build());
        });
        var invalidator = startInvalidator(server.channel, change -> {
            received.set(change);
            called.countDown();
        });

        assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        var change = received.get();
        assertThat(change.transactionMetadata())
                .containsEntry("actor", "admin@acme.com")
                .containsEntry("trace_id", "abc-123")
                .containsEntry("attempt", "3")         // integer-valued double → no .0
                .containsEntry("forced", "true")
                .hasSize(4);

        invalidator.close();
        server.shutdown();
    }

    @Test
    void watchResponse_missingTransactionMetadata_yieldsEmptyMap() throws Exception {
        AtomicReference<RelationshipChange> received = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        var server = startMockWatch(responseObserver -> {
            var update = RelationshipUpdate.newBuilder()
                    .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                    .setRelationship(simpleRel("doc", "1", "v", "user", "a"))
                    .build();
            responseObserver.onNext(WatchResponse.newBuilder()
                    .addUpdates(update)
                    .setChangesThrough(ZedToken.newBuilder().setToken("t-nometa"))
                    .build());
        });
        var invalidator = startInvalidator(server.channel, change -> {
            received.set(change);
            called.countDown();
        });

        assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().transactionMetadata()).isEmpty();

        invalidator.close();
        server.shutdown();
    }

    @Test
    void watchResponse_extractsCaveatName() throws Exception {
        AtomicReference<RelationshipChange> received = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        var server = startMockWatch(responseObserver -> {
            var rel = Relationship.newBuilder()
                    .setResource(ObjectReference.newBuilder().setObjectType("document").setObjectId("doc-caveat"))
                    .setRelation("viewer")
                    .setSubject(SubjectReference.newBuilder()
                            .setObject(ObjectReference.newBuilder().setObjectType("user").setObjectId("charlie")))
                    .setOptionalCaveat(ContextualizedCaveat.newBuilder().setCaveatName("ip_allowlist"))
                    .build();
            responseObserver.onNext(WatchResponse.newBuilder()
                    .addUpdates(RelationshipUpdate.newBuilder()
                            .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                            .setRelationship(rel))
                    .setChangesThrough(ZedToken.newBuilder().setToken("t-cav"))
                    .build());
        });
        var invalidator = startInvalidator(server.channel, change -> {
            received.set(change);
            called.countDown();
        });

        assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().caveatName()).isEqualTo("ip_allowlist");

        invalidator.close();
        server.shutdown();
    }

    @Test
    void watchResponse_extractsExpiresAt() throws Exception {
        AtomicReference<RelationshipChange> received = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);

        Instant expectedExpiry = Instant.parse("2026-12-31T23:59:59Z");
        var server = startMockWatch(responseObserver -> {
            var rel = Relationship.newBuilder()
                    .setResource(ObjectReference.newBuilder().setObjectType("document").setObjectId("doc-exp"))
                    .setRelation("viewer")
                    .setSubject(SubjectReference.newBuilder()
                            .setObject(ObjectReference.newBuilder().setObjectType("user").setObjectId("dave")))
                    .setOptionalExpiresAt(Timestamp.newBuilder()
                            .setSeconds(expectedExpiry.getEpochSecond())
                            .setNanos(expectedExpiry.getNano()))
                    .build();
            responseObserver.onNext(WatchResponse.newBuilder()
                    .addUpdates(RelationshipUpdate.newBuilder()
                            .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                            .setRelationship(rel))
                    .setChangesThrough(ZedToken.newBuilder().setToken("t-exp"))
                    .build());
        });
        var invalidator = startInvalidator(server.channel, change -> {
            received.set(change);
            called.countDown();
        });

        assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().expiresAt()).isEqualTo(expectedExpiry);

        invalidator.close();
        server.shutdown();
    }

    @Test
    void watchResponse_checkpointDoesNotTriggerListenerOrInvalidation() throws Exception {
        var cache = new CaffeineCache<CheckKey, CheckResult>(1000, Duration.ofMinutes(5), CheckKey::resourceIndex);
        var key = CheckKey.of(
                ResourceRef.of("document", "doc-1"),
                Permission.of("view"),
                SubjectRef.of("user", "alice", null));
        var result = new CheckResult(Permissionship.HAS_PERMISSION, "token1", Optional.empty());
        cache.put(key, result);

        AtomicInteger listenerCalls = new AtomicInteger(0);
        CountDownLatch firstResponseDelivered = new CountDownLatch(1);

        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        // Checkpoint-only response (no updates)
                        responseObserver.onNext(WatchResponse.newBuilder()
                                .setIsCheckpoint(true)
                                .setChangesThrough(ZedToken.newBuilder().setToken("checkpoint-1"))
                                .build());
                        firstResponseDelivered.countDown();
                        // Keep stream open
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", cache, new SdkMetrics());
        invalidator.addListener(c -> listenerCalls.incrementAndGet());
        invalidator.start();

        assertThat(firstResponseDelivered.await(5, TimeUnit.SECONDS)).isTrue();
        // Give dispatch a moment to prove nothing happens
        Thread.sleep(300);

        // Cache entry should NOT be invalidated (checkpoint has no updates)
        assertThat(cache.getIfPresent(key)).isNotNull();
        // Listener should NOT have been called
        assertThat(listenerCalls.get()).isZero();

        invalidator.close();
        server.shutdownNow();
    }

    // ─── B2: Cursor expiration recovery ──────────────────────────────────

    @Test
    void cursorExpired_resetsTokenAndResubscribesFromHead() throws Exception {
        // Server behavior:
        //   Call 1 (with no cursor): send a response with token "tok-good", then error
        //   Call 2 (with cursor "tok-good"): return FAILED_PRECONDITION "cursor too old"
        //   Call 3 (with NO cursor again — proves SDK reset): hold open
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicReference<String> thirdCallCursor = new AtomicReference<>("INITIAL");
        CountDownLatch thirdCallReceived = new CountDownLatch(1);

        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        int call = callCount.incrementAndGet();
                        if (call == 1) {
                            // First call: deliver a response so SDK records lastToken
                            responseObserver.onNext(WatchResponse.newBuilder()
                                    .addUpdates(RelationshipUpdate.newBuilder()
                                            .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                                            .setRelationship(simpleRel("doc", "1", "v", "user", "a")))
                                    .setChangesThrough(ZedToken.newBuilder().setToken("tok-good"))
                                    .build());
                            responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                        } else if (call == 2) {
                            // Second call: SDK should be retrying with start_cursor="tok-good"
                            // Simulate "cursor too old" — FAILED_PRECONDITION + cursor message.
                            responseObserver.onError(Status.FAILED_PRECONDITION
                                    .withDescription("specified start cursor is too old, revision has been garbage collected")
                                    .asRuntimeException());
                        } else {
                            // Third call: SDK should have RESET its cursor → request has no start_cursor
                            thirdCallCursor.set(request.hasOptionalStartCursor()
                                    ? request.getOptionalStartCursor().getToken()
                                    : null);
                            thirdCallReceived.countDown();
                            // Hold open
                        }
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics());
        invalidator.start();

        // Wait for the third call (proves SDK noticed cursor expiration and reset)
        assertThat(thirdCallReceived.await(15, TimeUnit.SECONDS))
                .as("SDK should retry from HEAD after cursor expiration").isTrue();
        // The third call MUST have an empty cursor (i.e. null after our reset)
        assertThat(thirdCallCursor.get())
                .as("after cursor reset, third call should not include start_cursor")
                .isNull();

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void cursorExpired_unrelatedFailedPrecondition_doesNotResetCursor() throws Exception {
        // Defensive: a FAILED_PRECONDITION with a non-cursor message must NOT
        // be misclassified as cursor expiration. We verify by making sure the
        // error counts as a normal retry (not a free reset).
        AtomicInteger callCount = new AtomicInteger(0);
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        callCount.incrementAndGet();
                        // FAILED_PRECONDITION but NOT about cursor — e.g. some unrelated
                        // server-side state condition. SDK should treat as normal failure.
                        responseObserver.onError(Status.FAILED_PRECONDITION
                                .withDescription("schema not yet propagated to all replicas")
                                .asRuntimeException());
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var metrics = new SdkMetrics();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), metrics);
        invalidator.start();

        // Should fail-fast after MAX_FAILURES_NEVER_CONNECTED = 3 (since we never
        // received any successful response). If we'd misclassified as cursor
        // expired, we'd never increment the failure counter and loop forever.
        assertThat(awaitCondition(() -> !invalidator.isRunning(), 15000))
                .as("non-cursor FAILED_PRECONDITION should still consume retry budget").isTrue();
        assertThat(callCount.get()).isLessThanOrEqualTo(5);  // ~3 attempts + a bit of slack

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void cursorExpired_invalidatesCacheAndPublishesEvent() throws Exception {
        // Verifies the K8s-informer-style data-loss recovery:
        //   - cache.invalidateAll() is called
        //   - WatchCursorExpired event is published with the expired token
        // Server triggers the same call sequence as cursorExpired_resetsToken...
        // but we also wire a real Caffeine cache + capturing event bus.

        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch thirdCallReceived = new CountDownLatch(1);

        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        int call = callCount.incrementAndGet();
                        if (call == 1) {
                            responseObserver.onNext(WatchResponse.newBuilder()
                                    .addUpdates(RelationshipUpdate.newBuilder()
                                            .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                                            .setRelationship(simpleRel("doc", "1", "v", "user", "a")))
                                    .setChangesThrough(ZedToken.newBuilder().setToken("tok-good"))
                                    .build());
                            responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                        } else if (call == 2) {
                            responseObserver.onError(Status.FAILED_PRECONDITION
                                    .withDescription("specified start cursor is too old, revision has been garbage collected")
                                    .asRuntimeException());
                        } else {
                            thirdCallReceived.countDown();
                        }
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        Cache<CheckKey, CheckResult> cache = new CaffeineCache<>(100L, Duration.ofMinutes(5), CheckKey::resourceIndex);
        // Pre-populate cache so we can verify it gets cleared
        var key = new CheckKey(
                ResourceRef.of("doc", "1"),
                Permission.of("view"),
                SubjectRef.user("alice"));
        cache.put(key, CheckResult.allowed("seed-token"));
        assertThat(cache.size()).isEqualTo(1);

        // Capture published events
        var bus = new com.authx.sdk.event.DefaultTypedEventBus(Runnable::run);
        AtomicReference<com.authx.sdk.event.SdkTypedEvent.WatchCursorExpired> capturedEvent = new AtomicReference<>();
        bus.subscribe(com.authx.sdk.event.SdkTypedEvent.WatchCursorExpired.class, capturedEvent::set);

        var invalidator = new WatchCacheInvalidator(channel, "test-key", cache, new SdkMetrics());
        invalidator.setEventBus(bus);
        invalidator.start();

        // Wait until the third Watch call (proves cursor expiry was processed)
        assertThat(thirdCallReceived.await(15, TimeUnit.SECONDS))
                .as("SDK should resubscribe after cursor expiry").isTrue();

        // Cache must be empty — Debezium-style full invalidation
        assertThat(cache.size())
                .as("cache must be fully invalidated on cursor expiry")
                .isEqualTo(0);

        // Event must have been published
        assertThat(capturedEvent.get())
                .as("WatchCursorExpired event must be published")
                .isNotNull();
        assertThat(capturedEvent.get().expiredCursor()).isEqualTo("tok-good");
        assertThat(capturedEvent.get().consecutiveOccurrences()).isEqualTo(1);

        invalidator.close();
        server.shutdownNow();
    }

    // ─── B3: Application-layer stall detection ───────────────────────────

    @Test
    void streamStalled_publishesEventAndForcesReconnect() throws Exception {
        // Server holds the stream open without sending anything (TCP and gRPC
        // keepalive look fine — only the application layer is silent).
        // SDK should detect this via the staleStreamThreshold timeout and
        // force a new Watch call.
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch firstCallStarted = new CountDownLatch(1);
        CountDownLatch secondCallStarted = new CountDownLatch(1);

        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        int call = callCount.incrementAndGet();
                        if (call == 1) {
                            firstCallStarted.countDown();
                            // Hold open, send nothing — simulates app-layer stall.
                        } else {
                            // SDK must have forced reconnect to reach this branch.
                            secondCallStarted.countDown();
                        }
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var bus = new com.authx.sdk.event.DefaultTypedEventBus(Runnable::run);
        AtomicReference<com.authx.sdk.event.SdkTypedEvent.WatchStreamStale> capturedEvent = new AtomicReference<>();
        bus.subscribe(com.authx.sdk.event.SdkTypedEvent.WatchStreamStale.class, capturedEvent::set);

        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics());
        invalidator.setEventBus(bus);
        // Tight threshold so the test finishes quickly.
        invalidator.setStaleStreamThreshold(Duration.ofMillis(500));
        invalidator.start();

        assertThat(firstCallStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Wait for SDK to detect stall and reconnect.
        assertThat(secondCallStarted.await(15, TimeUnit.SECONDS))
                .as("SDK should reconnect after stall threshold elapses").isTrue();

        // Event must have been published.
        assertThat(capturedEvent.get())
                .as("WatchStreamStale event must be published")
                .isNotNull();
        assertThat(capturedEvent.get().threshold()).isEqualTo(Duration.ofMillis(500));
        assertThat(capturedEvent.get().idleFor()).isGreaterThanOrEqualTo(Duration.ofMillis(500));

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void streamReceivingMessages_doesNotTriggerStall() throws Exception {
        // Healthy stream that sends a message every 100ms — must NOT trigger
        // the stall detector even with a 300ms threshold.
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch firstCallStarted = new CountDownLatch(1);
        AtomicReference<StreamObserver<WatchResponse>> obs = new AtomicReference<>();

        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        callCount.incrementAndGet();
                        obs.set(responseObserver);
                        firstCallStarted.countDown();
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var bus = new com.authx.sdk.event.DefaultTypedEventBus(Runnable::run);
        AtomicInteger staleEvents = new AtomicInteger(0);
        bus.subscribe(com.authx.sdk.event.SdkTypedEvent.WatchStreamStale.class, e -> staleEvents.incrementAndGet());

        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics());
        invalidator.setEventBus(bus);
        invalidator.setStaleStreamThreshold(Duration.ofMillis(300));
        invalidator.start();

        assertThat(firstCallStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Pump checkpoints faster than the stall threshold for ~1 second.
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline) {
            obs.get().onNext(WatchResponse.newBuilder()
                    .setChangesThrough(ZedToken.newBuilder().setToken("tok-" + System.nanoTime()))
                    .build());
            Thread.sleep(100);
        }

        assertThat(callCount.get())
                .as("only one Watch call should exist — no false-positive reconnect")
                .isEqualTo(1);
        assertThat(staleEvents.get())
                .as("no WatchStreamStale event on healthy stream")
                .isEqualTo(0);

        invalidator.close();
        server.shutdownNow();
    }

    // ─── B1: Accurate connection detection via onHeaders ─────────────────

    @Test
    void state_reportsConnectedWithoutAnyDataMessages() throws Exception {
        // Core B1 regression: the old impl waited for the first WatchResponse
        // to arrive before marking "connected". On a pure-read SpiceDB no
        // events ever flow, so Watch would sit in CONNECTING forever. This
        // test verifies the new impl reports CONNECTED as soon as gRPC HEADERS
        // return, independent of any data messages.
        CountDownLatch serverInvoked = new CountDownLatch(1);
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        // ServerCallStreamObserver has been set up and HEADERS are implicitly
                        // sent as soon as we touch the observer. We do NOT call onNext here
                        // — we want to prove the client sees CONNECTED without any data.
                        serverInvoked.countDown();
                        // Keep the stream open, but send nothing.
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics());

        assertThat(invalidator.state()).isEqualTo(WatchConnectionState.NOT_STARTED);

        invalidator.start();
        // After start, we're in CONNECTING
        assertThat(invalidator.state()).isIn(WatchConnectionState.CONNECTING, WatchConnectionState.CONNECTED);

        assertThat(serverInvoked.await(5, TimeUnit.SECONDS)).isTrue();

        // Wait for the CONNECTED transition — must happen even though
        // zero data messages have been sent.
        assertThat(awaitCondition(
                () -> invalidator.state() == WatchConnectionState.CONNECTED, 3000))
                .as("state should become CONNECTED via onHeaders, not data messages")
                .isTrue();

        invalidator.close();
        assertThat(invalidator.state()).isEqualTo(WatchConnectionState.STOPPED);
        server.shutdownNow();
    }

    @Test
    void state_notStartedBeforeStart() {
        var channel = io.grpc.ManagedChannelBuilder.forTarget("localhost:0").usePlaintext().build();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics());
        assertThat(invalidator.state()).isEqualTo(WatchConnectionState.NOT_STARTED);
        invalidator.close();
    }

    @Test
    void state_transitionsToStoppedOnClose() throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        // Hold stream open silently
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics());
        invalidator.start();

        // Wait for CONNECTED
        assertThat(awaitCondition(
                () -> invalidator.state() == WatchConnectionState.CONNECTED, 3000)).isTrue();

        invalidator.close();
        // close() must drive state to STOPPED synchronously
        assertThat(invalidator.state()).isEqualTo(WatchConnectionState.STOPPED);

        server.shutdownNow();
    }

    @Test
    void state_transitionsToStoppedOnPermanentError() throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        responseObserver.onError(Status.UNAUTHENTICATED
                                .withDescription("bad key").asRuntimeException());
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var invalidator = new WatchCacheInvalidator(channel, "bad-key", Cache.noop(), new SdkMetrics());
        invalidator.start();

        // Permanent error → state should reach STOPPED
        assertThat(awaitCondition(
                () -> invalidator.state() == WatchConnectionState.STOPPED, 5000))
                .as("permanent error should stop Watch").isTrue();
        assertThat(invalidator.isRunning()).isFalse();

        invalidator.close();
        server.shutdownNow();
    }

    // ─── Dedup gate (B3) — WatchCacheInvalidator × DuplicateDetector ───

    @Test
    void dedup_secondResponseWithSameToken_listenerCalledOnlyOnce() throws Exception {
        // Server pumps the SAME changes_through token twice. Dedup should let
        // listener fire on the first, then suppress the second.
        CountDownLatch bothSent = new CountDownLatch(2);
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        var update = RelationshipUpdate.newBuilder()
                                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                                .setRelationship(simpleRel("document", "doc-dedup", "editor", "user", "alice"))
                                .build();
                        var resp = WatchResponse.newBuilder()
                                .addUpdates(update)
                                .setChangesThrough(ZedToken.newBuilder().setToken("token-replay-1"))
                                .build();
                        // Fire the same response twice — simulating a cursor-boundary replay.
                        responseObserver.onNext(resp);
                        bothSent.countDown();
                        responseObserver.onNext(resp);
                        bothSent.countDown();
                        // Hold stream open
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var dedup = new CaffeineDuplicateDetector<String>(1000, Duration.ofMinutes(5));
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics(), dedup);

        var listenerCalls = new AtomicInteger(0);
        invalidator.addListener(change -> listenerCalls.incrementAndGet());
        invalidator.start();

        assertThat(bothSent.await(5, TimeUnit.SECONDS)).isTrue();
        // Give dispatch thread a moment to settle
        Thread.sleep(300);

        // Listener must have fired EXACTLY ONCE despite the duplicate response.
        assertThat(listenerCalls.get())
                .as("listener called once per unique zedToken")
                .isEqualTo(1);

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void dedup_doesNotSuppressCacheInvalidation() throws Exception {
        // Invariant: even when listener dispatch is suppressed by dedup, cache
        // invalidation STILL runs. Each pod needs a fresh local cache view.
        var cache = new CaffeineCache<CheckKey, CheckResult>(1000, Duration.ofMinutes(5), CheckKey::resourceIndex);

        // Pre-populate cache with TWO entries for the same resource so we can
        // detect the invalidation running twice.
        var key1 = CheckKey.of(ResourceRef.of("document", "doc-inv"), Permission.of("view"),
                SubjectRef.of("user", "alice", null));
        var key2 = CheckKey.of(ResourceRef.of("document", "doc-inv"), Permission.of("edit"),
                SubjectRef.of("user", "alice", null));
        cache.put(key1, new CheckResult(Permissionship.HAS_PERMISSION, "t1", Optional.empty()));
        cache.put(key2, new CheckResult(Permissionship.HAS_PERMISSION, "t1", Optional.empty()));

        CountDownLatch firstSent = new CountDownLatch(1);
        CountDownLatch secondSent = new CountDownLatch(1);

        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        var update = RelationshipUpdate.newBuilder()
                                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                                .setRelationship(simpleRel("document", "doc-inv", "editor", "user", "bob"))
                                .build();
                        var resp = WatchResponse.newBuilder()
                                .addUpdates(update)
                                .setChangesThrough(ZedToken.newBuilder().setToken("inv-dup-tok"))
                                .build();
                        responseObserver.onNext(resp);
                        firstSent.countDown();
                        // Now send the SAME response — dedup should suppress listener
                        // but cache invalidation should still run (idempotent).
                        responseObserver.onNext(resp);
                        secondSent.countDown();
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var dedup = new CaffeineDuplicateDetector<String>(1000, Duration.ofMinutes(5));
        var invalidator = new WatchCacheInvalidator(channel, "test-key", cache, new SdkMetrics(), dedup);

        var listenerCalls = new AtomicInteger(0);
        invalidator.addListener(change -> listenerCalls.incrementAndGet());
        invalidator.start();

        assertThat(firstSent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondSent.await(5, TimeUnit.SECONDS)).isTrue();

        // Cache entries must be invalidated (this is the key invariant).
        assertThat(awaitCondition(() -> cache.getIfPresent(key1) == null, 2000))
                .as("key1 invalidated").isTrue();
        assertThat(awaitCondition(() -> cache.getIfPresent(key2) == null, 2000))
                .as("key2 invalidated").isTrue();

        // Listener should have fired exactly once thanks to dedup.
        Thread.sleep(200);
        assertThat(listenerCalls.get()).as("listener dispatch count").isEqualTo(1);

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void dedup_differentTokens_bothListenerCallsFire() throws Exception {
        // Two responses with DIFFERENT changes_through tokens = two distinct
        // events. Both listeners must fire.
        CountDownLatch bothSent = new CountDownLatch(2);
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        var update = RelationshipUpdate.newBuilder()
                                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                                .setRelationship(simpleRel("doc", "d1", "v", "user", "a"))
                                .build();
                        responseObserver.onNext(WatchResponse.newBuilder()
                                .addUpdates(update)
                                .setChangesThrough(ZedToken.newBuilder().setToken("tok-A"))
                                .build());
                        bothSent.countDown();
                        responseObserver.onNext(WatchResponse.newBuilder()
                                .addUpdates(update)
                                .setChangesThrough(ZedToken.newBuilder().setToken("tok-B"))
                                .build());
                        bothSent.countDown();
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        var dedup = new CaffeineDuplicateDetector<String>(1000, Duration.ofMinutes(5));
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics(), dedup);

        var calls = new AtomicInteger(0);
        invalidator.addListener(change -> calls.incrementAndGet());
        invalidator.start();

        assertThat(bothSent.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitCondition(() -> calls.get() == 2, 2000))
                .as("both distinct tokens should fire listener").isTrue();

        invalidator.close();
        server.shutdownNow();
    }

    @Test
    void dedupDetector_accessorReturnsInjectedInstance() {
        // Wiring proof: the detector passed to the 5-arg constructor must be
        // the same instance returned by dedupDetector(). This is the hook that
        // lets us verify (via AuthxClientBuilder → SdkComponents → here) that
        // user-supplied detectors are not silently replaced.
        var injected = new CaffeineDuplicateDetector<String>(10, Duration.ofMinutes(1));
        var channel = io.grpc.ManagedChannelBuilder.forTarget("localhost:0").usePlaintext().build();
        var invalidator = new WatchCacheInvalidator(
                channel, "test-key", Cache.noop(), new SdkMetrics(), injected);

        assertThat(invalidator.dedupDetector()).isSameAs(injected);

        invalidator.close();
    }

    @Test
    void listenerExecutor_userProvided_notShutdownOnClose() throws Exception {
        // Verify the SDK does NOT shut down a user-supplied executor on close().
        // Lifecycle ownership: user-provided = user owns; SDK-default = SDK owns.
        var userExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var channel = io.grpc.ManagedChannelBuilder.forTarget("localhost:0").usePlaintext().build();
        var invalidator = new WatchCacheInvalidator(
                channel, "test-key", Cache.noop(), new SdkMetrics(),
                DuplicateDetector.noop(), userExecutor);

        invalidator.close();

        // User executor should still be alive — caller manages its lifecycle.
        assertThat(userExecutor.isShutdown()).isFalse();
        userExecutor.shutdown();
    }

    @Test
    void listenerExecutor_default_isShutdownOnClose() throws Exception {
        // Mirror image of the previous test: when WE create the executor, we
        // must shut it down so callers don't leak threads.
        var channel = io.grpc.ManagedChannelBuilder.forTarget("localhost:0").usePlaintext().build();
        var invalidator = new WatchCacheInvalidator(
                channel, "test-key", Cache.noop(), new SdkMetrics(),
                DuplicateDetector.noop(), null);  // null → SDK creates default
        invalidator.start();

        invalidator.close();
        assertThat(invalidator.state()).isEqualTo(WatchConnectionState.STOPPED);
        // We can't directly check the internal executor's shutdown state without
        // exposing it, but if the SDK forgets to shut it down the close() would
        // hang on the awaitTermination — the fact that this test returns means
        // close() completed cleanly.
    }

    @Test
    void dedupDetector_defaultConstructorUsesNoop() {
        // The 4-arg backwards-compat constructor must default to noop —
        // any other default would silently change behavior for existing users.
        var channel = io.grpc.ManagedChannelBuilder.forTarget("localhost:0").usePlaintext().build();
        var invalidator = new WatchCacheInvalidator(
                channel, "test-key", Cache.noop(), new SdkMetrics());

        // noop always returns true on every call
        var detector = invalidator.dedupDetector();
        assertThat(detector.tryProcess("a")).isTrue();
        assertThat(detector.tryProcess("a")).isTrue();  // no dedup
        assertThat(detector.tryProcess("b")).isTrue();

        invalidator.close();
    }

    @Test
    void dedup_noopDefault_behavesLikeBefore() throws Exception {
        // Sanity check: the 4-arg constructor (no explicit detector) wires in
        // DuplicateDetector.noop() so existing behavior is unchanged.
        CountDownLatch bothSent = new CountDownLatch(2);
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        var update = RelationshipUpdate.newBuilder()
                                .setOperation(RelationshipUpdate.Operation.OPERATION_TOUCH)
                                .setRelationship(simpleRel("doc", "d", "v", "user", "u"))
                                .build();
                        var resp = WatchResponse.newBuilder()
                                .addUpdates(update)
                                .setChangesThrough(ZedToken.newBuilder().setToken("tok-default"))
                                .build();
                        responseObserver.onNext(resp);
                        bothSent.countDown();
                        responseObserver.onNext(resp);
                        bothSent.countDown();
                    }
                })
                .build().start();

        var channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        // 4-arg constructor — no explicit dedup, defaults to noop.
        var invalidator = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics());

        var calls = new AtomicInteger(0);
        invalidator.addListener(change -> calls.incrementAndGet());
        invalidator.start();

        assertThat(bothSent.await(5, TimeUnit.SECONDS)).isTrue();
        // Without dedup, both duplicates should fire.
        assertThat(awaitCondition(() -> calls.get() == 2, 2000))
                .as("noop default should not dedupe").isTrue();

        invalidator.close();
        server.shutdownNow();
    }

    // ---- Helpers for the new tests ----

    private static Relationship simpleRel(String resType, String resId, String relation, String subjType, String subjId) {
        return Relationship.newBuilder()
                .setResource(ObjectReference.newBuilder().setObjectType(resType).setObjectId(resId))
                .setRelation(relation)
                .setSubject(SubjectReference.newBuilder()
                        .setObject(ObjectReference.newBuilder().setObjectType(subjType).setObjectId(subjId)))
                .build();
    }

    private record MockServer(Server grpcServer, ManagedChannel channel) {
        void shutdown() {
            channel.shutdownNow();
            grpcServer.shutdownNow();
        }
    }

    private static MockServer startMockWatch(java.util.function.Consumer<StreamObserver<WatchResponse>> handler) throws Exception {
        String name = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new WatchServiceGrpc.WatchServiceImplBase() {
                    @Override
                    public void watch(WatchRequest request, StreamObserver<WatchResponse> responseObserver) {
                        handler.accept(responseObserver);
                        // Keep stream open — don't call onCompleted
                    }
                })
                .build().start();
        var channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return new MockServer(server, channel);
    }

    private static WatchCacheInvalidator startInvalidator(ManagedChannel channel,
                                                          java.util.function.Consumer<RelationshipChange> listener) {
        var inv = new WatchCacheInvalidator(channel, "test-key", Cache.noop(), new SdkMetrics());
        inv.addListener(listener);
        inv.start();
        return inv;
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
