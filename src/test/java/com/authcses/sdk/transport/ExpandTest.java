package com.authcses.sdk.transport;

import com.authcses.sdk.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.authcses.sdk.transport.SdkTransport.RelationshipUpdate.Operation.TOUCH;
import static org.assertj.core.api.Assertions.assertThat;

class ExpandTest {

    @Test
    void expand_returnsTreeFromInMemory() {
        var transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("document", "doc-1"), Relation.of("viewer"),
                        SubjectRef.of("user", "alice", null)),
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("document", "doc-1"), Relation.of("viewer"),
                        SubjectRef.of("user", "bob", null))
        ));
        var tree = transport.expand(ResourceRef.of("document", "doc-1"), Permission.of("viewer"), Consistency.minimizeLatency());
        assertThat(tree.operation()).isEqualTo("leaf");
        assertThat(tree.subjects()).containsExactlyInAnyOrder("user:alice", "user:bob");
    }

    @Test
    void expand_emptyRelation_returnsEmptyLeaf() {
        var transport = new InMemoryTransport();
        var tree = transport.expand(ResourceRef.of("document", "doc-1"), Permission.of("viewer"), Consistency.minimizeLatency());
        assertThat(tree.operation()).isEqualTo("leaf");
        assertThat(tree.subjects()).isEmpty();
    }

    @Test
    void expand_onlyMatchesRequestedRelation() {
        var transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("document", "doc-1"), Relation.of("viewer"),
                        SubjectRef.of("user", "alice", null)),
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("document", "doc-1"), Relation.of("editor"),
                        SubjectRef.of("user", "bob", null))
        ));
        var tree = transport.expand(ResourceRef.of("document", "doc-1"), Permission.of("viewer"), Consistency.minimizeLatency());
        assertThat(tree.operation()).isEqualTo("leaf");
        assertThat(tree.subjects()).containsExactly("user:alice");
    }

    @Test
    void expand_resourceMetadataPopulated() {
        var transport = new InMemoryTransport();
        transport.writeRelationships(List.of(
                new SdkTransport.RelationshipUpdate(TOUCH,
                        ResourceRef.of("document", "doc-1"), Relation.of("viewer"),
                        SubjectRef.of("user", "alice", null))
        ));
        var tree = transport.expand(ResourceRef.of("document", "doc-1"), Permission.of("viewer"), Consistency.minimizeLatency());
        assertThat(tree.resourceType()).isEqualTo("document");
        assertThat(tree.resourceId()).isEqualTo("doc-1");
        assertThat(tree.relation()).isEqualTo("viewer");
    }
}
