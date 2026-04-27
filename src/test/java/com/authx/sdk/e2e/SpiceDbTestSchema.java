package com.authx.sdk.e2e;

import com.authzed.api.v1.SchemaServiceGrpc;
import com.authzed.api.v1.WriteSchemaRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;

import java.util.concurrent.TimeUnit;

/**
 * Shared helper that writes a test schema to a fresh SpiceDB serve-testing instance.
 * Called from @BeforeAll in each E2E test class.
 */
final class SpiceDbTestSchema {

    private SpiceDbTestSchema() {}

    /**
     * Minimal schema covering resource types used across E2E tests:
     * document (owner, editor, viewer + view permission), folder, group, user.
     */
    static final String SCHEMA = """
            definition user {}

            definition document {
                relation owner: user
                relation editor: user
                relation viewer: user
                permission view = owner + editor + viewer
            }

            definition folder {
                relation owner: user
                relation editor: user
                relation viewer: user
                permission view = owner + editor + viewer
            }

            definition group {
                relation member: user
            }
            """;

    /**
     * Write the test schema to SpiceDB via gRPC.
     *
     * @param target  host:port of the SpiceDB gRPC endpoint
     * @param presharedKey  the preshared key configured on the container
     */
    static void writeSchema(String target, String presharedKey) {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();
        try {
            Metadata metadata = new Metadata();
            metadata.put(
                    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + presharedKey);

            com.authzed.api.v1.SchemaServiceGrpc.SchemaServiceBlockingStub stub = SchemaServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));

            stub.writeSchema(WriteSchemaRequest.newBuilder()
                    .setSchema(SCHEMA)
                    .build());
        } finally {
            channel.shutdown();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }
}
