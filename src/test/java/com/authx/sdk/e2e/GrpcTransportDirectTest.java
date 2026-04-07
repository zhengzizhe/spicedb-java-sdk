package com.authx.sdk.e2e;

import com.authx.sdk.model.*;
import com.authx.sdk.transport.GrpcTransport;
import com.authx.sdk.transport.SdkTransport;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Direct GrpcTransport test — connects straight to SpiceDB.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GrpcTransportDirectTest {

    private static GrpcTransport transport;

    @BeforeAll
    static void setup() {
        try {
            var channel = io.grpc.ManagedChannelBuilder.forTarget("localhost:50051")
                    .usePlaintext().build();
            transport = new GrpcTransport(channel, "dev-token", 5000);
            // Quick smoke test — if SpiceDB is down, this will throw
            transport.readRelationships(ResourceRef.of("document", "__probe__"), null, Consistency.full());
        } catch (Exception e) {
            transport = null;
            assumeTrue(false, "SpiceDB not reachable: " + e.getMessage());
        }
    }

    @AfterAll
    static void teardown() {
        if (transport != null) transport.close();
    }

    @Test
    @Order(1)
    void writeAndCheck() {
        // Write
        GrantResult wr = transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "grpc-test-1"),
                        Relation.of("editor"),
                        SubjectRef.of("user", "grpc-alice", null))));
        assertNotNull(wr.zedToken());

        // Check
        CheckResult cr = transport.check(
                CheckRequest.from("document", "grpc-test-1", "editor",
                        "user", "grpc-alice", Consistency.full()));
        assertTrue(cr.hasPermission(), "grpc-alice should have editor after write");
    }

    @Test
    @Order(2)
    void readRelationships() {
        var tuples = transport.readRelationships(
                ResourceRef.of("document", "grpc-test-1"), Relation.of("editor"), Consistency.full());
        assertFalse(tuples.isEmpty());
        assertEquals("grpc-alice", tuples.getFirst().subjectId());
    }

    @Test
    @Order(3)
    void lookupSubjects() {
        var request = new LookupSubjectsRequest(
                ResourceRef.of("document", "grpc-test-1"),
                Permission.of("editor"), "user", 0, Consistency.full());
        var subjects = transport.lookupSubjects(request);
        assertTrue(subjects.stream().anyMatch(s -> s.id().equals("grpc-alice")));
    }

    @Test
    @Order(4)
    void deleteAndVerify() {
        transport.deleteRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.DELETE,
                        ResourceRef.of("document", "grpc-test-1"),
                        Relation.of("editor"),
                        SubjectRef.of("user", "grpc-alice", null))));

        CheckResult cr = transport.check(
                CheckRequest.from("document", "grpc-test-1", "editor",
                        "user", "grpc-alice", Consistency.full()));
        assertFalse(cr.hasPermission(), "grpc-alice should no longer have editor");
    }
}
