package com.authx.sdk;

import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link GrantFlow}. Covers spec 2026-04-22-grant-revoke-flow-api:
 * <ul>
 *   <li>Single relation multiple subjects (same type / mixed type)</li>
 *   <li>Multiple (relation, subject) pairs via re-grant</li>
 *   <li>Typed sub-relation / sub-permission / wildcard</li>
 *   <li>Modifier scope: caveat / expiration affect only the most recent batch</li>
 *   <li>Error states: no-grant, empty commit, re-commit, etc.</li>
 * </ul>
 */
class GrantFlowTest {

    // ---- local throwaway typed schema (matches the test-app style) ----

    enum DocRel implements Relation.Named {
        VIEWER("viewer"), EDITOR("editor"), ADMIN("admin");
        private final String v;
        DocRel(String v) { this.v = v; }
        @Override public String relationName() { return v; }
        @Override public List<SubjectType> subjectTypes() { return List.of(); }
    }

    enum DocPerm implements Permission.Named {
        VIEW("view"), EDIT("edit");
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

    enum DeptRel implements Relation.Named {
        MEMBER("member");
        private final String v;
        DeptRel(String v) { this.v = v; }
        @Override public String relationName() { return v; }
        @Override public List<SubjectType> subjectTypes() { return List.of(); }
    }

    enum DeptPerm implements Permission.Named {
        ALL_MEMBERS("all_members");
        private final String v;
        DeptPerm(String v) { this.v = v; }
        @Override public String permissionName() { return v; }
    }

    static final ResourceType<DocRel, DocPerm> Document =
            ResourceType.of("document", DocRel.class, DocPerm.class);
    static final ResourceType<UserRel, UserPerm> User =
            ResourceType.of("user", UserRel.class, UserPerm.class);
    static final ResourceType<GroupRel, GroupPerm> Group =
            ResourceType.of("group", GroupRel.class, GroupPerm.class);
    static final ResourceType<DeptRel, DeptPerm> Department =
            ResourceType.of("department", DeptRel.class, DeptPerm.class);

    private InMemoryTransport transport;

    @BeforeEach
    void setUp() {
        transport = new InMemoryTransport();
    }

    private GrantFlow newFlow() {
        return new GrantFlow("document", "doc-1", transport, null);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Normal paths
    // ──────────────────────────────────────────────────────────────────

    @Test
    void singleRelation_multipleSubjects_oneCommit() {
        var result = newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice")
                .to(User, "bob")
                .to(Group, "eng", GroupRel.MEMBER)
                .commit();

        assertThat(result).isNotNull();
        assertThat(transport.readRelationships(
                com.authx.sdk.model.ResourceRef.of("document", "doc-1"),
                Relation.of("viewer"),
                com.authx.sdk.model.Consistency.full()))
                .hasSize(3);
    }

    @Test
    void varargsSubjects_sameType() {
        newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice", "bob", "carol")
                .commit();

        assertThat(transport.readRelationships(
                com.authx.sdk.model.ResourceRef.of("document", "doc-1"),
                Relation.of("viewer"),
                com.authx.sdk.model.Consistency.full()))
                .hasSize(3);
    }

    @Test
    void iterableSubjects_sameType() {
        newFlow()
                .grant(DocRel.VIEWER)
                .to(User, List.of("alice", "bob", "carol"))
                .commit();

        assertThat(transport.readRelationships(
                com.authx.sdk.model.ResourceRef.of("document", "doc-1"),
                Relation.of("viewer"),
                com.authx.sdk.model.Consistency.full()))
                .hasSize(3);
    }

    @Test
    void multipleRelations_pairedWithSubjects() {
        var flow = newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .grant(DocRel.EDITOR).to(User, "bob")
                .grant(DocRel.ADMIN).to(Group, "eng", GroupRel.MEMBER);

        assertThat(flow.pendingCount()).isEqualTo(3);
        flow.commit();

        var all = transport.readRelationships(
                com.authx.sdk.model.ResourceRef.of("document", "doc-1"),
                null,
                com.authx.sdk.model.Consistency.full());
        assertThat(all).hasSize(3);
    }

    @Test
    void typedSubRelation_producesCanonicalUserset() {
        var pending = newFlow()
                .grant(DocRel.VIEWER)
                .to(Group, "eng", GroupRel.MEMBER)
                .pending();

        assertThat(pending).hasSize(1);
        SubjectRef subj = pending.get(0).subject();
        assertThat(subj.type()).isEqualTo("group");
        assertThat(subj.id()).isEqualTo("eng");
        assertThat(subj.relation()).isEqualTo("member");
    }

    @Test
    void typedSubPermission_producesCanonicalUserset() {
        var pending = newFlow()
                .grant(DocRel.VIEWER)
                .to(Department, "hq", DeptPerm.ALL_MEMBERS)
                .pending();

        assertThat(pending).hasSize(1);
        SubjectRef subj = pending.get(0).subject();
        assertThat(subj.type()).isEqualTo("department");
        assertThat(subj.id()).isEqualTo("hq");
        assertThat(subj.relation()).isEqualTo("all_members");
    }

    @Test
    void wildcardSubject() {
        var pending = newFlow()
                .grant(DocRel.VIEWER)
                .toWildcard(User)
                .pending();

        SubjectRef subj = pending.get(0).subject();
        assertThat(subj.type()).isEqualTo("user");
        assertThat(subj.id()).isEqualTo("*");
        assertThat(subj.relation()).isNull();
    }

    @Test
    void canonicalStringSubject() {
        var pending = newFlow()
                .grant(DocRel.VIEWER)
                .to("user:alice", "group:eng#member")
                .pending();

        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).subject().toRefString()).isEqualTo("user:alice");
        assertThat(pending.get(1).subject().toRefString()).isEqualTo("group:eng#member");
    }

