package com.authx.sdk.e2e;

import com.authx.sdk.model.*;
import com.authx.sdk.transport.GrpcTransport;
import com.authx.sdk.transport.SdkTransport;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Direct GrpcTransport test — connects straight to SpiceDB via Testcontainers.
 * If Docker is unavailable, tests are skipped gracefully.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GrpcTransportDirectTest {

    static GenericContainer<?> spicedb;
    private static GrpcTransport transport;

    @BeforeAll
    static void setup() {
        try {
            spicedb = new GenericContainer<>("authzed/spicedb:v1.33.0")
                    .withCommand("serve-testing")
                    .withExposedPorts(50051)
                    .waitingFor(Wait.forLogMessage(".*grpc server started serving.*", 1));
            spicedb.start();
        } catch (Exception e) {
            assumeTrue(false, "Docker/Testcontainers unavailable: " + e.getMessage());
        }

        String target = spicedb.getHost() + ":" + spicedb.getMappedPort(50051);

        // Write schema to the empty serve-testing instance
        SpiceDbTestSchema.writeSchema(target, "testkey");

        var channel = io.grpc.ManagedChannelBuilder.forTarget(target)
                .usePlaintext().build();
        transport = new GrpcTransport(channel, "testkey", 5000);
    }

    @AfterAll
    static void teardown() {
        if (transport != null) transport.close();
        if (spicedb != null && spicedb.isRunning()) spicedb.stop();
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
                CheckRequest.of("document", "grpc-test-1", "editor",
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
                CheckRequest.of("document", "grpc-test-1", "editor",
                        "user", "grpc-alice", Consistency.full()));
        assertFalse(cr.hasPermission(), "grpc-alice should no longer have editor");
    }
}
