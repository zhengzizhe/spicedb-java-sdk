package com.authx.sdk.transport;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.RelationshipFilter;
import com.authx.sdk.model.RelationshipPrecondition;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.WriteRequest;
import com.authx.sdk.spi.CoordinatedTokenStore;
import com.authx.sdk.spi.SdkComponents;
import com.authzed.api.v1.PermissionsServiceGrpc;
import com.authzed.api.v1.WriteRelationshipsRequest;
import com.authzed.api.v1.WriteRelationshipsResponse;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AtomicMutationRetryRegressionTest {
    @Test void uncertainConditionalWritesAreNeverReplayed() throws Exception { assertNotReplayed(false, true); }
    @Test void uncertainCoordinatedWritesAreNeverReplayed() throws Exception { assertNotReplayed(true, false); }
    private void assertNotReplayed(boolean coordinated, boolean conditional) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Server server = ServerBuilder.forPort(0).addService(new PermissionsServiceGrpc.PermissionsServiceImplBase() {
            @Override public void writeRelationships(WriteRelationshipsRequest request, StreamObserver<WriteRelationshipsResponse> observer) {
                int count = calls.incrementAndGet();
                // The first attempt may have committed despite a missing acknowledgment.
                observer.onError((count == 1 ? Status.UNAVAILABLE : Status.FAILED_PRECONDITION).asRuntimeException());
            }
        }).build().start();
        CoordinatedTokenStore store = new CoordinatedTokenStore() {
            @Override public com.authx.sdk.model.WriteResult executeWrite(java.util.function.Supplier<com.authx.sdk.model.WriteResult> mutation) { return mutation.get(); }
            @Override public String readRequiredToken() { return "required"; }
        };
        try (AuthxClient client = AuthxClient.builder().loadSchemaOnStart(false)
                .connection(connection -> connection.target("127.0.0.1:" + server.getPort()).presharedKey("test"))
                .extend(extension -> extension.components(coordinated ? SdkComponents.builder().coordinatedTokenStore(store).build() : SdkComponents.builder().build())).build()) {
            SdkTransport.RelationshipUpdate update = new SdkTransport.RelationshipUpdate(SdkTransport.RelationshipUpdate.Operation.TOUCH,
                    ResourceRef.of("document", "one"), Relation.of("viewer"), SubjectRef.parse("user:alice"));
            List<RelationshipPrecondition> conditions = conditional ? List.of(new RelationshipPrecondition(
                    RelationshipPrecondition.Operation.MUST_NOT_MATCH,
                    new RelationshipFilter("document", "one", "viewer", "user", null, null))) : List.of();
            assertThatThrownBy(() -> client.write(new WriteRequest(List.of(update), conditions))).isInstanceOf(RuntimeException.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.shutdownNow(); }
    }
}
