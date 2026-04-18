package com.authx.clustertest.caveat;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authzed.api.v1.ReadSchemaRequest;
import com.authzed.api.v1.SchemaServiceGrpc;
import com.authzed.api.v1.WriteSchemaRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end caveat tests against a live SpiceDB cluster.
 *
 * <p>Pre-requisite: {@code ./deploy/cluster-up.sh} has been run and ports
 * 50051/50052/50053 are reachable. Schema is appended (not replaced) with
 * caveat-using definitions so existing data isn't lost; teardown restores
 * the original schema.
 *
 * <p><b>Known flakiness:</b> SpiceDB's per-node namespace cache (with the
 * experimental watchable schema cache) lags WriteSchema response by up to
 * a few seconds. We sleep 5 s in @BeforeAll, but ~1 in 5 runs still hits
 * "object definition cv_doc not found" on the first check. Re-run on
 * failure or skip in CI until SpiceDB ships stable schema cache invalidation.
 */
class CaveatIT {

    // Pin to a single SpiceDB node — schema writes to 50051 take a moment to
    // propagate to 50052/50053's watchable schema cache; round-robin across all
    // three would race during setup.
    private static final String[] TARGETS = {"localhost:50051"};
    private static final String PSK = "testkey";

    private static final String CAVEAT_BLOCK = """

            // ── caveat IT additions (CaveatIT) ─────────────────────────
            caveat is_admin(role string) {
                role == "admin"
            }

            caveat in_window(now int, valid_from int, valid_to int) {
                now >= valid_from && now <= valid_to
            }

            definition cv_doc {
                relation admin_viewer: user with is_admin
                relation timed_viewer: user with in_window
                relation plain_viewer: user

                permission view_admin = admin_viewer
                permission view_timed = timed_viewer
                permission view_any   = admin_viewer + timed_viewer + plain_viewer
            }
            """;

    private static AuthxClient client;
    private static ManagedChannel adminChannel;
    private static String originalSchema;

    @BeforeAll
    static void up() throws Exception {
        adminChannel = NettyChannelBuilder.forAddress("localhost", 50051)
                .usePlaintext()
                .build();
        var stub = withAuth(SchemaServiceGrpc.newBlockingStub(adminChannel));

        // Always start from the canonical schema on disk, then append our caveat block.
        // Avoids "name reused" flakes if a prior run left state in SpiceDB.
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path schemaPath = here.resolve("../deploy/schema-v2.zed").normalize();
        if (!Files.exists(schemaPath)) {
            schemaPath = here.resolve("deploy/schema-v2.zed").normalize();
        }
        originalSchema = Files.readString(schemaPath);
        String combined = originalSchema + CAVEAT_BLOCK;
        stub.writeSchema(WriteSchemaRequest.newBuilder().setSchema(combined).build());

        // SpiceDB's per-node namespace cache lags WriteSchema by a few hundred ms
        // even with the watchable schema cache enabled. Sleep generously.
        try { Thread.sleep(5_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        client = AuthxClient.builder()
                .connection(c -> c.targets(TARGETS).presharedKey(PSK)
                        .requestTimeout(Duration.ofSeconds(15)))
                .features(f -> f.shutdownHook(false))
                .build();
    }

    @AfterAll
    static void down() {
        if (client != null) {
            // Best-effort revoke our test relationships.
            for (String rel : new String[]{"admin_viewer", "timed_viewer", "plain_viewer"}) {
                for (String user : new String[]{"alice", "bob"}) {
                    try { client.on("cv_doc").revoke("c1", rel, user); } catch (Exception ignored) { }
                }
            }
            try { client.close(); } catch (Exception ignored) { }
        }
        if (adminChannel != null) {
            try {
                if (originalSchema != null && !originalSchema.isBlank()) {
                    var stub = withAuth(SchemaServiceGrpc.newBlockingStub(adminChannel));
                    stub.writeSchema(WriteSchemaRequest.newBuilder()
                            .setSchema(originalSchema).build());
                }
            } catch (Exception ignored) { }
            adminChannel.shutdown();
            try { adminChannel.awaitTermination(5, TimeUnit.SECONDS); } catch (Exception ignored) { }
        }
    }

    private static SchemaServiceGrpc.SchemaServiceBlockingStub withAuth(
            SchemaServiceGrpc.SchemaServiceBlockingStub stub) {
        Metadata md = new Metadata();
        md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + PSK);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));
    }

    @Test
    void caveat_matchingContext_isAllowed() {
        client.on("cv_doc").resource("c1").grant("admin_viewer")
                .withCaveat("is_admin", Map.of())   // no pre-bound context — let check supply role
                .to("alice");

        CheckResult r = client.on("cv_doc").resource("c1")
                .check("view_admin")
                .withContext(Map.of("role", "admin"))
                .withConsistency(Consistency.full())
                .by("alice");

        assertThat(r.hasPermission()).isTrue();
        assertThat(r.isConditional()).isFalse();
    }

    @Test
    void caveat_nonMatchingContext_isDenied() {
        client.on("cv_doc").resource("c1").grant("admin_viewer")
                .withCaveat("is_admin", Map.of())   // no pre-bound context — let check supply role
                .to("alice");

        CheckResult r = client.on("cv_doc").resource("c1")
                .check("view_admin")
                .withContext(Map.of("role", "user"))
                .withConsistency(Consistency.full())
                .by("alice");

        assertThat(r.hasPermission()).isFalse();
        assertThat(r.isConditional()).isFalse();
    }

    @Test
    void caveat_missingRequiredContext_isConditional() {
        client.on("cv_doc").resource("c1").grant("admin_viewer")
                .withCaveat("is_admin", Map.of())   // no pre-bound context — let check supply role
                .to("alice");

        // No context provided — caveat cannot be evaluated.
        CheckResult r = client.on("cv_doc").resource("c1")
                .check("view_admin")
                .withConsistency(Consistency.full())
                .by("alice");

        assertThat(r.isConditional()).isTrue();
        assertThat(r.hasPermission()).isFalse();
    }

    @Test
    void caveat_numericContext_inWindow_isAllowed() {
        long now = System.currentTimeMillis() / 1000;
        // Pre-bind valid_from/valid_to at write time (these are stable per relationship).
        // The check supplies the dynamic 'now' field.
        client.on("cv_doc").resource("c1").grant("timed_viewer")
                .withCaveat("in_window", Map.of(
                        "valid_from", now - 60,
                        "valid_to", now + 60))
                .to("alice");

        CheckResult r = client.on("cv_doc").resource("c1")
                .check("view_timed")
                .withContext(Map.of("now", now))
                .withConsistency(Consistency.full())
                .by("alice");

        assertThat(r.hasPermission()).isTrue();
    }

    @Test
    void plainRelation_noCaveat_isAllowed() {
        // Sanity: same definition with a non-caveat relation works as expected.
        client.on("cv_doc").grant("c1", "plain_viewer", "bob");

        CheckResult r = client.on("cv_doc").resource("c1").check("view_any")
                .withConsistency(Consistency.full())
                .by("bob");
        assertThat(r.hasPermission()).isTrue();
    }
}
