package com.authx.sdk;

import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.model.Tuple;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate.Operation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WriteFlow} — the unified flow that replaces
 * {@code GrantFlow} + {@code RevokeFlow}. Covers:
 * <ul>
 *   <li>Grant-only paths (subject overloads, typed sub-relation,
 *       wildcard, modifier scope)</li>
 *   <li>Revoke-only paths (mirrors)</li>
 *   <li>Mixed TOUCH + DELETE in one flow — the point of this merger</li>
 *   <li>Error states: mode/relation switching, modifier misuse</li>
 *   <li>WriteCompletion listener semantics (attached at .commit())</li>
 * </ul>
 */
class WriteFlowTest {

    // ---- local typed schema ----

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
    }

    private WriteFlow newFlow() {
        return new WriteFlow("document", "doc-1", transport, null);
    }

    private int remainingCount() {
        return transport.readRelationships(
                ResourceRef.of("document", "doc-1"), null, Consistency.full()).size();
    }

    private static RelationshipUpdate touch(String rt, String rid, String rel,
                                            String st, String sid, String sr) {
        return new RelationshipUpdate(
                Operation.TOUCH,
                ResourceRef.of(rt, rid),
                Relation.of(rel),
                SubjectRef.of(st, sid, sr));
    }

    // ══════════════════════════════════════════════════════════════════
    //  Grant-only
    // ══════════════════════════════════════════════════════════════════

    @Test
    void grant_singleRelation_multipleSubjects() {
        newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice")
                .to(User, "bob")
                .to(Group, "eng", GroupRel.MEMBER)
                .commit();

        assertThat(remainingCount()).isEqualTo(3);
    }

    @Test
    void grant_varargsSameType() {
        newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice", "bob", "carol")
                .commit();

        assertThat(remainingCount()).isEqualTo(3);
    }

    @Test
    void grant_iterableSubjects() {
        newFlow()
                .grant(DocRel.VIEWER)
                .to(User, List.of("alice", "bob", "carol"))
                .commit();

        assertThat(remainingCount()).isEqualTo(3);
    }

    @Test
    void grant_multipleRelationsPaired() {
        WriteFlow flow = newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .grant(DocRel.EDITOR).to(User, "bob")
                .grant(DocRel.ADMIN).to(Group, "eng", GroupRel.MEMBER);
        assertThat(flow.pendingCount()).isEqualTo(3);
        flow.commit();
        assertThat(remainingCount()).isEqualTo(3);
    }

    @Test
    void grant_typedSubRelation_producesCanonicalUserset() {
        List<SdkTransport.RelationshipUpdate> pending = newFlow()
                .grant(DocRel.VIEWER).to(Group, "eng", GroupRel.MEMBER)
                .pending();
        assertThat(pending.get(0).subject().type()).isEqualTo("group");
        assertThat(pending.get(0).subject().relation()).isEqualTo("member");
    }

    @Test
    void grant_wildcard() {
        List<SdkTransport.RelationshipUpdate> pending = newFlow()
                .grant(DocRel.VIEWER).toWildcard(User)
                .pending();
        assertThat(pending.get(0).subject().id()).isEqualTo("*");
    }

    @Test
    void grant_allPendingAreTouchOperation() {
        List<SdkTransport.RelationshipUpdate> pending = newFlow()
                .grant(DocRel.VIEWER).to(User, "alice").to(User, "bob")
                .pending();
        assertThat(pending).allMatch(u -> u.operation() == Operation.TOUCH);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Revoke-only
    // ══════════════════════════════════════════════════════════════════

    @Test
    void revoke_singleRelation_multipleSubjects() {
        // seed data
        transport.writeRelationships(List.of(
                touch("document", "doc-1", "viewer", "user", "alice", null),
                touch("document", "doc-1", "viewer", "user", "bob", null),
                touch("document", "doc-1", "viewer", "user", "carol", null)
        ));

        newFlow()
                .revoke(DocRel.VIEWER)
                .from(User, "alice")
                .from(User, "bob")
                .commit();

        assertThat(remainingCount()).isEqualTo(1);  // carol remains
    }

    @Test
    void revoke_wildcard() {
        List<SdkTransport.RelationshipUpdate> pending = newFlow()
                .revoke(DocRel.VIEWER).fromWildcard(User)
                .pending();
        assertThat(pending.get(0).subject().id()).isEqualTo("*");
        assertThat(pending.get(0).operation()).isEqualTo(Operation.DELETE);
    }

    @Test
    void revoke_allPendingAreDeleteOperation() {
        List<SdkTransport.RelationshipUpdate> pending = newFlow()
                .revoke(DocRel.VIEWER).from(User, "alice").from(User, "bob")
                .pending();
        assertThat(pending).allMatch(u -> u.operation() == Operation.DELETE);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Mixed grant + revoke — the main reason WriteFlow exists
    // ══════════════════════════════════════════════════════════════════

    @Test
    void mixed_changeRoleAtomically() {
        // seed: alice is EDITOR
        transport.writeRelationships(List.of(
                touch("document", "doc-1", "editor", "user", "alice", null)
        ));

        // Atomically: remove editor, add viewer — one RPC
        newFlow()
                .revoke(DocRel.EDITOR).from(User, "alice")
                .grant(DocRel.VIEWER).to(User, "alice")
                .commit();

        List<Tuple> remaining = transport.readRelationships(
                ResourceRef.of("document", "doc-1"), null, Consistency.full());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).relation()).isEqualTo("viewer");
    }

    @Test
    void mixed_pendingContainsMixedOperations() {
        List<SdkTransport.RelationshipUpdate> pending = newFlow()
                .revoke(DocRel.EDITOR).from(User, "alice")
                .grant(DocRel.VIEWER).to(User, "alice")
                .grant(DocRel.VIEWER).to(User, "bob")
                .revoke(DocRel.ADMIN).from(Group, "eng", GroupRel.MEMBER)
                .pending();

        assertThat(pending).hasSize(4);
        assertThat(pending.get(0).operation()).isEqualTo(Operation.DELETE);
        assertThat(pending.get(1).operation()).isEqualTo(Operation.TOUCH);
        assertThat(pending.get(2).operation()).isEqualTo(Operation.TOUCH);
        assertThat(pending.get(3).operation()).isEqualTo(Operation.DELETE);
    }

    @Test
    void mixed_freelySwitchModeBackAndForth() {
        List<SdkTransport.RelationshipUpdate> pending = newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .revoke(DocRel.EDITOR).from(User, "bob")
                .grant(DocRel.ADMIN).to(User, "carol")
                .revoke(DocRel.VIEWER).from(User, "dave")
                .pending();
        assertThat(pending).hasSize(4);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Modifier scope — only affects last TOUCH batch
    // ══════════════════════════════════════════════════════════════════

    @Test
    void withCaveat_onlyAffectsLastTouchBatch() {
        List<SdkTransport.RelationshipUpdate> pending = newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice")                     // no caveat
                .to(User, "bob", "carol")
                    .withCaveat("ip_check", Map.of("cidr", "10/8"))
                .to(User, "dave")                       // clean
                .pending();

        assertThat(pending.get(0).caveat()).isNull();
        assertThat(pending.get(1).caveat()).isNotNull();
        assertThat(pending.get(2).caveat()).isNotNull();
        assertThat(pending.get(3).caveat()).isNull();
    }

    @Test
    void expiringIn_onlyAffectsLastTouchBatch() {
        List<SdkTransport.RelationshipUpdate> pending = newFlow()
                .grant(DocRel.VIEWER)
                .to(User, "alice")
                .to(User, "bob").expiringIn(Duration.ofDays(7))
                .to(User, "carol")
                .pending();

        assertThat(pending.get(0).expiresAt()).isNull();
        assertThat(pending.get(1).expiresAt()).isNotNull();
        assertThat(pending.get(2).expiresAt()).isNull();
    }

    @Test
    void withCaveat_onRevokeBatch_throws() {
        assertThatThrownBy(() -> newFlow()
                .revoke(DocRel.VIEWER).from(User, "alice")
                .withCaveat("ip_check", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOUCH");
    }

    @Test
    void expiringIn_onRevokeBatch_throws() {
        assertThatThrownBy(() -> newFlow()
                .revoke(DocRel.VIEWER).from(User, "alice")
                .expiringIn(Duration.ofDays(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOUCH");
    }

    // ══════════════════════════════════════════════════════════════════
    //  Error states
    // ══════════════════════════════════════════════════════════════════

    @Test
    void to_beforeGrant_throws() {
        assertThatThrownBy(() -> newFlow().to(User, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".grant(...)");
    }

    @Test
    void from_beforeRevoke_throws() {
        assertThatThrownBy(() -> newFlow().from(User, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".revoke(...)");
    }

    @Test
    void to_inRevokeMode_throws() {
        assertThatThrownBy(() -> newFlow()
                .revoke(DocRel.VIEWER)
                .to(User, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GRANT mode");
    }

    @Test
    void from_inGrantMode_throws() {
        assertThatThrownBy(() -> newFlow()
                .grant(DocRel.VIEWER)
                .from(User, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REVOKE mode");
    }

    @Test
    void commit_emptyFlow_throws() {
        assertThatThrownBy(() -> newFlow().commit())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no pending");
    }

    @Test
    void commit_afterGrantWithoutTo_throws() {
        assertThatThrownBy(() -> newFlow().grant(DocRel.VIEWER).commit())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void useAfterCommit_throws() {
        WriteFlow flow = newFlow().grant(DocRel.VIEWER).to(User, "alice");
        flow.commit();
        assertThatThrownBy(() -> flow.to(User, "bob"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(flow::commit)
                .isInstanceOf(IllegalStateException.class);
    }

    // ══════════════════════════════════════════════════════════════════
    //  WriteCompletion listener (attached after commit)
    // ══════════════════════════════════════════════════════════════════

    @Test
    void commit_returnsCompletionWithDelegatedAccessors() {
        WriteCompletion c = newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .commit();
        assertThat(c.result()).isNotNull();
        assertThat(c.count()).isEqualTo(1);
    }

    @Test
    void listener_firesImmediatelyOnCallingThread() {
        AtomicInteger fired = new AtomicInteger(0);
        WriteCompletion completion = newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .commit();
        completion.listener(c -> fired.incrementAndGet());
        assertThat(fired.get()).isEqualTo(1);
    }

    @Test
    void listener_syncThrowPropagates() {
        WriteCompletion completion = newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .commit();
        assertThatThrownBy(() -> completion.listener(c -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class).hasMessage("boom");
    }

    @Test
    void listener_allowsOnlyOneCallbackPerCompletion() {
        WriteCompletion completion = newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .commit();
        completion.listener(c -> {});
        assertThatThrownBy(() -> completion.listener(c -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listener already registered");
    }

    @Test
    void listenerAsync_dispatchesToExecutor() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r); t.setName("wf-async"); return t;
        });
        try {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<String>();
            WriteCompletion completion = newFlow()
                    .grant(DocRel.VIEWER).to(User, "alice")
                    .commit();
            completion.listenerAsync(c -> {
                threadName.set(Thread.currentThread().getName());
                done.countDown();
            }, exec);
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).isEqualTo("wf-async");
        } finally { exec.shutdown(); }
    }

    @Test
    void listenerAsync_exceptionSwallowed() {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            WriteCompletion c = newFlow()
                    .grant(DocRel.VIEWER).to(User, "alice")
                    .commit();
            c.listenerAsync(x -> { throw new RuntimeException("boom"); }, exec);
            assertThat(c).isNotNull();
        } finally { exec.shutdown(); }
    }

    @Test
    void commitAsync_returnsCompletableFutureOfWriteCompletion() throws Exception {
        WriteCompletion c = newFlow()
                .grant(DocRel.VIEWER).to(User, "alice")
                .commitAsync()
                .get(2, TimeUnit.SECONDS);
        assertThat(c).isInstanceOf(WriteCompletion.class);
        assertThat(c.count()).isEqualTo(1);
    }

    @Test
    void commitAsync_emptyFlow_failsFuture() {
        CompletableFuture<WriteCompletion> future = newFlow().grant(DocRel.VIEWER).commitAsync();
        assertThat(future).isCompletedExceptionally();
    }
}
