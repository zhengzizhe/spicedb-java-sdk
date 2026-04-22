package com.authx.sdk;

import com.authx.sdk.model.GrantResult;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-format identity for CrossResourceBatchBuilder's enum-typed
 * sub-relation overload (T011 of the schema-flat-descriptors spec).
 *
 * <p>Verifies that {@code .to(ResourceType, id, R subRel)} and
 * {@code .to(ResourceType, id, String subRelName)} produce the exact same
 * {@link RelationshipUpdate} sequence when submitted through
 * {@code writeRelationships}. Both the grant and the string path must
 * serialise the subject as {@code "group:eng#member"}.
 *
 * <p>Uses a tiny {@link CapturingTransport} extending {@link InMemoryTransport}
 * so we only override {@code writeRelationships} — the only method
 * CrossResourceBatchBuilder exercises — and inherit safe no-op defaults
 * for everything else.
 */
class CrossResourceBatchTypedSubRelTest {

    enum Rel implements com.authx.sdk.model.Relation.Named {
        MEMBER("member");
        private final String v;
        Rel(String v) { this.v = v; }
        @Override public String relationName() { return v; }
        @Override public List<SubjectType> subjectTypes() { return List.of(); }
    }

    enum Perm implements com.authx.sdk.model.Permission.Named {
        VIEW("view");
        private final String v;
        Perm(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    /** Captures every batch's updates on top of InMemoryTransport's no-op store. */
    static final class CapturingTransport extends InMemoryTransport {
        final List<RelationshipUpdate> captured = new ArrayList<>();
        @Override
        public GrantResult writeRelationships(List<RelationshipUpdate> updates) {
            captured.addAll(updates);
            return super.writeRelationships(updates);
        }
    }

    @Test
    void enumSubRelationAndStringSubRelationProduceSameUpdates() {
        ResourceType<Rel, Perm> group = ResourceType.of("group", Rel.class, Perm.class);

        var tr1 = new CapturingTransport();
        new CrossResourceBatchBuilder(tr1)
                .on("document", "d-1").grant("viewer").to(group, "eng", Rel.MEMBER)
                .commit();

        var tr2 = new CapturingTransport();
        new CrossResourceBatchBuilder(tr2)
                .on("document", "d-1").grant("viewer").to(group, "eng", "member")
                .commit();

        assertThat(tr1.captured).hasSize(1);
        assertThat(tr2.captured).hasSize(1);
        String fromEnum   = tr1.captured.get(0).subject().toRefString();
        String fromString = tr2.captured.get(0).subject().toRefString();
        assertThat(fromEnum)
                .isEqualTo(fromString)
                .isEqualTo("group:eng#member");
    }
}
