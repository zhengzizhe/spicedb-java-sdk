package com.authx.sdk.e2e;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CaveatContext;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("spicedb-e2e")
class SpiceDbCaveatE2eTest {

    private static final String CAVEATED_SCHEMA = """
            caveat not_expired(now timestamp, expires_at timestamp) {
                now < expires_at
            }

            definition user {}

            definition document {
                relation timed_viewer: user with not_expired
                permission view = timed_viewer
            }
            """;

    private static SpiceDbTestServer server;
    private static AuthxClient client;

    @BeforeAll
    static void setup() {
        server = SpiceDbTestServer.start();
        client = AuthxClient.builder()
                .connection(c -> c.target(server.target()).presharedKey(SpiceDbTestServer.PRESHARED_KEY))
                .build();
        client.schema().writeRaw(CAVEATED_SCHEMA);
    }

    @AfterAll
    static void teardown() {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    @Test
    void caveatedRelationshipUsesTypedContextForGrantAndCheck() {
        Instant expiresAt = Instant.parse("2026-05-01T00:00:00Z");

        client.on("document")
                .select("caveat-doc-1")
                .grant("timed_viewer")
                .to("user:alice")
                .withCaveat("not_expired", CaveatContext.of(Map.of("expires_at", expiresAt)))
                .commit();

        CheckResult allowed = client.on("document")
                .select("caveat-doc-1")
                .check("view")
                .withConsistency(Consistency.full())
                .given(CaveatContext.of(Map.of("now", Instant.parse("2026-04-30T00:00:00Z"))))
                .detailedBy("user:alice");

        CheckResult denied = client.on("document")
                .select("caveat-doc-1")
                .check("view")
                .withConsistency(Consistency.full())
                .given(CaveatContext.of(Map.of("now", Instant.parse("2026-05-02T00:00:00Z"))))
                .detailedBy("user:alice");

        assertThat(allowed.hasPermission()).isTrue();
        assertThat(denied.hasPermission()).isFalse();
    }

    @Test
    void existingMapBasedCaveatContextStillWorks() {
        Instant expiresAt = Instant.parse("2026-05-01T00:00:00Z");

        client.on("document")
                .select("caveat-doc-2")
                .grant("timed_viewer")
                .to("user:bob")
                .withCaveat("not_expired", Map.of("expires_at", expiresAt))
                .commit();

        CheckResult result = client.on("document")
                .select("caveat-doc-2")
                .check("view")
                .withConsistency(Consistency.full())
                .given(Map.of("now", Instant.parse("2026-04-30T00:00:00Z")))
                .detailedBy("user:bob");

        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }
}
