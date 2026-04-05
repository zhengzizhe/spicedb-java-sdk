package com.authcses.sdk;

import com.authcses.sdk.model.*;
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
class AuthCsesClientTest {

    private AuthCsesClient client;

    @BeforeEach
    void setup() {
        client = AuthCsesClient.inMemory();
    }

    @AfterEach
    void teardown() {
        client.close();
    }

    // ---- Grant & Check ----

    @Test
    void grant_single_thenCheck() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor").to("alice");

        assertTrue(doc.check("editor").by("alice").hasPermission());
        assertFalse(doc.check("editor").by("bob").hasPermission());
    }

    @Test
    void grant_multiple_users() {
        var doc = client.resource("document", "doc-1");
        doc.grant("viewer").to("alice", "bob", "carol");

        assertTrue(doc.check("viewer").by("alice").hasPermission());
        assertTrue(doc.check("viewer").by("bob").hasPermission());
        assertTrue(doc.check("viewer").by("carol").hasPermission());
        assertFalse(doc.check("viewer").by("dave").hasPermission());
    }

    @Test
    void grant_multiple_relations() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor", "can_download").to("alice");

        assertTrue(doc.check("editor").by("alice").hasPermission());
        assertTrue(doc.check("can_download").by("alice").hasPermission());
    }

    @Test
    void grant_collection() {
        var doc = client.resource("document", "doc-1");
        doc.grant("viewer").to(List.of("alice", "bob"));

        assertTrue(doc.check("viewer").by("alice").hasPermission());
        assertTrue(doc.check("viewer").by("bob").hasPermission());
    }

    @Test
    void grant_toSubjects() {
        var doc = client.resource("document", "doc-1");
        doc.grant("viewer").toSubjects("department:eng#member");

        var tuples = doc.relations("viewer").fetch();
        assertEquals(1, tuples.size());
        assertEquals("department", tuples.getFirst().subjectType());
        assertEquals("eng", tuples.getFirst().subjectId());
        assertEquals("member", tuples.getFirst().subjectRelation());
    }

    @Test
    void grantResult_returnsCount() {
        var doc = client.resource("document", "doc-1");
        GrantResult r = doc.grant("editor").to("alice", "bob");
        assertEquals(2, r.count());
        assertNotNull(r.zedToken());
    }

    // ---- Revoke ----

    @Test
    void revoke_removesRelationship() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor").to("alice");
        assertTrue(doc.check("editor").by("alice").hasPermission());

        doc.revoke("editor").from("alice");
        assertFalse(doc.check("editor").by("alice").hasPermission());
    }

    @Test
    void revokeAll_removesAllRelationsForUser() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor").to("alice");
        doc.grant("viewer").to("alice");
        doc.grant("can_download").to("alice");

        doc.revokeAll().from("alice");

        assertFalse(doc.check("editor").by("alice").hasPermission());
        assertFalse(doc.check("viewer").by("alice").hasPermission());
        assertFalse(doc.check("can_download").by("alice").hasPermission());
    }

    // ---- Check Bulk ----

    @Test
    void check_byAll() {
        var doc = client.resource("document", "doc-1");
        doc.grant("viewer").to("alice", "carol");

        BulkCheckResult result = doc.check("viewer").byAll("alice", "bob", "carol");

        assertTrue(result.get("alice").hasPermission());
        assertFalse(result.get("bob").hasPermission());
        assertTrue(result.get("carol").hasPermission());
        assertEquals(2, result.allowedCount());
        assertEquals(Set.of("alice", "carol"), result.allowedSet());
    }

    // ---- CheckAll ----

    @Test
    void checkAll_permissionSet() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor").to("alice");
        doc.grant("viewer").to("alice");

        PermissionSet perms = doc.checkAll("editor", "viewer", "owner").by("alice");

        assertTrue(perms.can("editor"));
        assertTrue(perms.can("viewer"));
        assertFalse(perms.can("owner"));
        assertEquals(Set.of("editor", "viewer"), perms.allowed());
    }

    @Test
    void checkAll_permissionMatrix() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor").to("alice");
        doc.grant("viewer").to("bob");

        PermissionMatrix matrix = doc.checkAll("editor", "viewer").byAll("alice", "bob");

        assertTrue(matrix.get("alice").can("editor"));
        assertFalse(matrix.get("bob").can("editor"));
        assertFalse(matrix.get("alice").can("viewer"));
        assertTrue(matrix.get("bob").can("viewer"));
    }

    // ---- Who ----

    @Test
    void who_withRelation() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor").to("alice", "bob");
        doc.grant("viewer").to("carol");

        List<String> editors = doc.who().withRelation("editor").fetch();
        assertEquals(2, editors.size());
        assertTrue(editors.contains("alice"));
        assertTrue(editors.contains("bob"));
    }

    @Test
    void who_withPermission() {
        var doc = client.resource("document", "doc-1");
        doc.grant("viewer").to("alice", "bob");

        // InMemory: permission == relation match
        Set<String> viewers = doc.who().withPermission("viewer").fetchSet();
        assertEquals(Set.of("alice", "bob"), viewers);
    }

    @Test
    void who_fetchExists() {
        var doc = client.resource("document", "doc-1");
        assertFalse(doc.who().withRelation("editor").fetchExists());

        doc.grant("editor").to("alice");
        assertTrue(doc.who().withRelation("editor").fetchExists());
    }

    @Test
    void who_fetchCount() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor").to("alice", "bob", "carol");
        assertEquals(3, doc.who().withRelation("editor").fetchCount());
    }

    // ---- Relations ----

    @Test
    void relations_fetch() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor").to("alice");
        doc.grant("viewer").to("bob");

        List<Tuple> all = doc.relations().fetch();
        assertEquals(2, all.size());
    }

    @Test
    void relations_filtered() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor").to("alice");
        doc.grant("viewer").to("bob");

        List<Tuple> editors = doc.relations("editor").fetch();
        assertEquals(1, editors.size());
        assertEquals("alice", editors.getFirst().subjectId());
    }

    @Test
    void relations_fetchSubjectIds() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor").to("alice", "bob");

        Set<String> ids = doc.relations("editor").fetchSubjectIdSet();
        assertEquals(Set.of("alice", "bob"), ids);
    }

    @Test
    void relations_groupByRelation() {
        var doc = client.resource("document", "doc-1");
        doc.grant("editor").to("alice");
        doc.grant("viewer").to("bob", "carol");

        Map<String, List<String>> grouped = doc.relations().groupByRelation();
        assertEquals(1, grouped.get("editor").size());
        assertEquals(2, grouped.get("viewer").size());
    }

    // ---- Lookup ----

    @Test
    void lookup_resources() {
        client.resource("document", "doc-1").grant("viewer").to("alice");
        client.resource("document", "doc-2").grant("viewer").to("alice");
        client.resource("document", "doc-3").grant("editor").to("alice");

        List<String> viewable = client.lookup("document").withPermission("viewer").by("alice").fetch();
        assertEquals(2, viewable.size());
        assertTrue(viewable.contains("doc-1"));
        assertTrue(viewable.contains("doc-2"));
    }

    @Test
    void lookup_fetchExists() {
        assertFalse(client.lookup("document").withPermission("viewer").by("alice").fetchExists());

        client.resource("document", "doc-1").grant("viewer").to("alice");
        assertTrue(client.lookup("document").withPermission("viewer").by("alice").fetchExists());
    }

    // ---- Batch ----

    @Test
    void batch_mixedGrantRevoke() {
        var doc = client.resource("document", "doc-1");
        doc.grant("owner").to("alice");

        BatchResult result = doc.batch()
                .revoke("owner").from("alice")
                .grant("owner").to("bob")
                .grant("editor").to("alice")
                .execute();

        assertNotNull(result.zedToken());
        assertFalse(doc.check("owner").by("alice").hasPermission());
        assertTrue(doc.check("owner").by("bob").hasPermission());
        assertTrue(doc.check("editor").by("alice").hasPermission());
    }

    // ---- Resource isolation ----

    @Test
    void differentResources_independent() {
        client.resource("document", "doc-1").grant("editor").to("alice");
        client.resource("document", "doc-2").grant("viewer").to("bob");

        assertFalse(client.resource("document", "doc-1").check("editor").by("bob").hasPermission());
        assertFalse(client.resource("document", "doc-2").check("viewer").by("alice").hasPermission());
    }

    // ---- Builder ----

    @Test
    void builder_requiresPresharedKey() {
        assertThrows(NullPointerException.class, () ->
                AuthCsesClient.builder()
                        .connection(c -> c.target("localhost:50051"))
                        .build());
    }

    @Test
    void builder_requiresTarget() {
        assertThrows(IllegalArgumentException.class, () ->
                AuthCsesClient.builder()
                        .connection(c -> c.presharedKey("key"))
                        .build());
    }
}
