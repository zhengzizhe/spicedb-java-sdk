package com.authx.sdk.transport;

import com.authx.sdk.cache.SchemaCache;
import com.authzed.api.v1.ExpCaveat;
import com.authzed.api.v1.ExpCaveatParameter;
import com.authzed.api.v1.ExpDefinition;
import com.authzed.api.v1.ExpPermission;
import com.authzed.api.v1.ExpRelation;
import com.authzed.api.v1.ExpTypeReference;
import com.authzed.api.v1.ExperimentalReflectSchemaRequest;
import com.authzed.api.v1.ExperimentalReflectSchemaResponse;
import com.authzed.api.v1.ExperimentalServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaLoaderTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void cleanup() throws Exception {
        if (channel != null) channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }

    private void startServer(ExperimentalServiceGrpc.ExperimentalServiceImplBase impl) throws Exception {
        String name = "schema-loader-" + System.nanoTime();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @Test
    void loadsDefinitionsAndCaveats() throws Exception {
        startServer(new ExperimentalServiceGrpc.ExperimentalServiceImplBase() {
            @Override
            public void experimentalReflectSchema(ExperimentalReflectSchemaRequest req,
                                                  StreamObserver<ExperimentalReflectSchemaResponse> obs) {
                obs.onNext(ExperimentalReflectSchemaResponse.newBuilder()
                        .addDefinitions(ExpDefinition.newBuilder()
                                .setName("document")
                                .addRelations(ExpRelation.newBuilder()
                                        .setName("folder")
                                        .addSubjectTypes(ExpTypeReference.newBuilder()
                                                .setSubjectDefinitionName("folder")))
                                .addRelations(ExpRelation.newBuilder()
                                        .setName("viewer")
                                        .addSubjectTypes(ExpTypeReference.newBuilder()
                                                .setSubjectDefinitionName("user"))
                                        .addSubjectTypes(ExpTypeReference.newBuilder()
                                                .setSubjectDefinitionName("user")
                                                .setIsPublicWildcard(true)))
                                .addPermissions(ExpPermission.newBuilder().setName("view")))
                        .addCaveats(ExpCaveat.newBuilder()
                                .setName("ip_allowlist")
                                .setExpression("client_ip in cidrs")
                                .addParameters(ExpCaveatParameter.newBuilder()
                                        .setName("cidrs")
                                        .setType("list<string>")))
                        .build());
                obs.onCompleted();
            }
        });

        var cache = new SchemaCache();
        boolean ok = new SchemaLoader().load(channel, new Metadata(), cache);
        assertThat(ok).isTrue();
        assertThat(cache.hasSchema()).isTrue();
        assertThat(cache.getResourceTypes()).containsExactly("document");
        assertThat(cache.getRelations("document")).containsExactlyInAnyOrder("folder", "viewer");
        assertThat(cache.getPermissions("document")).containsExactly("view");
        assertThat(cache.getSubjectTypes("document", "viewer")).hasSize(2);
        assertThat(cache.getCaveatNames()).containsExactly("ip_allowlist");
        assertThat(cache.getCaveat("ip_allowlist").parameters())
                .containsEntry("cidrs", "list<string>");
    }

    @Test
    void unimplementedIsNonFatal() throws Exception {
        startServer(new ExperimentalServiceGrpc.ExperimentalServiceImplBase() {
            @Override
            public void experimentalReflectSchema(ExperimentalReflectSchemaRequest req,
                                                  StreamObserver<ExperimentalReflectSchemaResponse> obs) {
                obs.onError(Status.UNIMPLEMENTED.asRuntimeException());
            }
        });

        var cache = new SchemaCache();
        var loader = new SchemaLoader();
        assertThat(loader.load(channel, new Metadata(), cache)).isFalse();
        // Second attempt short-circuits (reflectSupported = false).
        assertThat(loader.load(channel, new Metadata(), cache)).isFalse();
        assertThat(cache.hasSchema()).isFalse();
    }
}
