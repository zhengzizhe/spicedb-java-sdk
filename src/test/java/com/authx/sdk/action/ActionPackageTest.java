package com.authx.sdk.action;

import com.authx.sdk.model.*;
import com.authx.sdk.transport.InMemoryTransport;
import com.authx.sdk.transport.SdkTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the action/ package — CheckAction, CheckAllAction,
 * GrantAction, RevokeAction, RevokeAllAction, BatchBuilder,
 * BatchGrantAction, BatchRevokeAction, RelationQuery, SubjectQuery,
 * and WhoBuilder.
 *
 * All tests use InMemoryTransport (no real gRPC).
 */
class ActionPackageTest {

    private static final String RES_TYPE = "document";
    private static final String RES_ID = "doc-1";
    private static final String DEFAULT_SUBJECT = "user";
    private static final Executor SYNC_EXEC = Runnable::run;

    private InMemoryTransport transport;

    @BeforeEach
    void setUp() {
        transport = new InMemoryTransport();
    }

    // ================================================================
    //  GrantAction
    // ================================================================

    @Nested
    class GrantActionTests {

        @Test
        void grant_singleUser() {
            var grant = new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"});
            GrantResult result = grant.to("user:alice");

            assertThat(result.count()).isEqualTo(1);
            assertThat(result.zedToken()).isNotNull();
            assertThat(transport.size()).isEqualTo(1);
        }

        @Test
        void grant_multipleUsers() {
            var grant = new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"});
            GrantResult result = grant.to("user:alice", "user:bob", "user:carol");

            assertThat(result.count()).isEqualTo(3);
            assertThat(transport.size()).isEqualTo(3);
        }

        @Test
        void grant_multipleRelations_multipleUsers() {
            var grant = new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor", "viewer"});
            GrantResult result = grant.to("user:alice", "user:bob");

            // 2 relations x 2 users = 4 updates
            assertThat(result.count()).isEqualTo(4);
            assertThat(transport.size()).isEqualTo(4);
        }

        @Test
        void grant_toCollection() {
            var grant = new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"});
            GrantResult result = grant.to("user:alice", "user:bob");

            assertThat(result.count()).isEqualTo(2);
        }

        @Test
        void grant_toSubjects_parsesRef() {
            var grant = new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"});
            grant.to("department:eng#member");

            var tuples = transport.allTuples();
            assertThat(tuples).hasSize(1);
            var tuple = tuples.iterator().next();
            assertThat(tuple.subjectType()).isEqualTo("department");
            assertThat(tuple.subjectId()).isEqualTo("eng");
            assertThat(tuple.subjectRelation()).isEqualTo("member");
        }

        @Test
        void grant_toSubjects_collection() {
            var grant = new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"});
            grant.to("user:alice", "group:eng#member");

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void grant_withCaveat() {
            var grant = new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"});
            grant.withCaveat("ip_range", Map.of("allowed_cidr", "10.0.0.0/8"));
            GrantResult result = grant.to("user:alice");

            assertThat(result.count()).isEqualTo(1);
        }
    }

    // ================================================================
    //  CheckAction
    // ================================================================

    @Nested
    class CheckActionTests {

        @BeforeEach
        void grantSome() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"})
                    .to("user:alice");
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"})
                    .to("user:alice", "user:bob");
        }

        @Test
        void check_by_allowed() {
            var check = new CheckAction(RES_TYPE, RES_ID, transport, SYNC_EXEC, new String[]{"editor"});
            CheckResult result = check.by("user:alice");

            assertThat(result.hasPermission()).isTrue();
        }

        @Test
        void check_by_denied() {
            var check = new CheckAction(RES_TYPE, RES_ID, transport, SYNC_EXEC, new String[]{"editor"});
            CheckResult result = check.by("user:bob");

            assertThat(result.hasPermission()).isFalse();
        }

        @Test
        void check_withConsistency() {
            var check = new CheckAction(RES_TYPE, RES_ID, transport, SYNC_EXEC, new String[]{"editor"});
            check.withConsistency(Consistency.full());
            CheckResult result = check.by("user:alice");

            assertThat(result.hasPermission()).isTrue();
        }

