package com.authx.sdk.e2e;

import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Large real-SpiceDB data correctness matrix.
 *
 * <p>Each invocation writes deterministic data into a real SpiceDB container
 * and verifies that independent SDK read paths agree about the same facts.
 */
@Tag("spicedb-e2e")
class SpiceDbDataCorrectnessE2eTest {

    private static final int CASES = 200;

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

    static Stream<Arguments> relationshipCases() {
        return IntStream.range(0, CASES)
                .mapToObj(i -> Arguments.of(
                        i,
                        "data-doc-" + i,
                        relationFor(i),
                        "data-user-" + i,
                        "data-missing-user-" + i));
    }

    @ParameterizedTest(name = "[{0}] real SpiceDB data stays consistent for {2}")
    @MethodSource("relationshipCases")
    void writtenRelationshipIsConsistentAcrossReadPaths(
            int index,
            String documentId,
            String relation,
            String userId,
            String missingUserId) {
        SubjectRef subject = SubjectRef.of("user", userId, null);
        ResourceRef resource = ResourceRef.of("document", documentId);
        Permission directPermission = Permission.of(relation);

        WriteResult write = transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        resource,
                        Relation.of(relation),
                        subject)));
        assertNotNull(write.zedToken(), "write token must be present for case " + index);
        assertEquals(1, write.updateCount());

        CheckResult directCheck = transport.check(new CheckRequest(
                resource,
                directPermission,
                subject,
                Consistency.full(),
                null));
        assertTrue(directCheck.hasPermission(), "direct relation check should allow case " + index);

        CheckResult viewCheck = transport.check(new CheckRequest(
                resource,
                Permission.of("view"),
                subject,
                Consistency.full(),
                null));
        assertTrue(viewCheck.hasPermission(), "view permission should allow case " + index);

        CheckResult missingCheck = transport.check(new CheckRequest(
                resource,
                Permission.of("view"),
                SubjectRef.of("user", missingUserId, null),
                Consistency.full(),
                null));
        assertFalse(missingCheck.hasPermission(), "unwritten subject should be denied for case " + index);

        List<Tuple> tuples = transport.readRelationships(resource, Relation.of(relation), Consistency.full());
        assertTrue(tuples.stream().anyMatch(t ->
                        t.resourceType().equals("document")
                                && t.resourceId().equals(documentId)
                                && t.relation().equals(relation)
                                && t.subjectType().equals("user")
                                && t.subjectId().equals(userId)),
                "readRelationships must include written tuple for case " + index);

        List<SubjectRef> subjects = transport.lookupSubjects(new LookupSubjectsRequest(
                resource,
                directPermission,
                "user",
                0,
                Consistency.full()));
        assertTrue(subjects.stream().anyMatch(s -> s.id().equals(userId)),
                "lookupSubjects must include written subject for case " + index);
        assertFalse(subjects.stream().anyMatch(s -> s.id().equals(missingUserId)),
                "lookupSubjects must not include unwritten subject for case " + index);

        List<ResourceRef> resources = transport.lookupResources(new LookupResourcesRequest(
                "document",
                Permission.of("view"),
                subject,
                0,
                Consistency.full()));
        assertTrue(resources.stream().anyMatch(r -> r.id().equals(documentId)),
                "lookupResources must include written resource for case " + index);
    }

    private static String relationFor(int index) {
        return switch (index % 3) {
            case 0 -> "owner";
            case 1 -> "editor";
            default -> "viewer";
        };
    }
}
