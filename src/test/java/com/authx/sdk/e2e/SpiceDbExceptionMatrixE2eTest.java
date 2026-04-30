package com.authx.sdk.e2e;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.exception.AuthxException;
import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.LookupResourcesRequest;
import com.authx.sdk.model.LookupSubjectsRequest;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.GrpcTransport;
import com.authx.sdk.transport.SdkTransport;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Broad real-SpiceDB exception matrix across SDK operation families.
 */
@Tag("spicedb-e2e")
class SpiceDbExceptionMatrixE2eTest {

    private static SpiceDbTestServer server;
    private static io.grpc.ManagedChannel channel;
    private static GrpcTransport transport;
    private static AuthxClient client;

    @BeforeAll
    static void setup() {
        server = SpiceDbTestServer.start();
        channel = io.grpc.ManagedChannelBuilder.forTarget(server.target())
                .usePlaintext()
                .build();
        transport = new GrpcTransport(channel, SpiceDbTestServer.PRESHARED_KEY, 5000);
        client = AuthxClient.builder()
                .connection(c -> c.target(server.target()).presharedKey(SpiceDbTestServer.PRESHARED_KEY))
                .build();
    }

    @AfterAll
    static void teardown() {
        if (client != null) client.close();
        if (transport != null) transport.close();
        if (channel != null) channel.shutdownNow();
        if (server != null) server.close();
    }

