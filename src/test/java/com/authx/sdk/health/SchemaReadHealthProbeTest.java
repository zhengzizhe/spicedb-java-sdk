package com.authx.sdk.health;

import com.authzed.api.v1.ReadSchemaRequest;
import com.authzed.api.v1.ReadSchemaResponse;
import com.authzed.api.v1.SchemaServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SchemaReadHealthProbe}. Uses in-process gRPC to mock SpiceDB
 * responses, covering the four behaviours:
 *
 * <ul>
 *   <li>Schema present → healthy</li>
 *   <li>Schema missing (NOT_FOUND) → healthy (SpiceDB is responding correctly)</li>
 *   <li>Server returns other gRPC errors → unhealthy</li>
 *   <li>Server unreachable / closed channel → unhealthy</li>
 * </ul>
 */
class SchemaReadHealthProbeTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    private void startServer(SchemaServiceGrpc.SchemaServiceImplBase impl) throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @Test
    void readSchema_ok_reportsUp() throws Exception {
        startServer(new SchemaServiceGrpc.SchemaServiceImplBase() {
            @Override
            public void readSchema(ReadSchemaRequest request, StreamObserver<ReadSchemaResponse> observer) {
                observer.onNext(ReadSchemaResponse.newBuilder()
                        .setSchemaText("definition user {}")
                        .build());
                observer.onCompleted();
            }
        });

        var probe = new SchemaReadHealthProbe(channel, "test-key");
        var result = probe.check();

        assertThat(result.healthy()).isTrue();
        assertThat(result.name()).isEqualTo("spicedb-schema");
        assertThat(result.details()).isEqualTo("schema present");
    }

    @Test
    void readSchema_notFound_reportsUpWithDifferentDetails() throws Exception {
        startServer(new SchemaServiceGrpc.SchemaServiceImplBase() {
            @Override
            public void readSchema(ReadSchemaRequest request, StreamObserver<ReadSchemaResponse> observer) {
                observer.onError(Status.NOT_FOUND
                        .withDescription("no schema has been defined")
                        .asRuntimeException());
            }
        });

        var probe = new SchemaReadHealthProbe(channel, "test-key");
        var result = probe.check();

        // Key assertion: NOT_FOUND is mapped to HEALTHY
        assertThat(result.healthy()).isTrue();
        assertThat(result.details()).contains("no schema written");
    }

    @Test
    void readSchema_unauthenticated_reportsDown() throws Exception {
        startServer(new SchemaServiceGrpc.SchemaServiceImplBase() {
            @Override
            public void readSchema(ReadSchemaRequest request, StreamObserver<ReadSchemaResponse> observer) {
                observer.onError(Status.UNAUTHENTICATED
                        .withDescription("invalid bearer token")
                        .asRuntimeException());
            }
        });

        var probe = new SchemaReadHealthProbe(channel, "bad-key");
        var result = probe.check();

        assertThat(result.healthy()).isFalse();
        assertThat(result.details()).contains("UNAUTHENTICATED");
    }

    @Test
    void readSchema_unavailable_reportsDown() throws Exception {
        startServer(new SchemaServiceGrpc.SchemaServiceImplBase() {
            @Override
            public void readSchema(ReadSchemaRequest request, StreamObserver<ReadSchemaResponse> observer) {
                observer.onError(Status.UNAVAILABLE
                        .withDescription("service temporarily down")
                        .asRuntimeException());
            }
        });

        var probe = new SchemaReadHealthProbe(channel, "test-key");
        var result = probe.check();

        assertThat(result.healthy()).isFalse();
        assertThat(result.details()).contains("UNAVAILABLE");
    }

    @Test
    void readSchema_timeout_reportsDown() throws Exception {
        startServer(new SchemaServiceGrpc.SchemaServiceImplBase() {
            @Override
            public void readSchema(ReadSchemaRequest request, StreamObserver<ReadSchemaResponse> observer) {
                // Never respond — force the deadline to fire.
            }
        });

        var probe = new SchemaReadHealthProbe(channel, "test-key", Duration.ofMillis(50));
        var result = probe.check();

        assertThat(result.healthy()).isFalse();
        assertThat(result.details()).contains("DEADLINE_EXCEEDED");
    }
}