        @Test
        void check_byAsync_returns_future() {
            var check = new CheckAction(RES_TYPE, RES_ID, transport, SYNC_EXEC, new String[]{"editor"});
            CompletableFuture<CheckResult> future = check.byAsync("user:alice");

            assertThat(future.join().hasPermission()).isTrue();
        }

        @Test
        void check_byAll_varargs() {
            var check = new CheckAction(RES_TYPE, RES_ID, transport, SYNC_EXEC, new String[]{"viewer"});
            BulkCheckResult result = check.byAll("user:alice", "user:bob", "user:carol");

            assertThat(result.get("alice").hasPermission()).isTrue();
            assertThat(result.get("bob").hasPermission()).isTrue();
            assertThat(result.get("carol").hasPermission()).isFalse();
            assertThat(result.allowedCount()).isEqualTo(2);
        }

        @Test
        void check_byAll_collection() {
            var check = new CheckAction(RES_TYPE, RES_ID, transport, SYNC_EXEC, new String[]{"editor"});
            BulkCheckResult result = check.byAll("user:alice", "user:bob");

            assertThat(result.get("alice").hasPermission()).isTrue();
            assertThat(result.get("bob").hasPermission()).isFalse();
        }
    }

    // ================================================================
    //  CheckAllAction
    // ================================================================

    @Nested
    class CheckAllActionTests {

        @BeforeEach
        void grantSome() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"})
                    .to("user:alice");
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"})
                    .to("user:alice", "user:bob");
        }

        @Test
        void checkAll_by_singleUser() {
            var action = new CheckAllAction(RES_TYPE, RES_ID, transport,                     new String[]{"editor", "viewer", "owner"});
            PermissionSet perms = action.by("user:alice");

            assertThat(perms.can("editor")).isTrue();
            assertThat(perms.can("viewer")).isTrue();
            assertThat(perms.can("owner")).isFalse();
            assertThat(perms.allowed()).containsExactlyInAnyOrder("editor", "viewer");
        }

        @Test
        void checkAll_byAll_matrix() {
            var action = new CheckAllAction(RES_TYPE, RES_ID, transport,                     new String[]{"editor", "viewer"});
            PermissionMatrix matrix = action.byAll("user:alice", "user:bob");

            assertThat(matrix.get("user:alice").can("editor")).isTrue();
            assertThat(matrix.get("user:alice").can("viewer")).isTrue();
            assertThat(matrix.get("user:bob").can("editor")).isFalse();
            assertThat(matrix.get("user:bob").can("viewer")).isTrue();
        }

        @Test
        void checkAll_byAll_collection() {
            var action = new CheckAllAction(RES_TYPE, RES_ID, transport,                     new String[]{"editor", "viewer"});
            PermissionMatrix matrix = action.byAll("user:alice", "user:bob");

            assertThat(matrix.get("user:alice")).isNotNull();
            assertThat(matrix.get("user:bob")).isNotNull();
        }

