package com.authx.sdk.transport;

import com.authx.sdk.model.*;
import com.authx.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckBulkMultiTest {

    @Test
    void checkBulkMulti_duplicatePermissions_returnsAllResults() {
        com.authx.sdk.transport.InMemoryTransport transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "doc-1"),
                        Relation.of("viewer"),
                        SubjectRef.of("user", "alice", null)),
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        ResourceRef.of("document", "doc-2"),
                        Relation.of("viewer"),
                        SubjectRef.of("user", "bob", null))
        ));

        java.util.List<com.authx.sdk.transport.SdkTransport.BulkCheckItem> items = List.of(
                new SdkTransport.BulkCheckItem(
                        ResourceRef.of("document", "doc-1"),
                        Permission.of("viewer"),
                        SubjectRef.of("user", "alice", null)),
                new SdkTransport.BulkCheckItem(
                        ResourceRef.of("document", "doc-2"),
                        Permission.of("viewer"),
                        SubjectRef.of("user", "bob", null))
        );

        List<CheckResult> results = transport.checkBulkMulti(items, Consistency.minimizeLatency());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
        assertThat(results.get(1).permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }
}
