package com.authx.sdk.model;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CheckResultTest {

    @Test
    void allowed_hasPermission() {
        var r = CheckResult.allowed("token1");
        assertTrue(r.hasPermission());
        assertFalse(r.isConditional());
    }

    @Test
    void denied_noPermission() {
        var r = CheckResult.denied("token1");
        assertFalse(r.hasPermission());
        assertFalse(r.isConditional());
    }

    @Test
    void conditional() {
        var r = new CheckResult(com.authx.sdk.model.enums.Permissionship.CONDITIONAL_PERMISSION, "t", Optional.empty());
        assertFalse(r.hasPermission());
        assertTrue(r.isConditional());
    }

    @Test
    void bulkCheckResult_aggregations() {
        var results = new BulkCheckResult(Map.of(
                "alice", CheckResult.allowed("t"),
                "bob", CheckResult.denied("t"),
                "carol", CheckResult.allowed("t")));

        assertEquals(2, results.allowedCount());
        assertTrue(results.anyAllowed());
        assertFalse(results.allAllowed());
        assertTrue(results.allowedSet().contains("alice"));
        assertTrue(results.allowedSet().contains("carol"));
        assertEquals(List.of("bob"), results.denied());
    }

    @Test
    void permissionSet_can() {
        var ps = new PermissionSet(Map.of(
                "view", CheckResult.allowed("t"),
                "edit", CheckResult.denied("t")));

        assertTrue(ps.can("view"));
        assertFalse(ps.can("edit"));
        assertFalse(ps.can("nonexistent"));
        assertEquals(Set.of("view"), ps.allowed());
        assertEquals(Set.of("edit"), ps.denied());
    }

    @Test
    void permissionMatrix_whoCanAll() {
        var alice = new PermissionSet(Map.of(
                "view", CheckResult.allowed("t"),
                "edit", CheckResult.allowed("t")));
        var bob = new PermissionSet(Map.of(
                "view", CheckResult.allowed("t"),
                "edit", CheckResult.denied("t")));

        var matrix = new PermissionMatrix(Map.of("alice", alice, "bob", bob));

        assertEquals(List.of("alice"), matrix.whoCanAll("view", "edit"));
        assertEquals(2, matrix.whoCanAny("view", "edit").size());
    }
}