    // ──────────────────────────────────────────────────────────────────
    //  Modifier scope (the "方案 A" semantic)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void withCaveat_onlyAffectsLastBatch() {
        var pending = newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice")                                   // no caveat
                .to(User, "bob", "carol")
                    .withCaveat("ip_check", Map.of("cidr", "10/8"))  // caveat applied here
                .to(User, "dave")                                    // clean again
                .pending();

        assertThat(pending).hasSize(4);
        assertThat(pending.get(0).caveat()).isNull();            // alice
        assertThat(pending.get(1).caveat()).isNotNull();         // bob
        assertThat(pending.get(1).caveat().name()).isEqualTo("ip_check");
        assertThat(pending.get(2).caveat()).isNotNull();         // carol (same batch as bob)
        assertThat(pending.get(3).caveat()).isNull();            // dave
    }

    @Test
    void expiringIn_onlyAffectsLastBatch() {
        var pending = newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice")
                .to(User, "bob")
                    .expiringIn(Duration.ofDays(7))
                .to(User, "carol")
                .pending();

        assertThat(pending.get(0).expiresAt()).isNull();
        assertThat(pending.get(1).expiresAt()).isNotNull();
        assertThat(pending.get(2).expiresAt()).isNull();
    }

    @Test
    void expiringAt_overridesExpiringIn_onSameBatch() {
        Instant at = Instant.now().plus(Duration.ofDays(30));
        var pending = newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice")
                    .expiringIn(Duration.ofDays(7))
                    .expiringAt(at)
                .pending();

        assertThat(pending.get(0).expiresAt()).isEqualTo(at);
    }

    @Test
    void caveatAndExpiration_stackOnSameBatch() {
        var pending = newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice")
                    .withCaveat("ip_check", Map.of("cidr", "10/8"))
                    .expiringIn(Duration.ofDays(7))
                .pending();

        assertThat(pending.get(0).caveat()).isNotNull();
        assertThat(pending.get(0).expiresAt()).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Error states
    // ──────────────────────────────────────────────────────────────────

    @Test
    void to_beforeGrant_throws() {
        assertThatThrownBy(() -> newFlow().to(User, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".grant(...)");
    }

    @Test
    void withCaveat_beforeTo_throws() {
        assertThatThrownBy(() -> newFlow()
                .grant(DocRel.VIEWER)
                .withCaveat("ip_check", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preceding .to(...)");
    }

    @Test
    void expiringIn_beforeTo_throws() {
        assertThatThrownBy(() -> newFlow()
                .grant(DocRel.VIEWER)
                .expiringIn(Duration.ofDays(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preceding .to(...)");
    }

    @Test
    void commit_noPending_throws() {
        assertThatThrownBy(() -> newFlow().grant(DocRel.VIEWER).commit())
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
        var flow = newFlow().grant(DocRel.VIEWER).to(User, "alice");
        flow.commit();

        assertThatThrownBy(() -> flow.to(User, "bob"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already committed");
        assertThatThrownBy(() -> flow.grant(DocRel.EDITOR))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(flow::commit)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commitAsync_noPending_failsFuture() {
        var future = newFlow().grant(DocRel.VIEWER).commitAsync();
        assertThat(future).isCompletedExceptionally();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Wire-level invariants (RelationshipUpdate content)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void allPendingUpdates_areTouchOperations() {
        var pending = newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice")
                .to(User, "bob")
                .pending();

        assertThat(pending).allMatch(u -> u.operation() == Operation.TOUCH);
    }

    @Test
    void allPendingUpdates_bindToSingleResource() {
        var pending = newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .grant(DocRel.EDITOR).to(User, "bob")
                .pending();

        assertThat(pending).allMatch(u ->
                u.resource().type().equals("document")
                && u.resource().id().equals("doc-1"));
    }

    // ──────────────────────────────────────────────────────────────────
    //  GrantCompletion listeners — hang off commit()
    // ──────────────────────────────────────────────────────────────────

    @Test
    void commit_returnsCompletionWithDelegatedAccessors() {
        GrantCompletion c = newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice")
                .commit();
        assertThat(c.result()).isNotNull();
        assertThat(c.count()).isEqualTo(c.result().count());
    }

    @Test
    void listener_firesImmediatelyOnCallingThread() {
        var fired = new java.util.concurrent.atomic.AtomicInteger(0);
        var threadName = new java.util.concurrent.atomic.AtomicReference<String>();
        newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .commit()
                .listener(r -> {
                    fired.incrementAndGet();
                    threadName.set(Thread.currentThread().getName());
                });

        assertThat(fired.get()).isEqualTo(1);
        assertThat(threadName.get()).isEqualTo(Thread.currentThread().getName());
    }

    @Test
    void listener_multipleFireInOrder() {
        var order = new java.util.ArrayList<Integer>();
        newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .commit()
                .listener(r -> order.add(1))
                .listener(r -> order.add(2))
                .listener(r -> order.add(3));

        assertThat(order).containsExactly(1, 2, 3);
    }

    @Test
    void listener_syncThrowStopsSubsequentSyncListeners() {
        var fired = new java.util.ArrayList<Integer>();
        assertThatThrownBy(() -> newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .commit()
                .listener(r -> fired.add(1))
                .listener(r -> { throw new RuntimeException("boom"); })
                .listener(r -> fired.add(3)))      // must not fire
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        assertThat(fired).containsExactly(1);
    }

    @Test
    void listenerAsync_dispatchesToExecutor() throws Exception {
        var exec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r);
            t.setName("listener-async-pool");
            return t;
        });
        try {
            var done = new java.util.concurrent.CountDownLatch(1);
            var threadName = new java.util.concurrent.atomic.AtomicReference<String>();

            newFlow()
                    .grant(DocRel.VIEWER).to(User, "alice")
                    .commit()
                    .listenerAsync(r -> {
                        threadName.set(Thread.currentThread().getName());
                        done.countDown();
                    }, exec);

            assertThat(done.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).isEqualTo("listener-async-pool");
        } finally {
            exec.shutdown();
        }
    }

    @Test
    void listenerAsync_exceptionSwallowed() {
        var exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // completion chain must not throw; async listener's exception is logged and swallowed.
            var c = newFlow()
                    .grant(DocRel.VIEWER).to(User, "alice")
                    .commit()
                    .listenerAsync(r -> { throw new RuntimeException("async-boom"); }, exec);
            assertThat(c).isNotNull();
        } finally {
            exec.shutdown();
        }
    }

    @Test
    void listener_null_throws() {
        assertThatThrownBy(() -> newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .commit()
                .listener(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void listenerAsync_nullCallback_throws() {
        var exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> newFlow()
                    .grant(DocRel.VIEWER).to(User, "alice")
                    .commit()
                    .listenerAsync(null, exec))
                    .isInstanceOf(NullPointerException.class);
        } finally { exec.shutdown(); }
    }

    @Test
    void listenerAsync_nullExecutor_throws() {
        assertThatThrownBy(() -> newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .commit()
                .listenerAsync(r -> {}, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Wire-level sanity
    // ──────────────────────────────────────────────────────────────────

    @Test
    void relationSwitching_tracksCurrentRelation() {
        var pending = newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .grant(DocRel.EDITOR).to(User, "bob")
                .grant(DocRel.ADMIN).to(User, "carol")
                .pending();

        assertThat(pending.get(0).relation().name()).isEqualTo("viewer");
        assertThat(pending.get(1).relation().name()).isEqualTo("editor");
        assertThat(pending.get(2).relation().name()).isEqualTo("admin");
    }
}
