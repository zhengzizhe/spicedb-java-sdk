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
    @Test
    void detailedBulkUsesDifferentContextsForDuplicateCellsInOneRealSnapshot() {
        client.on("document").select("bulk-context-doc").grant("timed_viewer").to("user:carol")
                .withCaveat("not_expired", Map.of("expires_at", Instant.parse("2026-05-01T00:00:00Z"))).commit();
        com.authx.sdk.model.SubjectRef subject = com.authx.sdk.model.SubjectRef.parse("user:carol");
        com.authx.sdk.model.DetailedBulkCheckResult result = client.batchCheck()
                .add("document", "bulk-context-doc", "view", subject, Map.of("now", Instant.parse("2026-04-30T00:00:00Z")))
                .add("document", "bulk-context-doc", "view", subject, Map.of("now", Instant.parse("2026-05-02T00:00:00Z")))
                .add("document", "bulk-context-doc", "view", subject)
                .withConsistency(Consistency.full()).fetchInSingleSnapshot();
        assertThat(result.items().get(0).result().hasPermission()).isTrue();
        assertThat(result.items().get(1).result().hasPermission()).isFalse();
        assertThat(result.items().get(2).result().isConditional()).isTrue();
        assertThat(result.requireSingleSnapshotToken()).isNotBlank();
    }

    @Test
    void existingFluentMultiResourceChecksRetainTheirContext() {
        for (String id : java.util.List.of("fluent-bulk-a", "fluent-bulk-b")) {
            client.on("document").select(id).grant("timed_viewer").to("user:dave")
                    .withCaveat("not_expired", Map.of("expires_at", Instant.parse("2026-05-01T00:00:00Z"))).commit();
        }
        com.authx.sdk.model.CheckMatrix result = client.on("document").select("fluent-bulk-a", "fluent-bulk-b")
                .check("view").withConsistency(Consistency.full())
                .withContext(Map.of("now", Instant.parse("2026-04-30T00:00:00Z")))
                .byAll(com.authx.sdk.model.SubjectRef.parse("user:dave"));
        assertThat(result.allowed("fluent-bulk-a", "view", "user:dave")).isTrue();
        assertThat(result.allowed("fluent-bulk-b", "view", "user:dave")).isTrue();
    }

    @Test
    void twoCoordinatedSdkClientsObserveGrantRevokeAndRegrantWithoutHeadReads() {
        com.authx.sdk.spi.CoordinatedTokenStore coordinator = new com.authx.sdk.spi.CoordinatedTokenStore() {
            private String token;
            private boolean pending;
            @Override public synchronized com.authx.sdk.model.WriteResult executeWrite(
                    java.util.function.Supplier<com.authx.sdk.model.WriteResult> mutation) {
                if (pending) throw new IllegalStateException("Uncertain write");
                pending = true;
                com.authx.sdk.model.WriteResult result = mutation.get();
                token = result.zedToken(); pending = false; return result;
            }
            @Override public synchronized String readRequiredToken() {
                if (pending || token == null) throw new IllegalStateException("No confirmed token");
                return token;
            }
        };
        com.authx.sdk.policy.PolicyRegistry policies = com.authx.sdk.policy.PolicyRegistry.builder()
                .defaultPolicy(com.authx.sdk.policy.ResourcePolicy.builder()
                        .readConsistency(com.authx.sdk.policy.ReadConsistency.coordinatedSession()).build()).build();
        try (AuthxClient writer = AuthxClient.builder()
                    .connection(c -> c.target(server.target()).presharedKey(SpiceDbTestServer.PRESHARED_KEY))
                    .extend(e -> e.policies(policies).components(com.authx.sdk.spi.SdkComponents.builder()
                            .coordinatedTokenStore(coordinator).build())).build();
             AuthxClient reader = AuthxClient.builder()
                    .connection(c -> c.target(server.target()).presharedKey(SpiceDbTestServer.PRESHARED_KEY))
                    .extend(e -> e.policies(policies).components(com.authx.sdk.spi.SdkComponents.builder()
                            .coordinatedTokenStore(coordinator).build())).build()) {
            for (int cycle = 0; cycle < 3; cycle++) {
                writer.on("document").select("coordinated-doc").grant("timed_viewer").to("user:coordinated")
                        .withCaveat("not_expired", Map.of("expires_at", Instant.parse("2026-05-01T00:00:00Z"))).commit();
                assertThat(reader.on("document").select("coordinated-doc").check("view")
                        .withContext(Map.of("now", Instant.parse("2026-04-30T00:00:00Z")))
                        .detailedBy("user:coordinated").hasPermission()).isTrue();
                writer.on("document").select("coordinated-doc").revoke("timed_viewer").from("user:coordinated").commit();
                assertThat(reader.on("document").select("coordinated-doc").check("view")
                        .withContext(Map.of("now", Instant.parse("2026-04-30T00:00:00Z")))
                        .detailedBy("user:coordinated").hasPermission()).isFalse();
            }
        }
    }

    @Test
    void detailedRelationshipReadPreservesTheStoredCaveatContext() {
        client.on("document").select("detailed-metadata").grant("timed_viewer").to("user:metadata")
                .withCaveat("not_expired", Map.of("expires_at", "2027-05-01T00:00:00Z")).commit();
        java.util.List<com.authx.sdk.model.RelationshipRecord> records = client.readRelationshipDetails(
                com.authx.sdk.model.ResourceRef.of("document", "detailed-metadata"), null, Consistency.full());
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().tuple().subject()).isEqualTo("user:metadata");
        assertThat(records.getFirst().caveat().name()).isEqualTo("not_expired");
        assertThat(records.getFirst().caveat().context()).containsEntry("expires_at", "2027-05-01T00:00:00Z");
    }

}
