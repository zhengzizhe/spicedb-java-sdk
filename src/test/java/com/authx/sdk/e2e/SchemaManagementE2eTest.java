package com.authx.sdk.e2e;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.exception.AuthxPreconditionException;
import com.authx.sdk.model.SchemaDiffResult;
import com.authx.sdk.model.SchemaReadResult;
import com.authx.sdk.model.SchemaWriteResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("spicedb-e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaManagementE2eTest {

    private static SpiceDbTestServer server;
    private static AuthxClient client;

    private static final String UPDATED_SCHEMA = """
            definition user {}

            definition document {
                relation owner: user
                relation editor: user
                relation viewer: user
                permission view = owner + editor + viewer
            }

            definition workspace {
                relation admin: user
                relation member: user
                permission manage = admin
                permission view = admin + member
            }
            """;

    @BeforeAll
    static void setup() {
        server = SpiceDbTestServer.start();
        client = AuthxClient.builder()
                .connection(c -> c.target(server.target()).presharedKey(SpiceDbTestServer.PRESHARED_KEY))
                .build();
    }

    @AfterAll
    static void teardown() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    @Test
    @Order(1)
    void readRawReturnsServerSchemaAndRevision() {
        SchemaReadResult result = client.schema().readRaw();

        assertThat(result.schemaText()).contains("definition document");
        assertThat(result.schemaText()).contains("relation viewer: user");
        assertThat(result.zedToken()).isNotBlank();
    }

    @Test
    @Order(2)
    void diffRawReturnsStableSdkEntries() {
        SchemaDiffResult result = client.schema().diffRaw(UPDATED_SCHEMA);

        assertThat(result.hasDiffs()).isTrue();
        assertThat(result.zedToken()).isNotBlank();
        assertThat(result.diffs())
                .anySatisfy(diff -> {
                    assertThat(diff.kind()).contains("DEFINITION");
                    assertThat(diff.target()).isNotBlank();
                });
    }

    @Test
    @Order(3)
    void writeRawUpdatesServerSchemaAndRefreshesLocalCache() {
        SchemaWriteResult write = client.schema().writeRaw(UPDATED_SCHEMA);

        assertThat(write.zedToken()).isNotBlank();
        assertThat(client.schema().readRaw().schemaText()).contains("definition workspace");
        assertThat(client.schema().hasResourceType("workspace")).isTrue();
        assertThat(client.schema().relationsOf("workspace")).contains("admin", "member");
        assertThat(client.schema().permissionsOf("workspace")).contains("manage", "view");
    }

    @Test
    @Order(4)
    void refreshReloadsLocalSchemaCache() {
        assertThat(client.schema().refresh()).isTrue();
        assertThat(client.schema().resourceTypes()).contains("document", "workspace");
    }

    @Test
    @Order(5)
    void invalidInputsFailBeforeOrThroughMappedSdkExceptions() {
        assertThatThrownBy(() -> client.schema().writeRaw(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");

        assertThatThrownBy(() -> client.schema().diffRaw(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comparisonSchema");

        assertThatThrownBy(() -> client.schema().writeRaw("""
                definition document {
                relation viewer: missing
                }
                """))
                .isInstanceOf(AuthxPreconditionException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @Order(6)
    void inMemoryClientFailsFastForRemoteSchemaManagement() {
        AuthxClient inMemory = AuthxClient.inMemory();
        try {
            assertThatThrownBy(() -> inMemory.schema().readRaw())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("live SpiceDB");
        } finally {
            inMemory.close();
        }
    }
}
