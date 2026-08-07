package com.authx.sdk.transport;

import com.authx.sdk.model.CaveatRef;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authzed.api.v1.PermissionsServiceGrpc;
import com.authzed.api.v1.WriteRelationshipsRequest;
import com.authzed.api.v1.WriteRelationshipsResponse;
import com.authzed.api.v1.ZedToken;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcTransportRelationshipWriteTest {

    @Test
    void oneWriteBatchPreservesCaveatAndExpirationInTheGrpcRequest() throws Exception {
        AtomicReference<WriteRelationshipsRequest> received = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(new PermissionsServiceGrpc.PermissionsServiceImplBase() {
                    @Override
                    public void writeRelationships(
                            WriteRelationshipsRequest request,
                            StreamObserver<WriteRelationshipsResponse> responseObserver) {
                        calls.incrementAndGet();
                        received.set(request);
                        responseObserver.onNext(WriteRelationshipsResponse.newBuilder()
                                .setWrittenAt(ZedToken.newBuilder().setToken("zed-token"))
                                .build());
                        responseObserver.onCompleted();
                    }
                })
                .build()
                .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

        try {
            GrpcTransport transport = new GrpcTransport(channel, "test-key", 5_000);
            Instant expiresAt = Instant.parse("2026-10-01T00:00:00.123456789Z");
            SdkTransport.RelationshipUpdate conditional = new SdkTransport.RelationshipUpdate(
                    SdkTransport.RelationshipUpdate.Operation.TOUCH,
                    ResourceRef.of("document", "doc-1"),
                    Relation.of("viewer"),
                    SubjectRef.of("user", "alice", null),
                    new CaveatRef("during_shift", Map.of("shift", "day")),
                    expiresAt);
            SdkTransport.RelationshipUpdate ordinary = new SdkTransport.RelationshipUpdate(
                    SdkTransport.RelationshipUpdate.Operation.TOUCH,
                    ResourceRef.of("document", "doc-2"),
                    Relation.of("viewer"),
                    SubjectRef.of("user", "bob", null));

            transport.writeRelationships(List.of(conditional, ordinary));

            assertThat(calls).hasValue(1);
            assertThat(received.get().getUpdatesCount()).isEqualTo(2);
            com.authzed.api.v1.Relationship relationship =
                    received.get().getUpdates(0).getRelationship();
            assertThat(relationship.hasOptionalCaveat()).isTrue();
            assertThat(relationship.getOptionalCaveat().getCaveatName()).isEqualTo("during_shift");
            assertThat(relationship.getOptionalCaveat().getContext()
                    .getFieldsOrThrow("shift").getStringValue()).isEqualTo("day");
            assertThat(relationship.hasOptionalExpiresAt()).isTrue();
            assertThat(relationship.getOptionalExpiresAt().getSeconds())
                    .isEqualTo(expiresAt.getEpochSecond());
            assertThat(relationship.getOptionalExpiresAt().getNanos()).isEqualTo(expiresAt.getNano());
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }
}
