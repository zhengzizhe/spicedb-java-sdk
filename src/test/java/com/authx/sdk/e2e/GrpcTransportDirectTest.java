package com.authx.sdk.e2e;

import com.authx.sdk.model.BulkCheckResult;
import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.ExpandTree;
import com.authx.sdk.model.LookupResourcesRequest;
import com.authx.sdk.model.LookupSubjectsRequest;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.Tuple;
import com.authx.sdk.model.WriteResult;
import com.authx.sdk.transport.GrpcTransport;
import com.authx.sdk.transport.SdkTransport;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("spicedb-e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GrpcTransportDirectTest {

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
    @Order(1)
    void writeAndCheck() {
        WriteResult write = transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "grpc-test-1"),
                        Relation.of("editor"),
                        SubjectRef.of("user", "grpc-alice", null))));
        assertNotNull(write.zedToken());

        CheckResult check = transport.check(
                CheckRequest.of("document", "grpc-test-1", "editor",
                        "user", "grpc-alice", Consistency.full()));
        assertTrue(check.hasPermission());
    }

    @Test
    @Order(2)
    void readRelationships() {
        List<Tuple> tuples = transport.readRelationships(
                ResourceRef.of("document", "grpc-test-1"), Relation.of("editor"), Consistency.full());
        assertFalse(tuples.isEmpty());
        assertEquals("grpc-alice", tuples.getFirst().subjectId());
    }

    @Test
    @Order(3)
    void lookupSubjects() {
        LookupSubjectsRequest request = new LookupSubjectsRequest(
                ResourceRef.of("document", "grpc-test-1"),
                Permission.of("editor"), "user", 0, Consistency.full());
        List<SubjectRef> subjects = transport.lookupSubjects(request);
        assertTrue(subjects.stream().anyMatch(s -> s.id().equals("grpc-alice")));
    }

    @Test
    @Order(4)
    void checkBulk() {
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "grpc-bulk-1"),
                        Relation.of("viewer"),
                        SubjectRef.of("user", "grpc-bulk-alice", null)),
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "grpc-bulk-1"),
                        Relation.of("editor"),
                        SubjectRef.of("user", "grpc-bulk-bob", null))));

        BulkCheckResult result = transport.checkBulk(
                CheckRequest.of("document", "grpc-bulk-1", "view",
                        "user", "grpc-bulk-alice", Consistency.full()),
                List.of(
                        SubjectRef.of("user", "grpc-bulk-alice", null),
                        SubjectRef.of("user", "grpc-bulk-bob", null),
                        SubjectRef.of("user", "grpc-bulk-carol", null)));

        assertTrue(result.get("user:grpc-bulk-alice").hasPermission());
        assertTrue(result.get("user:grpc-bulk-bob").hasPermission());
        assertFalse(result.get("user:grpc-bulk-carol").hasPermission());
        assertEquals(2, result.allowedCount());
    }

    @Test
    @Order(5)
    void lookupResources() {
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "grpc-lookup-a"),
                        Relation.of("viewer"),
                        SubjectRef.of("user", "grpc-lookup-alice", null)),
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "grpc-lookup-b"),
                        Relation.of("editor"),
                        SubjectRef.of("user", "grpc-lookup-alice", null)),
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "grpc-lookup-c"),
                        Relation.of("owner"),
                        SubjectRef.of("user", "grpc-lookup-bob", null))));

        LookupResourcesRequest request = new LookupResourcesRequest(
                "document",
                Permission.of("view"),
                SubjectRef.of("user", "grpc-lookup-alice", null),
                0,
                Consistency.full());
        List<ResourceRef> resources = transport.lookupResources(request);

        assertTrue(resources.stream().anyMatch(r -> r.id().equals("grpc-lookup-a")));
        assertTrue(resources.stream().anyMatch(r -> r.id().equals("grpc-lookup-b")));
        assertFalse(resources.stream().anyMatch(r -> r.id().equals("grpc-lookup-c")));
    }

    @Test
    @Order(6)
    void deleteByFilter() {
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "grpc-filter-1"),
                        Relation.of("viewer"),
                        SubjectRef.of("user", "grpc-filter-alice", null)),
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "grpc-filter-1"),
                        Relation.of("editor"),
                        SubjectRef.of("user", "grpc-filter-alice", null))));

        WriteResult result = transport.deleteByFilter(
                ResourceRef.of("document", "grpc-filter-1"),
                SubjectRef.of("user", "grpc-filter-alice", null),
                Relation.of("viewer"));

        assertNotNull(result.zedToken());
        assertFalse(result.submittedUpdateCountKnown());
        assertEquals(WriteResult.UNKNOWN_SUBMITTED_UPDATE_COUNT, result.submittedUpdateCount());
        assertFalse(transport.check(CheckRequest.of("document", "grpc-filter-1", "viewer",
                "user", "grpc-filter-alice", Consistency.full())).hasPermission());
        assertTrue(transport.check(CheckRequest.of("document", "grpc-filter-1", "editor",
                "user", "grpc-filter-alice", Consistency.full())).hasPermission());
    }

    @Test
    @Order(7)
    void expand() {
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "grpc-expand-1"),
                        Relation.of("owner"),
                        SubjectRef.of("user", "grpc-expand-alice", null)),
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "grpc-expand-1"),
                        Relation.of("viewer"),
                        SubjectRef.of("user", "grpc-expand-bob", null))));

        ExpandTree tree = transport.expand(
                ResourceRef.of("document", "grpc-expand-1"),
                Permission.of("view"),
                Consistency.full());

        assertEquals("document", tree.resourceType());
        assertEquals("grpc-expand-1", tree.resourceId());
        assertTrue(tree.contains("user:grpc-expand-alice"));
        assertTrue(tree.contains("user:grpc-expand-bob"));
    }

    @Test
    @Order(8)
    void deleteAndVerify() {
        transport.deleteRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.DELETE,
                        ResourceRef.of("document", "grpc-test-1"),
                        Relation.of("editor"),
                        SubjectRef.of("user", "grpc-alice", null))));

        CheckResult check = transport.check(
                CheckRequest.of("document", "grpc-test-1", "editor",
                        "user", "grpc-alice", Consistency.full()));
        assertFalse(check.hasPermission());
    }
}
