package com.authx.sdk.e2e;

import com.authzed.api.v1.SchemaServiceGrpc;
import com.authzed.api.v1.WriteSchemaRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.TimeUnit;

/**
 * Shared helper that writes a test schema to a fresh SpiceDB instance.
 */
final class SpiceDbTestSchema {

    private SpiceDbTestSchema() {}

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

    static void writeSchema(String target, String presharedKey) {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();
        try {
            Metadata metadata = new Metadata();
            metadata.put(
                    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + presharedKey);

            SchemaServiceGrpc.SchemaServiceBlockingStub stub = SchemaServiceGrpc.newBlockingStub(channel)
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
