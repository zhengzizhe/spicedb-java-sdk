package com.authx.sdk.transport;

import com.authx.sdk.exception.AuthxProtocolException;
import com.authx.sdk.model.*;
import com.authzed.api.v1.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BulkProtocolRegressionTest {
    @Test void sendsContextAndRejectsUnboundOrIncompleteResponses() throws Exception {
        AtomicReference<CheckBulkPermissionsRequest> received = new AtomicReference<>();
        AtomicReference<String> mode = new AtomicReference<>("valid");
        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name).directExecutor().addService(new PermissionsServiceGrpc.PermissionsServiceImplBase() {
            @Override public void checkBulkPermissions(CheckBulkPermissionsRequest request, StreamObserver<CheckBulkPermissionsResponse> observer) {
                received.set(request);
                CheckBulkPermissionsResponse.Builder result = CheckBulkPermissionsResponse.newBuilder()
                        .setCheckedAt(ZedToken.newBuilder().setToken("t"));
                if (!mode.get().equals("missing")) {
                    CheckBulkPermissionsRequestItem item = request.getItems(0);
                    if (mode.get().equals("wrong")) item = item.toBuilder().setPermission("other").build();
                    result.addPairs(CheckBulkPermissionsPair.newBuilder().setRequest(item)
                            .setItem(CheckBulkPermissionsResponseItem.newBuilder()
                                    .setPermissionship(CheckPermissionResponse.Permissionship.PERMISSIONSHIP_HAS_PERMISSION)));
                }
                observer.onNext(result.build()); observer.onCompleted();
            }
        }).build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            GrpcTransport transport = new GrpcTransport(channel, "test-key", 1000);
            com.authx.sdk.model.CheckRequest request = AuthorizationConsistencyRegressionTest.request(
                    com.authx.sdk.model.Consistency.full(), Map.of("requested_permission", "example.read"));
            assertThat(transport.checkBulk(request, List.of(request.subject())).get(request.subject().toRefString()).hasPermission()).isTrue();
            assertThat(received.get().getItems(0).getContext().getFieldsOrThrow("requested_permission").getStringValue()).isEqualTo("example.read");
            transport.checkBulkMulti(List.of(new SdkTransport.BulkCheckItem(request.resource(), request.permission(), request.subject(), request.caveatContext())), request.consistency());
            assertThat(received.get().getItems(0).getContext().getFieldsCount()).isEqualTo(1);
            com.authx.sdk.model.CheckRequest emptyContext = new com.authx.sdk.model.CheckRequest(
                    request.resource(), request.permission(), request.subject(), request.consistency(), Map.of());
            transport.checkBulk(emptyContext, List.of(request.subject()));
            assertThat(received.get().getItems(0).hasContext()).isFalse();
            transport.checkBulkMulti(List.of(new SdkTransport.BulkCheckItem(request.resource(), request.permission(), request.subject(), Map.of())), request.consistency());
            assertThat(received.get().getItems(0).hasContext()).isFalse();
            for (String invalid : List.of("wrong", "missing")) {
                mode.set(invalid);
                assertThatThrownBy(() -> transport.checkBulk(request, List.of(request.subject())))
                        .isInstanceOf(AuthxProtocolException.class);
            }
        } finally { channel.shutdownNow(); server.shutdownNow(); }
    }
}
