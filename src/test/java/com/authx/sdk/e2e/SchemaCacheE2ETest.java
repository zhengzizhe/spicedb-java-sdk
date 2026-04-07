package com.authx.sdk.e2e;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.exception.InvalidPermissionException;
import com.authx.sdk.exception.InvalidResourceException;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E2E test: verifies SchemaCache validates resource types, relations, permissions
 * using schema data loaded from SpiceDB via Testcontainers.
 * If Docker is unavailable, tests are skipped gracefully.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaCacheE2ETest {

    static GenericContainer<?> spicedb;
    private static AuthxClient client;

    @BeforeAll
    static void setup() {
        try {
            spicedb = new GenericContainer<>("authzed/spicedb:v1.33.0")
                    .withCommand("serve-testing", "--grpc-preshared-key=testkey")
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
                .features(f -> f.telemetry(false))
                .build();
    }

    @AfterAll
    static void teardown() {
        if (client != null) client.close();
        if (spicedb != null && spicedb.isRunning()) spicedb.stop();
    }

    @Test
    @Order(1)
    void validResourceType_works() {
        assertDoesNotThrow(() -> client.resource("document", "doc-1"));
        assertDoesNotThrow(() -> client.resource("folder", "f-1"));
        assertDoesNotThrow(() -> client.resource("group", "g-1"));
    }

    @Test
    @Order(2)
    void invalidResourceType_throwsWithSuggestion() {
        var ex = assertThrows(InvalidResourceException.class,
                () -> client.resource("docment", "doc-1"));
        assertTrue(ex.getMessage().contains("docment"), "should mention the typo");
        assertTrue(ex.getMessage().contains("document"), "should suggest 'document'");
    }

    @Test
    @Order(3)
    void completelyWrongType_throwsWithAvailableList() {
        var ex = assertThrows(InvalidResourceException.class,
                () -> client.resource("xyz_nonexistent", "id-1"));
        assertTrue(ex.getMessage().contains("Available"), "should list available types");
    }

    @Test
    @Order(4)
    void validOperations_work() {
        var doc = client.resource("document", "schema-test-doc");

        // grant with valid relation
        assertDoesNotThrow(() -> doc.grant("editor").to("schema-test-user"));

        // check with valid permission
        assertDoesNotThrow(() -> doc.check("view").by("schema-test-user"));

        // cleanup
        doc.revoke("editor").from("schema-test-user");
    }
}