    static Stream<Arguments> spicedbExceptionCases() {
        return Stream.of(
                Arguments.of("check unknown resource type", (ThrowingCallable) () ->
                        transport.check(CheckRequest.of("unknown_resource", "d1", "view",
                                "user", "alice", Consistency.full()))),
                Arguments.of("check unknown permission", (ThrowingCallable) () ->
                        transport.check(CheckRequest.of("document", "d1", "unknown_permission",
                                "user", "alice", Consistency.full()))),
                Arguments.of("check unknown subject type", (ThrowingCallable) () ->
                        transport.check(CheckRequest.of("document", "d1", "view",
                                "unknown_subject", "alice", Consistency.full()))),

                Arguments.of("bulk check unknown resource type", (ThrowingCallable) () ->
                        transport.checkBulk(
                                CheckRequest.of("unknown_resource", "d1", "view",
                                        "user", "alice", Consistency.full()),
                                List.of(SubjectRef.of("user", "alice")))),
                Arguments.of("bulk check unknown permission", (ThrowingCallable) () ->
                        transport.checkBulk(
                                CheckRequest.of("document", "d1", "unknown_permission",
                                        "user", "alice", Consistency.full()),
                                List.of(SubjectRef.of("user", "alice")))),
                Arguments.of("bulk check unknown subject type", (ThrowingCallable) () ->
                        transport.checkBulk(
                                CheckRequest.of("document", "d1", "view",
                                        "unknown_subject", "alice", Consistency.full()),
                                List.of(SubjectRef.of("unknown_subject", "alice")))),

                Arguments.of("write unknown resource type", (ThrowingCallable) () ->
                        transport.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                                ResourceRef.of("unknown_resource", "d1"),
                                Relation.of("viewer"),
                                SubjectRef.of("user", "alice"))))),
                Arguments.of("write unknown relation", (ThrowingCallable) () ->
                        transport.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                                ResourceRef.of("document", "d1"),
                                Relation.of("unknown_relation"),
                                SubjectRef.of("user", "alice"))))),
                Arguments.of("write unknown subject type", (ThrowingCallable) () ->
                        transport.writeRelationships(List.of(new SdkTransport.RelationshipUpdate(
                                SdkTransport.RelationshipUpdate.Operation.TOUCH,
                                ResourceRef.of("document", "d1"),
                                Relation.of("viewer"),
                                SubjectRef.of("unknown_subject", "alice"))))),

                Arguments.of("delete unknown resource type", (ThrowingCallable) () ->
                        transport.deleteRelationships(List.of(new SdkTransport.RelationshipUpdate(
                                SdkTransport.RelationshipUpdate.Operation.DELETE,
                                ResourceRef.of("unknown_resource", "d1"),
                                Relation.of("viewer"),
                                SubjectRef.of("user", "alice"))))),
                Arguments.of("delete unknown relation", (ThrowingCallable) () ->
                        transport.deleteRelationships(List.of(new SdkTransport.RelationshipUpdate(
                                SdkTransport.RelationshipUpdate.Operation.DELETE,
                                ResourceRef.of("document", "d1"),
                                Relation.of("unknown_relation"),
                                SubjectRef.of("user", "alice"))))),
                Arguments.of("delete unknown subject type", (ThrowingCallable) () ->
                        transport.deleteRelationships(List.of(new SdkTransport.RelationshipUpdate(
                                SdkTransport.RelationshipUpdate.Operation.DELETE,
                                ResourceRef.of("document", "d1"),
                                Relation.of("viewer"),
                                SubjectRef.of("unknown_subject", "alice"))))),

                Arguments.of("read unknown resource type", (ThrowingCallable) () ->
                        transport.readRelationships(
                                ResourceRef.of("unknown_resource", "d1"),
                                Relation.of("viewer"),
                                Consistency.full())),
                Arguments.of("read unknown relation", (ThrowingCallable) () ->
                        transport.readRelationships(
                                ResourceRef.of("document", "d1"),
                                Relation.of("unknown_relation"),
                                Consistency.full())),

                Arguments.of("lookupSubjects unknown resource type", (ThrowingCallable) () ->
                        transport.lookupSubjects(new LookupSubjectsRequest(
                                ResourceRef.of("unknown_resource", "d1"),
                                Permission.of("view"),
                                "user",
                                0,
                                Consistency.full()))),
                Arguments.of("lookupSubjects unknown permission", (ThrowingCallable) () ->
                        transport.lookupSubjects(new LookupSubjectsRequest(
                                ResourceRef.of("document", "d1"),
                                Permission.of("unknown_permission"),
                                "user",
                                0,
                                Consistency.full()))),
                Arguments.of("lookupSubjects unknown subject type", (ThrowingCallable) () ->
                        transport.lookupSubjects(new LookupSubjectsRequest(
                                ResourceRef.of("document", "d1"),
                                Permission.of("view"),
                                "unknown_subject",
                                0,
                                Consistency.full()))),

                Arguments.of("lookupResources unknown resource type", (ThrowingCallable) () ->
                        transport.lookupResources(new LookupResourcesRequest(
                                "unknown_resource",
                                Permission.of("view"),
                                SubjectRef.of("user", "alice"),
                                0,
                                Consistency.full()))),
                Arguments.of("lookupResources unknown permission", (ThrowingCallable) () ->
                        transport.lookupResources(new LookupResourcesRequest(
                                "document",
                                Permission.of("unknown_permission"),
                                SubjectRef.of("user", "alice"),
                                0,
                                Consistency.full()))),
                Arguments.of("lookupResources unknown subject type", (ThrowingCallable) () ->
                        transport.lookupResources(new LookupResourcesRequest(
                                "document",
                                Permission.of("view"),
                                SubjectRef.of("unknown_subject", "alice"),
                                0,
                                Consistency.full()))),

                Arguments.of("expand unknown resource type", (ThrowingCallable) () ->
                        transport.expand(
                                ResourceRef.of("unknown_resource", "d1"),
                                Permission.of("view"),
                                Consistency.full())),
                Arguments.of("expand unknown permission", (ThrowingCallable) () ->
                        transport.expand(
                                ResourceRef.of("document", "d1"),
                                Permission.of("unknown_permission"),
                                Consistency.full())),

                Arguments.of("deleteByFilter unknown resource type", (ThrowingCallable) () ->
                        transport.deleteByFilter(
                                ResourceRef.of("unknown_resource", "d1"),
                                SubjectRef.of("user", "alice"),
                                Relation.of("viewer"))),
                Arguments.of("deleteByFilter unknown relation", (ThrowingCallable) () ->
                        transport.deleteByFilter(
                                ResourceRef.of("document", "d1"),
                                SubjectRef.of("user", "alice"),
                                Relation.of("unknown_relation"))),
                Arguments.of("deleteByFilter unknown subject type", (ThrowingCallable) () ->
                        transport.deleteByFilter(
                                ResourceRef.of("document", "d1"),
                                SubjectRef.of("unknown_subject", "alice"),
                                Relation.of("viewer")))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("spicedbExceptionCases")
    void spicedbOperationFailuresMapToSdkExceptions(String name, ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .as(name)
                .isInstanceOf(AuthxException.class);
    }

    static Stream<Arguments> failFastCases() {
        return Stream.of(
                Arguments.of("empty resource id in select", (ThrowingCallable) () ->
                        client.on("document").select("").grant("viewer").to("user:alice").commit()),
                Arguments.of("empty relation in grant", (ThrowingCallable) () ->
                        client.on("document").select("ff-doc-1").grant("").to("user:alice").commit()),
                Arguments.of("empty relation in revoke", (ThrowingCallable) () ->
                        client.on("document").select("ff-doc-1").revoke("").from("user:alice").commit()),
                Arguments.of("empty subject in to", (ThrowingCallable) () ->
                        client.on("document").select("ff-doc-1").grant("viewer").to("").commit()),
                Arguments.of("malformed subject in to", (ThrowingCallable) () ->
                        client.on("document").select("ff-doc-1").grant("viewer").to("alice").commit()),
                Arguments.of("empty subject in check", (ThrowingCallable) () ->
                        client.on("document").select("ff-doc-1").check("view").by("")),
                Arguments.of("malformed subject in check", (ThrowingCallable) () ->
                        client.on("document").select("ff-doc-1").check("view").by("alice")),
                Arguments.of("negative lookupSubjects limit", (ThrowingCallable) () ->
                        client.on("document").select("ff-doc-1").lookupSubjects("user", "view").limit(-1)),
                Arguments.of("negative lookupResources limit", (ThrowingCallable) () ->
                        client.on("document").lookupResources("user:alice").limit(-1)),
                Arguments.of("empty batch commit", (ThrowingCallable) () ->
                        client.batch().commit()),
                Arguments.of("empty batch resource id", (ThrowingCallable) () ->
                        client.batch().on("document", "").grant("viewer").to("user:alice").commit()),
                Arguments.of("empty batch relation", (ThrowingCallable) () ->
                        client.batch().on("document", "ff-doc-1").grant("").to("user:alice").commit()),
                Arguments.of("empty batch subject", (ThrowingCallable) () ->
                        client.batch().on("document", "ff-doc-1").grant("viewer").to("").commit()),
                Arguments.of("unsupported caveat context", (ThrowingCallable) () ->
                        transport.check(new CheckRequest(
                                ResourceRef.of("document", "ff-doc-1"),
                                Permission.of("view"),
                                SubjectRef.of("user", "alice"),
                                Consistency.full(),
                                Map.of("unsupported", new Object()))))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failFastCases")
    void malformedSdkInputsFailFastBeforeSuccessfulRpc(String name, ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .as(name)
                .isInstanceOf(RuntimeException.class);
    }
}
