package com.authx.sdk;

import com.authx.sdk.model.CaveatRef;
import com.authx.sdk.model.WriteResult;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossResourceBatchBuilderModifierTest {

    @Test
    @SuppressWarnings("unchecked")
    void singleResourceModifiersApplyOnlyToMostRecentGrant() {
        SdkTransport transport = mock(SdkTransport.class);
        when(transport.writeRelationships(anyList())).thenReturn(new WriteResult("zed-token", 2));
        Instant expiresAt = Instant.parse("2026-09-01T00:00:00Z");
        CaveatRef caveat = new CaveatRef("during_shift", Map.of("shift", "day"));

        new CrossResourceBatchBuilder(transport)
                .on("document", "doc-1")
                .grant("viewer").to("user:alice")
                .grant("editor").to("user:bob")
                .withCaveat(caveat)
                .expiringAt(expiresAt)
                .commit();

        ArgumentCaptor<List<RelationshipUpdate>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(transport).writeRelationships(updatesCaptor.capture());
        List<RelationshipUpdate> updates = updatesCaptor.getValue();

        assertThat(updates).hasSize(2);
        assertThat(updates.get(0).caveat()).isNull();
        assertThat(updates.get(0).expiresAt()).isNull();
        assertThat(updates.get(1).caveat()).isEqualTo(caveat);
        assertThat(updates.get(1).expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiResourceModifiersApplyToEveryUpdateFromMostRecentGrantInOneBatch() {
        SdkTransport transport = mock(SdkTransport.class);
        when(transport.writeRelationships(anyList())).thenReturn(new WriteResult("zed-token", 5));
        Instant expiresAt = Instant.parse("2026-10-01T00:00:00Z");
        CaveatRef caveat = new CaveatRef("in_region", Map.of("region", "east"));

        new CrossResourceBatchBuilder(transport)
                .on("folder", "folder-1")
                .grant("owner").to("user:owner")
                .onAll("document", List.of("doc-1", "doc-2"))
                .grant("viewer").to("user:alice", "user:bob")
                .withCaveat(caveat)
                .expiringAt(expiresAt)
                .commit();

        ArgumentCaptor<List<RelationshipUpdate>> updatesCaptor = ArgumentCaptor.forClass(List.class);
        verify(transport).writeRelationships(updatesCaptor.capture());
        List<RelationshipUpdate> updates = updatesCaptor.getValue();

        assertThat(updates).hasSize(5);
        assertThat(updates.getFirst().caveat()).isNull();
        assertThat(updates.getFirst().expiresAt()).isNull();
        assertThat(updates.subList(1, 5)).allSatisfy(update -> {
            assertThat(update.caveat()).isEqualTo(caveat);
            assertThat(update.expiresAt()).isEqualTo(expiresAt);
        });
    }

    @Test
    void caveatCannotModifyARevokeOrFallBackToAnEarlierGrant() {
        CrossResourceBatchBuilder batch = new CrossResourceBatchBuilder(mock(SdkTransport.class));
        CrossResourceBatchBuilder.ResourceScope scope = batch
                .on("document", "doc-1")
                .grant("viewer").to("user:alice");

        assertThatThrownBy(() -> scope
                .revoke("viewer").from("user:bob")
                .withCaveat(new CaveatRef("during_shift", Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("applies only to grant(...).to(...) updates");
    }

    @Test
    void expirationCannotModifyAFannedRevoke() {
        CrossResourceBatchBuilder.MultiResourceScope scope =
                new CrossResourceBatchBuilder(mock(SdkTransport.class))
                        .onAll("document", List.of("doc-1", "doc-2"));

        assertThatThrownBy(() -> scope
                .revoke("viewer").from("user:alice")
                .expiringAt(Instant.parse("2026-10-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("applies only to grant(...).to(...) updates");
    }
}
