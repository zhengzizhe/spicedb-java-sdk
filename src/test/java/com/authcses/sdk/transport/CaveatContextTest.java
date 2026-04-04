package com.authcses.sdk.transport;

import com.authcses.sdk.model.Consistency;
import com.authcses.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.authcses.sdk.transport.SdkTransport.RelationshipUpdate.Operation.TOUCH;
import static org.assertj.core.api.Assertions.assertThat;

class CaveatContextTest {

    @Test
    void check_withCaveatContext_doesNotThrow() {
        var transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH, "doc", "1", "viewer", "user", "alice", null)));

        var ctx = Map.<String, Object>of("ip_address", "192.168.1.1", "is_internal", true);
        var result = transport.check("doc", "1", "viewer", "user", "alice",
                Consistency.minimizeLatency(), ctx);
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }

    @Test
    void check_withCaveatContext_nullContext_delegatesToBaseCheck() {
        var transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH, "doc", "2", "editor", "user", "bob", null)));

        var result = transport.check("doc", "2", "editor", "user", "bob",
                Consistency.minimizeLatency(), null);
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }

    @Test
    void check_withCaveatContext_noPermission_returnsDenied() {
        var transport = new InMemoryTransport();

        // Use a HashMap to allow null values, covering the null branch in toValue()
        var ctx = new java.util.HashMap<String, Object>();
        ctx.put("region", "us-east");
        ctx.put("score", 42.5);
        ctx.put("active", false);
        ctx.put("tag", null);
        var result = transport.check("doc", "99", "viewer", "user", "unknown",
                Consistency.minimizeLatency(), ctx);
        assertThat(result.permissionship()).isEqualTo(Permissionship.NO_PERMISSION);
    }
}
