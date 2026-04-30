package com.authx.sdk.e2e;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.WriteCompletion;
import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.ExpandTree;
import com.authx.sdk.model.Tuple;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("spicedb-e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SdkEndToEndTest {

    private static SpiceDbTestServer server;
    private static AuthxClient client;

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
    void grantCheckLookupAndRelations() {
        WriteCompletion grant = client.on("document")
                .select("e2e-doc-1")
                .grant("editor")
                .to("user:alice")
                .commit();
        assertNotNull(grant.zedToken());
        assertEquals(1, grant.updateCount());

        CheckResult check = client.on("document").select("e2e-doc-1").check("editor")
                .withConsistency(Consistency.full())
                .detailedBy("user:alice");
        assertTrue(check.hasPermission());

        CheckResult denied = client.on("document").select("e2e-doc-1").check("editor")
                .withConsistency(Consistency.full())
                .detailedBy("user:bob");
        assertFalse(denied.hasPermission());

        Set<String> editors = Set.copyOf(client.on("document")
                .select("e2e-doc-1")
                .lookupSubjects("user", "editor")
                .fetchIds());
        assertTrue(editors.contains("alice"));

        List<Tuple> tuples = client.on("document").select("e2e-doc-1").relations("editor")
                .withConsistency(Consistency.full())
                .fetch();
        assertFalse(tuples.isEmpty());
        assertEquals("alice", tuples.getFirst().subjectId());
    }

    @Test
    @Order(2)
    void lookupResourcesAndBatchCheck() {
        client.on("document").select("e2e-doc-2").grant("viewer").to("user:alice").commit();
        client.on("document").select("e2e-doc-3").grant("editor").to("user:alice").commit();
        client.on("document").select("e2e-doc-4").grant("owner").to("user:bob").commit();

        List<String> docs = client.on("document")
                .lookupResources("user:alice")
                .limit(100)
                .can("view");
        assertTrue(docs.contains("e2e-doc-2"));
        assertTrue(docs.contains("e2e-doc-3"));
        assertFalse(docs.contains("e2e-doc-4"));

        CheckMatrix matrix = client.on("document")
                .select("e2e-doc-2", "e2e-doc-3")
                .check("view")
                .byAll("user:alice", "user:bob");
        assertTrue(matrix.allowed("e2e-doc-2", "view", "user:alice"));
        assertTrue(matrix.allowed("e2e-doc-3", "view", "user:alice"));
        assertFalse(matrix.allowed("e2e-doc-2", "view", "user:bob"));
    }

    @Test
    @Order(3)
    void crossResourceBatchAndListener() throws Exception {
        WriteCompletion batch = client.batch()
                .on("document", "e2e-batch-doc")
                    .grant("owner").to("user:carol")
                    .grant("editor").to("user:dave")
                .on("folder", "e2e-batch-folder")
                    .grant("viewer").to("user:carol")
                .commit();
        assertEquals(3, batch.updateCount());
        assertTrue(client.on("document").select("e2e-batch-doc").check("owner").by("user:carol"));
        assertTrue(client.on("folder").select("e2e-batch-folder").check("viewer").by("user:carol"));

        AtomicInteger listenerCount = new AtomicInteger();
        CompletableFuture<WriteCompletion> future = client.on("document")
                .select("e2e-listener-doc")
                .grant("viewer")
                .to("user:listener")
                .listener(done -> listenerCount.set(done.updateCount()))
                .commit();

        WriteCompletion completion = future.get(5, TimeUnit.SECONDS);
        assertEquals(1, completion.updateCount());
        assertEquals(1, listenerCount.get());
    }

    @Test
    @Order(4)
    void expandAndDeleteByFilter() {
        client.on("document").select("e2e-expand-doc")
                .grant("owner").to("user:owner")
                .grant("viewer").to("user:viewer")
                .commit();

        ExpandTree tree = client.on("document").select("e2e-expand-doc")
                .expand("view");
        assertTrue(tree.contains("user:owner"));
        assertTrue(tree.contains("user:viewer"));

        client.on("document").select("e2e-expand-doc")
                .revoke("viewer").from("user:viewer")
                .commit();
        assertFalse(client.on("document").select("e2e-expand-doc").check("viewer").by("user:viewer"));
        assertTrue(client.on("document").select("e2e-expand-doc").check("owner").by("user:owner"));
    }
}
