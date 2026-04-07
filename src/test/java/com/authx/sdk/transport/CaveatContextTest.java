package com.authx.sdk.transport;

import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation.TOUCH;
import static org.assertj.core.api.Assertions.assertThat;

class CaveatContextTest {

    @Test
    void check_withCaveatContext_doesNotThrow() {
        var transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("doc", "1"), Relation.of("viewer"),
                        SubjectRef.of("user", "alice", null))));

        var ctx = Map.<String, Object>of("ip_address", "192.168.1.1", "is_internal", true);
        var request = new CheckRequest(
                ResourceRef.of("doc", "1"), Permission.of("viewer"),
                SubjectRef.of("user", "alice", null),
                Consistency.minimizeLatency(), ctx);
        var result = transport.check(request);
        assertThat(result.permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }

    @Test
    void check_withCaveatContext_nullContext_delegatesToBaseCheck() {
        var transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("doc", "2"), Relation.of("editor"),
                        SubjectRef.of("user", "bob", null))));

        var request = new CheckRequest(
                ResourceRef.of("doc", "2"), Permission.of("editor"),
                SubjectRef.of("user", "bob", null),
                Consistency.minimizeLatency(), null);
        var result = transport.check(request);
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
        var request = new CheckRequest(
                ResourceRef.of("doc", "99"), Permission.of("viewer"),
                SubjectRef.of("user", "unknown", null),
                Consistency.minimizeLatency(), ctx);
        var result = transport.check(request);
        assertThat(result.permissionship()).isEqualTo(Permissionship.NO_PERMISSION);
    }
}
