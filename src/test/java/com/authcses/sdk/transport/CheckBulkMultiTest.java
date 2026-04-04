package com.authcses.sdk.transport;

import com.authcses.sdk.model.CheckResult;
import com.authcses.sdk.model.Consistency;
import com.authcses.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckBulkMultiTest {

    @Test
    void checkBulkMulti_duplicatePermissions_returnsAllResults() {
        var transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        "document", "doc-1", "viewer", "user", "alice", null),
                new SdkTransport.RelationshipUpdate(
                        SdkTransport.RelationshipUpdate.Operation.TOUCH,
                        "document", "doc-2", "viewer", "user", "bob", null)
        ));

        var items = List.of(
                new SdkTransport.BulkCheckItem("document", "doc-1", "viewer", "user", "alice"),
                new SdkTransport.BulkCheckItem("document", "doc-2", "viewer", "user", "bob")
        );

        List<CheckResult> results = transport.checkBulkMulti(items, Consistency.minimizeLatency());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
        assertThat(results.get(1).permissionship()).isEqualTo(Permissionship.HAS_PERMISSION);
    }
}
