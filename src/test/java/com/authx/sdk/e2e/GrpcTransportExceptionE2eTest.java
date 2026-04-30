package com.authx.sdk.e2e;

import com.authx.sdk.exception.AuthxAuthException;
import com.authx.sdk.exception.AuthxConnectionException;
import com.authx.sdk.exception.AuthxPreconditionException;
import com.authx.sdk.exception.AuthxTimeoutException;
import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.GrpcTransport;
import com.authx.sdk.transport.SdkTransport;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("spicedb-e2e")
class GrpcTransportExceptionE2eTest {

    private static SpiceDbTestServer server;
    private static io.grpc.ManagedChannel channel;
    private static GrpcTransport transport;

    @BeforeAll
    static void setup() {
        server = SpiceDbTestServer.start();
        channel = io.grpc.ManagedChannelBuilder.forTarget(server.target())
                .usePlaintext()
                .build();
        transport = new GrpcTransport(channel, SpiceDbTestServer.PRESHARED_KEY, 5000);
    }

    @AfterAll
    static void teardown() {
        if (transport != null) transport.close();
        if (channel != null) channel.shutdownNow();
        if (server != null) server.close();
    }

    @Test
    void wrongPresharedKeyMapsToAuthException() {
        io.grpc.ManagedChannel wrongKeyChannel = io.grpc.ManagedChannelBuilder.forTarget(server.target())
                .usePlaintext()
                .build();
        try {
            GrpcTransport wrongKeyTransport = new GrpcTransport(wrongKeyChannel, "wrong-key", 1000);

            assertThatThrownBy(() -> wrongKeyTransport.check(
                    CheckRequest.of("document", "auth-failure", "view",
                            "user", "alice", Consistency.full())))
                    .isInstanceOf(AuthxAuthException.class);
        } finally {
            wrongKeyChannel.shutdownNow();
        }
    }

    @Test
    void unavailableTargetMapsToConnectionException() {
        io.grpc.ManagedChannel unavailableChannel = io.grpc.ManagedChannelBuilder
                .forTarget("127.0.0.1:1")
                .usePlaintext()
                .build();
        try {
            GrpcTransport unavailableTransport = new GrpcTransport(
                    unavailableChannel, SpiceDbTestServer.PRESHARED_KEY, 500);

            assertThatThrownBy(() -> unavailableTransport.check(
                    CheckRequest.of("document", "unavailable", "view",
                            "user", "alice", Consistency.full())))
                    .isInstanceOf(AuthxConnectionException.class);
        } finally {
            unavailableChannel.shutdownNow();
        }
    }

    @Test
    void expiredDeadlineMapsToTimeoutException() {
        GrpcTransport timeoutTransport = new GrpcTransport(
                channel, SpiceDbTestServer.PRESHARED_KEY, 0);

        assertThatThrownBy(() -> timeoutTransport.check(
                CheckRequest.of("document", "deadline", "view",
                        "user", "alice", Consistency.full())))
                .isInstanceOf(AuthxTimeoutException.class);
    }

    @Test
    void unknownPermissionMapsToPreconditionException() {
        assertThatThrownBy(() -> transport.check(
                CheckRequest.of("document", "bad-permission", "does_not_exist",
                        "user", "alice", Consistency.full())))
                .isInstanceOf(AuthxPreconditionException.class)
                .hasMessageContaining("does_not_exist");
    }

    @Test
    void unknownRelationWriteMapsToPreconditionException() {
        assertThatThrownBy(() -> transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "bad-relation"),
                        Relation.of("does_not_exist"),
                        SubjectRef.of("user", "alice", null)))))
                .isInstanceOf(AuthxPreconditionException.class)
                .hasMessageContaining("does_not_exist");
    }

    @Test
    void bulkItemErrorMapsToSdkExceptionInsteadOfDeniedResult() {
        assertThatThrownBy(() -> transport.checkBulk(
                CheckRequest.of("document", "bulk-bad", "does_not_exist",
                        "user", "alice", Consistency.full()),
                List.of(SubjectRef.of("user", "alice", null))))
                .isInstanceOf(AuthxPreconditionException.class)
                .hasMessageContaining("does_not_exist");
    }
}
