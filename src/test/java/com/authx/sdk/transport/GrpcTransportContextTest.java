package com.authx.sdk.transport;

import com.authx.sdk.exception.AuthxTimeoutException;
import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authzed.api.v1.CheckPermissionRequest;
import com.authzed.api.v1.CheckPermissionResponse;
import com.authzed.api.v1.PermissionsServiceGrpc;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SR:C1 — {@link GrpcTransport} propagates upstream {@link io.grpc.Context}
 * cancellation and deadline to the underlying gRPC call.
 */
class GrpcTransportContextTest {

    /**
     * When an upstream {@link Context.CancellableContext} is cancelled while a
     * slow gRPC call is in flight, the call must observe {@code CANCELLED}
     * within 50 ms — not run to its own policy deadline.
     */
    @Test
    void upstreamContextCancellation_propagatesWithin50ms() throws Exception {
        // Server that NEVER responds — holds the stream open so the client
        // has to observe cancellation to ever return.
        CountDownLatch serverReceivedCall = new CountDownLatch(1);
        AtomicReference<Status> observedStatus = new AtomicReference<>();

        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new PermissionsServiceGrpc.PermissionsServiceImplBase() {
                    @Override
                    public void checkPermission(CheckPermissionRequest request,
                                                StreamObserver<CheckPermissionResponse> obs) {
                        serverReceivedCall.countDown();
                        // Never respond — keep the call blocking.
                    }
                })
                .build().start();

        ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor().build();

        // Policy deadline = 30s (well beyond the 50ms cancellation budget).
        com.authx.sdk.transport.GrpcTransport transport = new GrpcTransport(channel, "test", 30_000);

        // Upstream cancellable context — simulates an HTTP handler thread
        // whose context gets cancelled mid-request.
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Context.CancellableContext upstream = Context.current().withCancellation();

        // Fire cancellation after 100ms (enough for the call to start streaming).
        scheduler.schedule(() -> upstream.cancel(
                Status.CANCELLED.withDescription("upstream cancelled").asRuntimeException()),
                100, TimeUnit.MILLISECONDS);

        Thread caller = new Thread(() -> {
            Context prev = upstream.attach();
            try {
                transport.check(CheckRequest.of(
                        ResourceRef.of("doc", "1"),
                        Permission.of("view"),
                        SubjectRef.of("user", "alice", null),
                        Consistency.minimizeLatency()));
            } catch (Throwable t) {
                observedStatus.set(Status.fromThrowable(t));
            } finally {
                upstream.detach(prev);
            }
        }, "upstream-caller");

        long start = System.nanoTime();
        caller.start();

        assertThat(serverReceivedCall.await(2, TimeUnit.SECONDS))
                .as("server must receive the call before cancellation fires").isTrue();

        caller.join(2_000);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Cancellation fired at 100ms; propagation must complete within 50ms
        // after that. Allow some scheduling slack — total budget 250ms.
        assertThat(elapsedMs)
                .as("call must return within 250ms of upstream cancel (not policy 30s deadline)")
                .isLessThan(250);
        assertThat(observedStatus.get()).isNotNull();
        assertThat(observedStatus.get().getCode())
                .as("status observed by caller must be CANCELLED")
                .isEqualTo(Status.Code.CANCELLED);

        transport.close();
        channel.shutdownNow();
        server.shutdownNow();
        scheduler.shutdownNow();
    }

    /**
     * When the upstream context carries a deadline TIGHTER than the SDK's
     * policy timeout, the gRPC call must fail at the upstream deadline, not
     * the (looser) policy one.
     */
    @Test
    void upstreamTighterDeadline_winsOverPolicy() throws Exception {
        // Slow server — takes 5s to respond, far beyond both deadlines.
        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new PermissionsServiceGrpc.PermissionsServiceImplBase() {
                    @Override
                    public void checkPermission(CheckPermissionRequest request,
                                                StreamObserver<CheckPermissionResponse> obs) {
                        // Never respond.
                    }
                })
                .build().start();

        ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
                .directExecutor().build();

        // Policy deadline = 30s (loose).
        com.authx.sdk.transport.GrpcTransport transport = new GrpcTransport(channel, "test", 30_000);

        // Upstream deadline = 200ms (tight).
        Context ctxWithDeadline = Context.current()
                .withDeadline(Deadline.after(200, TimeUnit.MILLISECONDS),
                        Executors.newSingleThreadScheduledExecutor());

        long start = System.nanoTime();
        Context prev = ctxWithDeadline.attach();
        try {
            assertThatThrownBy(() -> transport.check(CheckRequest.of(
                    ResourceRef.of("doc", "1"),
                    Permission.of("view"),
                    SubjectRef.of("user", "alice", null),
                    Consistency.minimizeLatency())))
                    .isInstanceOf(AuthxTimeoutException.class);
        } finally {
            ctxWithDeadline.detach(prev);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        // Must fail at ~200ms (upstream), not ~30s (policy). Give ample slack
        // for scheduler jitter on slower CI.
        assertThat(elapsedMs)
                .as("deadline must fire at upstream 200ms, not policy 30s")
                .isLessThan(2_000);

        transport.close();
        channel.shutdownNow();
        server.shutdownNow();
    }
}
