package com.authx.sdk.e2e;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.GrantResult;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test: SDK → SpiceDB gRPC via Testcontainers.
 * SpiceDB is started automatically in serve-testing mode (in-memory, no persistence).
 * If Docker is unavailable, tests are skipped gracefully.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SdkEndToEndTest {

    static GenericContainer<?> spicedb;
    private static AuthxClient client;

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

        client = AuthxClient.builder()
                .connection(c -> c.target(target).presharedKey("testkey"))
                .build();
    }

    @AfterAll
    static void teardown() {
        if (client != null) client.close();
        if (spicedb != null && spicedb.isRunning()) spicedb.stop();
    }

    @Test
    @Order(1)
    void connect_succeeds() {
        assertNotNull(client);
    }

    @Test
    @Order(2)
    void grant_and_check() {
        var doc = client.resource("document", "e2e-doc-1");

        // Grant editor to alice
        GrantResult grantResult = doc.grant("editor").to("alice");
        assertNotNull(grantResult.zedToken(), "grant should return zedToken");
        assertTrue(grantResult.count() > 0, "grant count should be > 0");

        // Check with full consistency (ensure we read after write)
        CheckResult checkResult = doc.check("editor")
                .withConsistency(com.authx.sdk.model.Consistency.full())
                .by("alice");
        assertTrue(checkResult.hasPermission(),
                "alice should have editor on e2e-doc-1, got: " + checkResult.permissionship());

        // Check: bob should NOT have editor
        CheckResult bobCheck = doc.check("editor")
                .withConsistency(com.authx.sdk.model.Consistency.full())
                .by("bob");
        assertFalse(bobCheck.hasPermission(), "bob should NOT have editor on e2e-doc-1");
    }

    @Test
    @Order(3)
    void who_withRelation() {
        var doc = client.resource("document", "e2e-doc-1");

        Set<String> editors = doc.who().withRelation("editor")
                .withConsistency(com.authx.sdk.model.Consistency.full())
                .fetchSet();
        assertTrue(editors.contains("alice"), "alice should be in editors list");
    }

    @Test
    @Order(4)
    void relations_read() {
        var doc = client.resource("document", "e2e-doc-1");

        var tuples = doc.relations("editor")
                .withConsistency(com.authx.sdk.model.Consistency.full())
                .fetch();
        assertFalse(tuples.isEmpty(), "should have at least one editor relationship");
        assertEquals("alice", tuples.getFirst().subjectId());
    }

    @Test
    @Order(5)
    void lookup_resources() {
        List<String> docs = client.lookup("document").withPermission("editor").by("alice")
                .withConsistency(com.authx.sdk.model.Consistency.full())
                .fetch();
        assertTrue(docs.contains("e2e-doc-1"), "alice should find e2e-doc-1 via lookup");
    }

    @Test
    @Order(6)
    void revoke_and_verify() {
        var doc = client.resource("document", "e2e-doc-1");
        doc.revoke("editor").from("alice");

        CheckResult checkResult = doc.check("editor")
                .withConsistency(com.authx.sdk.model.Consistency.full())
                .by("alice");
        assertFalse(checkResult.hasPermission(), "alice should no longer have editor after revoke");
    }

    @Test
    @Order(7)
    void batch_operations() {
        var doc = client.resource("document", "e2e-doc-batch");

        var result = doc.batch()
                .grant("owner").to("carol")
                .grant("editor").to("dave")
                .execute();

        assertNotNull(result.zedToken());

        assertTrue(doc.check("owner")
                .withConsistency(com.authx.sdk.model.Consistency.full())
                .by("carol").hasPermission());
        assertTrue(doc.check("editor")
                .withConsistency(com.authx.sdk.model.Consistency.full())
                .by("dave").hasPermission());

        // Cleanup
        doc.batch()
                .revoke("owner").from("carol")
                .revoke("editor").from("dave")
                .execute();
    }
}
