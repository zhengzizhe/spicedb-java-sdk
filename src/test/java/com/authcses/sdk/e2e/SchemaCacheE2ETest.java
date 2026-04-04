package com.authcses.sdk.e2e;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.exception.InvalidPermissionException;
import com.authcses.sdk.exception.InvalidResourceException;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E2E test: verifies SchemaCache validates resource types, relations, permissions
 * using schema data from /sdk/connect.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaCacheE2ETest {

    private static AuthCsesClient client;

    @BeforeAll
    static void setup() {
        try {
            client = AuthCsesClient.builder()
                    .connection(c -> c.target("localhost:50051").presharedKey("dev-token"))
                    .features(f -> f.telemetry(false))
                    .build();
        } catch (Exception e) {
            assumeTrue(false, "Platform not reachable: " + e.getMessage());
        }
    }

    @AfterAll
    static void teardown() {
        if (client != null) client.close();
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
