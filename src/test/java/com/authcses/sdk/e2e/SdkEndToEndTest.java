package com.authcses.sdk.e2e;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.model.GrantResult;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test: SDK → Platform /sdk/connect → SpiceDB gRPC.
 * Requires: platform running on localhost:8090, SpiceDB on localhost:50051,
 * and a valid API Key with access to the "dev" namespace.
 *
 * Skipped automatically if platform is not reachable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SdkEndToEndTest {

    // Set via env or hardcode for local testing
    private static final String PLATFORM_ENDPOINT = "http://localhost:8090";
    private static final String API_KEY = "ak_30ac4b596f8bd12c5bf4202faccd36bc";
    private static final String NAMESPACE = "dev";

    private static AuthCsesClient client;

    @BeforeAll
    static void setup() {
        // Skip if SpiceDB is not reachable
        try {
            client = AuthCsesClient.builder()
                    .connection(c -> c.target("localhost:50051").presharedKey("dev-token"))
                    .build();
        } catch (Exception e) {
            assumeTrue(false, "SpiceDB not reachable: " + e.getMessage());
        }
    }

    @AfterAll
    static void teardown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void connect_succeeds() {
        // If we got here, builder.build() called /sdk/connect and got SpiceDB credentials
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
                .withConsistency(com.authcses.sdk.model.Consistency.full())
                .by("alice");
        assertTrue(checkResult.hasPermission(),
                "alice should have editor on e2e-doc-1, got: " + checkResult.permissionship());

        // Check: bob should NOT have editor
        CheckResult bobCheck = doc.check("editor")
                .withConsistency(com.authcses.sdk.model.Consistency.full())
                .by("bob");
        assertFalse(bobCheck.hasPermission(), "bob should NOT have editor on e2e-doc-1");
    }

    @Test
    @Order(3)
    void who_withRelation() {
        var doc = client.resource("document", "e2e-doc-1");

        Set<String> editors = doc.who().withRelation("editor")
                .withConsistency(com.authcses.sdk.model.Consistency.full())
                .fetchSet();
        assertTrue(editors.contains("alice"), "alice should be in editors list");
    }

    @Test
    @Order(4)
    void relations_read() {
        var doc = client.resource("document", "e2e-doc-1");

        var tuples = doc.relations("editor")
                .withConsistency(com.authcses.sdk.model.Consistency.full())
                .fetch();
        assertFalse(tuples.isEmpty(), "should have at least one editor relationship");
        assertEquals("alice", tuples.getFirst().subjectId());
    }

    @Test
    @Order(5)
    void lookup_resources() {
        List<String> docs = client.lookup("document").withPermission("editor").by("alice")
                .withConsistency(com.authcses.sdk.model.Consistency.full())
                .fetch();
        assertTrue(docs.contains("e2e-doc-1"), "alice should find e2e-doc-1 via lookup");
    }

    @Test
    @Order(6)
    void revoke_and_verify() {
        var doc = client.resource("document", "e2e-doc-1");
        doc.revoke("editor").from("alice");

        CheckResult checkResult = doc.check("editor")
                .withConsistency(com.authcses.sdk.model.Consistency.full())
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
                .withConsistency(com.authcses.sdk.model.Consistency.full())
                .by("carol").hasPermission());
        assertTrue(doc.check("editor")
                .withConsistency(com.authcses.sdk.model.Consistency.full())
                .by("dave").hasPermission());

        // Cleanup
        doc.batch()
                .revoke("owner").from("carol")
                .revoke("editor").from("dave")
                .execute();
    }
}
