package com.authx.sdk;

import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RevokeFlow}. Mirrors {@link GrantFlowTest} structure
 * but covers deletion semantics.
 */
class RevokeFlowTest {

    // ---- local throwaway typed schema ----

    enum DocRel implements Relation.Named {
        VIEWER("viewer"), EDITOR("editor"), ADMIN("admin");
        private final String v;
        DocRel(String v) { this.v = v; }
        @Override public String relationName() { return v; }
        @Override public List<SubjectType> subjectTypes() { return List.of(); }
    }

    enum DocPerm implements Permission.Named {
        VIEW("view");
        private final String v;
        DocPerm(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    enum UserRel implements Relation.Named {
        SELF("self");
        private final String v;
        UserRel(String v) { this.v = v; }
        @Override public String relationName() { return v; }
        @Override public List<SubjectType> subjectTypes() { return List.of(); }
    }

    enum UserPerm implements Permission.Named {
        IDENTITY("identity");
        private final String v;
        UserPerm(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    enum GroupRel implements Relation.Named {
        MEMBER("member");
        private final String v;
        GroupRel(String v) { this.v = v; }
        @Override public String relationName() { return v; }
        @Override public List<SubjectType> subjectTypes() { return List.of(); }
    }

    enum GroupPerm implements Permission.Named {
        ACCESS("access");
        private final String v;
        GroupPerm(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    static final ResourceType<DocRel, DocPerm> Document =
            ResourceType.of("document", DocRel.class, DocPerm.class);
    static final ResourceType<UserRel, UserPerm> User =
            ResourceType.of("user", UserRel.class, UserPerm.class);
    static final ResourceType<GroupRel, GroupPerm> Group =
            ResourceType.of("group", GroupRel.class, GroupPerm.class);

    private InMemoryTransport transport;

    @BeforeEach
    void setUp() {
        transport = new InMemoryTransport();
        // Seed data so revoke has something to delete
        transport.writeRelationships(List.of(
                touch("document", "doc-1", "viewer", "user", "alice", null),
                touch("document", "doc-1", "viewer", "user", "bob", null),
                touch("document", "doc-1", "viewer", "user", "carol", null),
                touch("document", "doc-1", "editor", "user", "dave", null),
                touch("document", "doc-1", "admin", "group", "eng", "member")
        ));
    }

    private static RelationshipUpdate touch(String rt, String rid, String rel,
                                            String st, String sid, String sr) {
        return new RelationshipUpdate(
                Operation.TOUCH,
                ResourceRef.of(rt, rid),
                Relation.of(rel),
                SubjectRef.of(st, sid, sr));
    }

    private RevokeFlow newFlow() {
        return new RevokeFlow("document", "doc-1", transport, null);
    }

    private int remainingCount() {
        return transport.readRelationships(
                ResourceRef.of("document", "doc-1"), null, Consistency.full()).size();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Normal paths
    // ──────────────────────────────────────────────────────────────────

    @Test
    void singleRelation_multipleSubjects_oneCommit() {
        newFlow()
                .revoke(DocRel.VIEWER)
                .from(User, "alice")
                .from(User, "bob")
                .commit();

        assertThat(remainingCount()).isEqualTo(3);   // carol, dave, eng#member
    }

    @Test
    void varargsSubjects() {
        newFlow()
                .revoke(DocRel.VIEWER)
                .from(User, "alice", "bob", "carol")
                .commit();

        assertThat(remainingCount()).isEqualTo(2);   // dave, eng#member
    }

    @Test
    void multipleRelations_pairedWithSubjects() {
        newFlow()
                .revoke(DocRel.VIEWER).from(User, "alice")
                .revoke(DocRel.EDITOR).from(User, "dave")
                .revoke(DocRel.ADMIN).from(Group, "eng", GroupRel.MEMBER)
                .commit();

        assertThat(remainingCount()).isEqualTo(2);   // bob, carol still viewers
    }

    @Test
    void typedSubRelation_producesCanonicalUserset() {
        var pending = newFlow()
                .revoke(DocRel.ADMIN)
                .from(Group, "eng", GroupRel.MEMBER)
                .pending();

        assertThat(pending).hasSize(1);
        SubjectRef subj = pending.get(0).subject();
        assertThat(subj.type()).isEqualTo("group");
        assertThat(subj.id()).isEqualTo("eng");
        assertThat(subj.relation()).isEqualTo("member");
    }

    @Test
    void wildcardSubject() {
        var pending = newFlow()
                .revoke(DocRel.VIEWER)
                .fromWildcard(User)
                .pending();

        SubjectRef subj = pending.get(0).subject();
        assertThat(subj.type()).isEqualTo("user");
        assertThat(subj.id()).isEqualTo("*");
        assertThat(subj.relation()).isNull();
    }

    @Test
    void canonicalStringSubject() {
        var pending = newFlow()
                .revoke(DocRel.VIEWER)
                .from("user:alice", "group:eng#member")
                .pending();

        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).subject().toRefString()).isEqualTo("user:alice");
        assertThat(pending.get(1).subject().toRefString()).isEqualTo("group:eng#member");
    }

    @Test
    void iterableSubjects() {
        newFlow()
                .revoke(DocRel.VIEWER)
                .from(User, List.of("alice", "bob"))
                .commit();

        assertThat(remainingCount()).isEqualTo(3);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Error states
    // ──────────────────────────────────────────────────────────────────

    @Test
    void from_beforeRevoke_throws() {
        assertThatThrownBy(() -> newFlow().from(User, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".revoke(...)");
    }

    @Test
    void commit_noPending_throws() {
        assertThatThrownBy(() -> newFlow().revoke(DocRel.VIEWER).commit())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no pending updates");
    }

    @Test
    void commit_emptyFlow_throws() {
        assertThatThrownBy(() -> newFlow().commit())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void useAfterCommit_throws() {
        var flow = newFlow().revoke(DocRel.VIEWER).from(User, "alice");
        flow.commit();

        assertThatThrownBy(() -> flow.from(User, "bob"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already committed");
        assertThatThrownBy(() -> flow.revoke(DocRel.EDITOR))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(flow::commit)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commitAsync_noPending_failsFuture() {
        var future = newFlow().revoke(DocRel.VIEWER).commitAsync();
        assertThat(future).isCompletedExceptionally();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Wire-level invariants
    // ──────────────────────────────────────────────────────────────────

    @Test
    void allPendingUpdates_areDeleteOperations() {
        var pending = newFlow()
                .revoke(DocRel.VIEWER)
                .from(User, "alice")
                .from(User, "bob")
                .pending();

        assertThat(pending).allMatch(u -> u.operation() == Operation.DELETE);
    }

    @Test
    void allPendingUpdates_haveNoCaveatOrExpiration() {
        // RevokeFlow does not expose caveat/expiration modifiers — sanity
        // check that built updates never carry them.
        var pending = newFlow()
                .revoke(DocRel.VIEWER)
                .from(User, "alice")
                .pending();

        assertThat(pending.get(0).caveat()).isNull();
        assertThat(pending.get(0).expiresAt()).isNull();
    }

    @Test
    void pendingCount_tracksAccumulation() {
        var flow = newFlow().revoke(DocRel.VIEWER);
        assertThat(flow.pendingCount()).isZero();

        flow.from(User, "alice");
        assertThat(flow.pendingCount()).isEqualTo(1);

        flow.from(User, "bob", "carol");
        assertThat(flow.pendingCount()).isEqualTo(3);
    }
}
