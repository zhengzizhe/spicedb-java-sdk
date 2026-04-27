package com.authx.sdk;

import com.authx.sdk.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the full SDK fluent API using InMemory transport.
 */
class AuthxClientTest {

    private AuthxClient client;

    @BeforeEach
    void setup() {
        client = AuthxClient.inMemory();
    }

    @AfterEach
    void teardown() {
        client.close();
    }

    // ---- Grant & Check ----

    @Test
    void grant_single_thenCheck() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor").to("user:alice");

        assertTrue(doc.check("editor").by("user:alice").hasPermission());
        assertFalse(doc.check("editor").by("user:bob").hasPermission());
    }

    @Test
    void grant_multiple_users() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("viewer").to("user:alice", "user:bob", "user:carol");

        assertTrue(doc.check("viewer").by("user:alice").hasPermission());
        assertTrue(doc.check("viewer").by("user:bob").hasPermission());
        assertTrue(doc.check("viewer").by("user:carol").hasPermission());
        assertFalse(doc.check("viewer").by("user:dave").hasPermission());
    }

    @Test
    void grant_multiple_relations() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor", "can_download").to("user:alice");

        assertTrue(doc.check("editor").by("user:alice").hasPermission());
        assertTrue(doc.check("can_download").by("user:alice").hasPermission());
    }

    @Test
    void grant_collection() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("viewer").to("user:alice", "user:bob");

        assertTrue(doc.check("viewer").by("user:alice").hasPermission());
        assertTrue(doc.check("viewer").by("user:bob").hasPermission());
    }

    @Test
    void grant_toSubjects() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("viewer").to("department:eng#member");

        java.util.List<com.authx.sdk.model.Tuple> tuples = doc.relations("viewer").fetch();
        assertEquals(1, tuples.size());
        assertEquals("department", tuples.getFirst().subjectType());
        assertEquals("eng", tuples.getFirst().subjectId());
        assertEquals("member", tuples.getFirst().subjectRelation());
    }

    @Test
    void grantResult_returnsCount() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        GrantResult r = doc.grant("editor").to("user:alice", "user:bob");
        assertEquals(2, r.count());
        assertNotNull(r.zedToken());
    }

    // ---- Revoke ----

    @Test
    void revoke_removesRelationship() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor").to("user:alice");
        assertTrue(doc.check("editor").by("user:alice").hasPermission());

        doc.revoke("editor").from("user:alice");
        assertFalse(doc.check("editor").by("user:alice").hasPermission());
    }

    @Test
    void revokeAll_removesAllRelationsForUser() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor").to("user:alice");
        doc.grant("viewer").to("user:alice");
        doc.grant("can_download").to("user:alice");

        doc.revokeAll().from("user:alice");

        assertFalse(doc.check("editor").by("user:alice").hasPermission());
        assertFalse(doc.check("viewer").by("user:alice").hasPermission());
        assertFalse(doc.check("can_download").by("user:alice").hasPermission());
    }

    // ---- Check Bulk ----

    @Test
    void check_byAll() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("viewer").to("user:alice", "user:carol");

        BulkCheckResult result = doc.check("viewer").byAll("user:alice", "user:bob", "user:carol");

        assertTrue(result.get("alice").hasPermission());
        assertFalse(result.get("bob").hasPermission());
        assertTrue(result.get("carol").hasPermission());
        assertEquals(2, result.allowedCount());
        assertEquals(Set.of("alice", "carol"), result.allowedSet());
    }

    // ---- CheckAll ----

    @Test
    void checkAll_permissionSet() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor").to("user:alice");
        doc.grant("viewer").to("user:alice");

        PermissionSet perms = doc.checkAll("editor", "viewer", "owner").by("user:alice");

        assertTrue(perms.can("editor"));
        assertTrue(perms.can("viewer"));
        assertFalse(perms.can("owner"));
        assertEquals(Set.of("editor", "viewer"), perms.allowed());
    }

    @Test
    void checkAll_permissionMatrix() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor").to("user:alice");
        doc.grant("viewer").to("user:bob");

        PermissionMatrix matrix = doc.checkAll("editor", "viewer").byAll("user:alice", "user:bob");

        assertTrue(matrix.get("user:alice").can("editor"));
        assertFalse(matrix.get("user:bob").can("editor"));
        assertFalse(matrix.get("user:alice").can("viewer"));
        assertTrue(matrix.get("user:bob").can("viewer"));
    }

    // ---- Who ----

    @Test
    void who_withRelation() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor").to("user:alice", "user:bob");
        doc.grant("viewer").to("user:carol");

        List<String> editors = doc.who("user").withRelation("editor").fetch();
        assertEquals(2, editors.size());
        assertTrue(editors.contains("alice"));
        assertTrue(editors.contains("bob"));
    }

    @Test
    void who_withPermission() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("viewer").to("user:alice", "user:bob");

        // InMemory: permission == relation match
        Set<String> viewers = doc.who("user").withPermission("viewer").fetchSet();
        assertEquals(Set.of("alice", "bob"), viewers);
    }

    @Test
    void who_fetchExists() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        assertFalse(doc.who("user").withRelation("editor").fetchExists());

        doc.grant("editor").to("user:alice");
        assertTrue(doc.who("user").withRelation("editor").fetchExists());
    }

    @Test
    void who_fetchCount() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor").to("user:alice", "user:bob", "user:carol");
        assertEquals(3, doc.who("user").withRelation("editor").fetchCount());
    }

    // ---- Relations ----

    @Test
    void relations_fetch() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor").to("user:alice");
        doc.grant("viewer").to("user:bob");

        List<Tuple> all = doc.relations().fetch();
        assertEquals(2, all.size());
    }

    @Test
    void relations_filtered() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor").to("user:alice");
        doc.grant("viewer").to("user:bob");

        List<Tuple> editors = doc.relations("editor").fetch();
        assertEquals(1, editors.size());
        assertEquals("alice", editors.getFirst().subjectId());
    }

    @Test
    void relations_fetchSubjectIds() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor").to("user:alice", "user:bob");

        Set<String> ids = doc.relations("editor").fetchSubjectIdSet();
        assertEquals(Set.of("alice", "bob"), ids);
    }

    @Test
    void relations_groupByRelation() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("editor").to("user:alice");
        doc.grant("viewer").to("user:bob", "user:carol");

        Map<String, List<String>> grouped = doc.relations().groupByRelation();
        assertEquals(1, grouped.get("editor").size());
        assertEquals(2, grouped.get("viewer").size());
    }

    // ---- Lookup ----

    @Test
    void lookup_resources() {
        client.resource("document", "doc-1").grant("viewer").to("user:alice");
        client.resource("document", "doc-2").grant("viewer").to("user:alice");
        client.resource("document", "doc-3").grant("editor").to("user:alice");

        List<String> viewable = client.lookup("document").withPermission("viewer").by("user:alice").fetch();
        assertEquals(2, viewable.size());
        assertTrue(viewable.contains("doc-1"));
        assertTrue(viewable.contains("doc-2"));
    }

    @Test
    void lookup_fetchExists() {
        assertFalse(client.lookup("document").withPermission("viewer").by("user:alice").fetchExists());

        client.resource("document", "doc-1").grant("viewer").to("user:alice");
        assertTrue(client.lookup("document").withPermission("viewer").by("user:alice").fetchExists());
    }

    // ---- Batch ----

    @Test
    void batch_mixedGrantRevoke() {
        com.authx.sdk.ResourceHandle doc = client.resource("document", "doc-1");
        doc.grant("owner").to("user:alice");

        BatchResult result = doc.batch()
                .revoke("owner").from("user:alice")
                .grant("owner").to("user:bob")
                .grant("editor").to("user:alice")
                .execute();

        assertNotNull(result.zedToken());
        assertFalse(doc.check("owner").by("user:alice").hasPermission());
        assertTrue(doc.check("owner").by("user:bob").hasPermission());
        assertTrue(doc.check("editor").by("user:alice").hasPermission());
    }

    // ---- Resource isolation ----

    @Test
    void differentResources_independent() {
        client.resource("document", "doc-1").grant("editor").to("user:alice");
        client.resource("document", "doc-2").grant("viewer").to("user:bob");

        assertFalse(client.resource("document", "doc-1").check("editor").by("user:bob").hasPermission());
        assertFalse(client.resource("document", "doc-2").check("viewer").by("user:alice").hasPermission());
    }

    // ---- Builder ----

    @Test
    void builder_requiresPresharedKey() {
        assertThrows(NullPointerException.class, () ->
                AuthxClient.builder()
                        .connection(c -> c.target("localhost:50051"))
                        .build());
    }

    @Test
    void builder_requiresTarget() {
        assertThrows(IllegalArgumentException.class, () ->
                AuthxClient.builder()
                        .connection(c -> c.presharedKey("key"))
                        .build());
    }
}