        @Test
        void checkAll_withConsistency() {
            var action = new CheckAllAction(RES_TYPE, RES_ID, transport,                     new String[]{"editor"});
            action.withConsistency(Consistency.full());
            PermissionSet perms = action.by("user:alice");

            assertThat(perms.can("editor")).isTrue();
        }
    }

    // ================================================================
    //  RevokeAction
    // ================================================================

    @Nested
    class RevokeActionTests {

        @BeforeEach
        void grantSome() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"})
                    .to("user:alice", "user:bob");
        }

        @Test
        void revoke_from_singleUser() {
            var revoke = new RevokeAction(RES_TYPE, RES_ID, transport, new String[]{"editor"});
            RevokeResult result = revoke.from("user:alice");

            assertThat(result.count()).isEqualTo(1);
            // alice removed, bob still there
            assertThat(transport.size()).isEqualTo(1);
        }

        @Test
        void revoke_from_multipleUsers() {
            var revoke = new RevokeAction(RES_TYPE, RES_ID, transport, new String[]{"editor"});
            RevokeResult result = revoke.from("user:alice", "user:bob");

            assertThat(result.count()).isEqualTo(2);
            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void revoke_from_collection() {
            var revoke = new RevokeAction(RES_TYPE, RES_ID, transport, new String[]{"editor"});
            RevokeResult result = revoke.from("user:alice");

            assertThat(result.count()).isEqualTo(1);
        }

        @Test
        void revoke_fromSubjects() {
            // Grant a subject ref first
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"})
                    .to("department:eng#member");

            var revoke = new RevokeAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"});
            revoke.from("department:eng#member");

            // Only the two editor grants should remain
            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void revoke_fromSubjects_collection() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"})
                    .to("group:eng#member");

            var revoke = new RevokeAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"});
            revoke.from("group:eng#member");

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void revoke_multipleRelations() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"})
                    .to("user:alice");
            var revoke = new RevokeAction(RES_TYPE, RES_ID, transport, new String[]{"editor", "viewer"});
            revoke.from("user:alice");

            // Both editor and viewer removed for alice; bob's editor remains
            assertThat(transport.size()).isEqualTo(1);
        }
    }

    // ================================================================
    //  RevokeAllAction
    // ================================================================

    @Nested
    class RevokeAllActionTests {

        @BeforeEach
        void grantSome() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"}).to("user:alice");
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"}).to("user:alice");
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"owner"}).to("user:alice");
        }

        @Test
        void revokeAll_noRelations_removesAll() {
            var action = new RevokeAllAction(RES_TYPE, RES_ID, transport, null);
            RevokeResult result = action.from("user:alice");

            assertThat(result.count()).isEqualTo(3);
            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void revokeAll_emptyRelations_removesAll() {
            var action = new RevokeAllAction(RES_TYPE, RES_ID, transport, new String[]{});
            RevokeResult result = action.from("user:alice");

            assertThat(result.count()).isEqualTo(3);
            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void revokeAll_specificRelations_removesOnlyMatching() {
            var action = new RevokeAllAction(RES_TYPE, RES_ID, transport,
                    new String[]{"editor", "viewer"});
            RevokeResult result = action.from("user:alice");

            assertThat(result.count()).isEqualTo(2);
            // owner remains
            assertThat(transport.size()).isEqualTo(1);
        }

        @Test
        void revokeAll_multipleUsers() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"}).to("user:bob");
            var action = new RevokeAllAction(RES_TYPE, RES_ID, transport, null);
            action.from("user:alice", "user:bob");

            assertThat(transport.size()).isEqualTo(0);
        }
    }

    // ================================================================
    //  BatchBuilder + BatchGrantAction + BatchRevokeAction
    // ================================================================

    @Nested
    class BatchBuilderTests {

        @Test
        void batch_grant_and_revoke_atomic() {
            // Pre-grant
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"owner"}).to("user:alice");

            BatchResult result = new BatchBuilder(RES_TYPE, RES_ID, transport)
                    .grant("editor").to("user:bob")
                    .revoke("owner").from("user:alice")
                    .grant("viewer").to("user:carol", "user:dave")
                    .execute();

            assertThat(result.zedToken()).isNotNull();
            // Verify: alice lost owner, bob has editor, carol+dave have viewer
            var check = new CheckAction(RES_TYPE, RES_ID, transport, SYNC_EXEC, new String[]{"owner"});
            assertThat(check.by("user:alice").hasPermission()).isFalse();

            check = new CheckAction(RES_TYPE, RES_ID, transport, SYNC_EXEC, new String[]{"editor"});
            assertThat(check.by("user:bob").hasPermission()).isTrue();

            check = new CheckAction(RES_TYPE, RES_ID, transport, SYNC_EXEC, new String[]{"viewer"});
            assertThat(check.by("user:carol").hasPermission()).isTrue();
            assertThat(check.by("user:dave").hasPermission()).isTrue();
        }

        @Test
        void batch_empty_execute() {
            BatchResult result = new BatchBuilder(RES_TYPE, RES_ID, transport).execute();

            assertThat(result.zedToken()).isNull();
        }

        @Test
        void batchGrant_toSubjects() {
            BatchResult result = new BatchBuilder(RES_TYPE, RES_ID, transport)
                    .grant("viewer").to("department:eng#member")
                    .execute();

            assertThat(result.zedToken()).isNotNull();
            assertThat(transport.size()).isEqualTo(1);
        }

        @Test
        void batchRevoke_fromSubjects() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"})
                    .to("group:eng#member");

            new BatchBuilder(RES_TYPE, RES_ID, transport)
                    .revoke("viewer").from("group:eng#member")
                    .execute();

            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void batch_grant_multipleRelations() {
            new BatchBuilder(RES_TYPE, RES_ID, transport)
                    .grant("editor", "viewer").to("user:alice")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void batch_revoke_collection() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"})
                    .to("user:alice", "user:bob");

            new BatchBuilder(RES_TYPE, RES_ID, transport)
                    .revoke("editor").from("user:alice", "user:bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void batch_grant_collection() {
            new BatchBuilder(RES_TYPE, RES_ID, transport)
                    .grant("viewer").to("user:alice", "user:bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }
    }

    // ================================================================
    //  RelationQuery
    // ================================================================

    @Nested
    class RelationQueryTests {

        @BeforeEach
        void grantSome() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"}).to("user:alice");
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"}).to("user:bob", "user:carol");
        }

        @Test
        void fetch_all() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{});
            List<Tuple> tuples = query.fetch();

            assertThat(tuples).hasSize(3);
        }

        @Test
        void fetch_nullRelations() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, null);
            List<Tuple> tuples = query.fetch();

            assertThat(tuples).hasSize(3);
        }

        @Test
        void fetch_filtered() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{"editor"});
            List<Tuple> tuples = query.fetch();

            assertThat(tuples).hasSize(1);
            assertThat(tuples.getFirst().subjectId()).isEqualTo("alice");
        }

        @Test
        void fetch_multipleRelations() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{"editor", "viewer"});
            List<Tuple> tuples = query.fetch();

            assertThat(tuples).hasSize(3);
        }

        @Test
        void fetchSet() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{});
            Set<Tuple> set = query.fetchSet();

            assertThat(set).hasSize(3);
        }

        @Test
        void fetchCount() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{});
            assertThat(query.fetchCount()).isEqualTo(3);
        }

        @Test
        void fetchExists_true() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{"editor"});
            assertThat(query.fetchExists()).isTrue();
        }

        @Test
        void fetchExists_false() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{"owner"});
            assertThat(query.fetchExists()).isFalse();
        }

        @Test
        void fetchFirst_present() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{"editor"});
            Optional<Tuple> first = query.fetchFirst();

            assertThat(first).isPresent();
            assertThat(first.get().subjectId()).isEqualTo("alice");
        }

        @Test
        void fetchFirst_empty() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{"owner"});
            assertThat(query.fetchFirst()).isEmpty();
        }

        @Test
        void fetchSubjectIds() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{"viewer"});
            List<String> ids = query.fetchSubjectIds();

            assertThat(ids).containsExactlyInAnyOrder("bob", "carol");
        }

        @Test
        void fetchSubjectIdSet() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{"viewer"});
            Set<String> ids = query.fetchSubjectIdSet();

            assertThat(ids).containsExactlyInAnyOrder("bob", "carol");
        }

        @Test
        void groupByRelationTuples() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{});
            Map<String, List<Tuple>> grouped = query.groupByRelationTuples();

            assertThat(grouped).containsKeys("editor", "viewer");
            assertThat(grouped.get("editor")).hasSize(1);
            assertThat(grouped.get("viewer")).hasSize(2);
        }

        @Test
        void groupByRelation() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{});
            Map<String, List<String>> grouped = query.groupByRelation();

            assertThat(grouped.get("editor")).containsExactly("alice");
            assertThat(grouped.get("viewer")).containsExactlyInAnyOrder("bob", "carol");
        }

        @Test
        void withConsistency() {
            var query = new RelationQuery(RES_TYPE, RES_ID, transport, new String[]{});
            query.withConsistency(Consistency.full());
            assertThat(query.fetchCount()).isEqualTo(3);
        }
    }

    // ================================================================
    //  SubjectQuery
    // ================================================================

    @Nested
    class SubjectQueryTests {

        @BeforeEach
        void grantSome() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"}).to("user:alice", "user:bob");
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"}).to("user:carol");
        }

        @Test
        void fetch_permission() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "editor", true);
            List<String> ids = query.fetch();

            assertThat(ids).containsExactlyInAnyOrder("alice", "bob");
        }

        @Test
        void fetch_relation() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "editor", false);
            List<String> ids = query.fetch();

            assertThat(ids).containsExactlyInAnyOrder("alice", "bob");
        }

        @Test
        void fetchSet() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "editor", true);
            Set<String> ids = query.fetchSet();

            assertThat(ids).containsExactlyInAnyOrder("alice", "bob");
        }

        @Test
        void fetchFirst() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "editor", true);
            Optional<String> first = query.fetchFirst();

            assertThat(first).isPresent();
            assertThat(first.get()).isIn("alice", "bob");
        }

        @Test
        void fetchFirst_empty() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "owner", true);
            assertThat(query.fetchFirst()).isEmpty();
        }

        @Test
        void fetchCount() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "editor", true);
            assertThat(query.fetchCount()).isEqualTo(2);
        }

        @Test
        void fetchExists_true() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "editor", true);
            assertThat(query.fetchExists()).isTrue();
        }

        @Test
        void fetchExists_false_permission() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "owner", true);
            assertThat(query.fetchExists()).isFalse();
        }

        @Test
        void fetchExists_false_relation() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "owner", false);
            assertThat(query.fetchExists()).isFalse();
        }

        @Test
        void limit_permission() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "editor", true);
            query.limit(1);
            List<String> ids = query.fetch();

            assertThat(ids).hasSize(1);
        }

        @Test
        void limit_relation() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "editor", false);
            query.limit(1);
            List<String> ids = query.fetch();

            assertThat(ids).hasSize(1);
        }

        @Test
        void withConsistency() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "editor", true);
            query.withConsistency(Consistency.full());
            assertThat(query.fetchCount()).isEqualTo(2);
        }

        @Test
        void fetchAsync() {
            var query = new SubjectQuery(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC, "editor", true);
            CompletableFuture<List<String>> future = query.fetchAsync();

            assertThat(future.join()).containsExactlyInAnyOrder("alice", "bob");
        }
    }

    // ================================================================
    //  WhoBuilder
    // ================================================================

    @Nested
    class WhoBuilderTests {

        @BeforeEach
        void grantSome() {
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"editor"}).to("user:alice", "user:bob");
            new GrantAction(RES_TYPE, RES_ID, transport, new String[]{"viewer"}).to("user:carol");
        }

        @Test
        void withPermission() {
            var who = new WhoBuilder(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC);
            List<String> editors = who.withPermission("editor").fetch();

            assertThat(editors).containsExactlyInAnyOrder("alice", "bob");
        }

        @Test
        void withRelation() {
            var who = new WhoBuilder(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC);
            List<String> viewers = who.withRelation("viewer").fetch();

            assertThat(viewers).containsExactly("carol");
        }

        @Test
        void withPermission_limit() {
            var who = new WhoBuilder(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC);
            List<String> limited = who.withPermission("editor").limit(1).fetch();

            assertThat(limited).hasSize(1);
        }

        @Test
        void withPermission_fetchSet() {
            var who = new WhoBuilder(RES_TYPE, RES_ID, transport, DEFAULT_SUBJECT, SYNC_EXEC);
            Set<String> editors = who.withPermission("editor").fetchSet();

            assertThat(editors).containsExactlyInAnyOrder("alice", "bob");
        }
    }
}
