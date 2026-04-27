package com.authx.sdk.model;

import com.authx.sdk.model.enums.Permissionship;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ModelAdditionalTest {

    // ---- CaveatRef ----
    @Nested class CaveatRefTest {
        @Test void rejectsNullName() {
            assertThatThrownBy(() -> new CaveatRef(null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
        }

        @Test void acceptsNullContext() {
            CaveatRef ref = new CaveatRef("geo_fence", null);
            assertThat(ref.name()).isEqualTo("geo_fence");
            assertThat(ref.context()).isNull();
        }

        @Test void equalityWithContext() {
            Map<String, Object> ctx = Map.<String, Object>of("region", "us-east");
            CaveatRef a = new CaveatRef("geo_fence", ctx);
            CaveatRef b = new CaveatRef("geo_fence", ctx);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test void inequalityDifferentName() {
            CaveatRef a = new CaveatRef("cav_a", null);
            CaveatRef b = new CaveatRef("cav_b", null);
            assertThat(a).isNotEqualTo(b);
        }
    }

    // ---- CheckKey ----
    @Nested class CheckKeyTest {
        @Test void resourceIndexAutoComputed() {
            CheckKey key = new CheckKey(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.of("user", "alice"));
            assertThat(key.resourceIndex()).isEqualTo("doc:1");
        }

        @Test void factoryMethodOf() {
            CheckKey key = CheckKey.of(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.of("user", "alice"));
            assertThat(key.resourceIndex()).isEqualTo("doc:1");
        }

        @Test void explicitResourceIndex() {
            CheckKey key = new CheckKey(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.of("user", "alice"), "custom:idx");
            assertThat(key.resourceIndex()).isEqualTo("custom:idx");
        }

        @Test void equalityAndHashCode() {
            CheckKey k1 = CheckKey.of(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.of("user", "alice"));
            CheckKey k2 = CheckKey.of(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.of("user", "alice"));
            assertThat(k1).isEqualTo(k2);
            assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
        }
    }

    // ---- ExpandTree ----
    @Nested class ExpandTreeTest {
        @Test void leafNodeLeavesReturnsSubjects() {
            ExpandTree leaf = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:alice", "user:bob"));
            assertThat(leaf.leaves()).containsExactly("user:alice", "user:bob");
        }

        @Test void nonLeafCollectsLeavesRecursively() {
            ExpandTree left = new ExpandTree("leaf", "doc", "1", "editor", List.of(), List.of("user:alice"));
            ExpandTree right = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:bob"));
            ExpandTree union = new ExpandTree("union", "doc", "1", "access", List.of(left, right), List.of());
            assertThat(union.leaves()).containsExactlyInAnyOrder("user:alice", "user:bob");
        }

        @Test void depthOfLeaf() {
            ExpandTree leaf = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:a"));
            assertThat(leaf.depth()).isEqualTo(1);
        }

        @Test void depthOfNestedTree() {
            ExpandTree leaf = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:a"));
            ExpandTree mid = new ExpandTree("union", "doc", "1", "x", List.of(leaf), List.of());
            ExpandTree root = new ExpandTree("union", "doc", "1", "y", List.of(mid), List.of());
            assertThat(root.depth()).isEqualTo(3);
        }

        @Test void containsFindsSubjectInLeaf() {
            ExpandTree leaf = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:alice"));
            assertThat(leaf.contains("user:alice")).isTrue();
            assertThat(leaf.contains("user:bob")).isFalse();
        }

        @Test void containsFindsSubjectInNestedTree() {
            ExpandTree leaf = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:deep"));
            ExpandTree root = new ExpandTree("union", "doc", "1", "access", List.of(leaf), List.of());
            assertThat(root.contains("user:deep")).isTrue();
            assertThat(root.contains("user:missing")).isFalse();
        }

        @Test void nullChildrenReturnsEmptyLeaves() {
            ExpandTree tree = new ExpandTree("union", "doc", "1", "access", null, null);
            assertThat(tree.leaves()).isEmpty();
        }

        @Test void nullChildrenDepthIsOne() {
            ExpandTree tree = new ExpandTree("union", "doc", "1", "access", null, null);
            assertThat(tree.depth()).isEqualTo(1);
        }

        @Test void containsWithNullSubjectsAndChildren() {
            ExpandTree tree = new ExpandTree("union", "doc", "1", "access", null, null);
            assertThat(tree.contains("user:any")).isFalse();
        }
    }

    // ---- Tuple ----
    @Nested class TupleTest {
        @Test void subjectWithoutRelation() {
            Tuple t = new Tuple("document", "doc-1", "editor", "user", "alice", null);
            assertThat(t.subject()).isEqualTo("user:alice");
        }

        @Test void subjectWithRelation() {
            Tuple t = new Tuple("document", "doc-1", "viewer", "group", "eng", "member");
            assertThat(t.subject()).isEqualTo("group:eng#member");
        }

        @Test void resource() {
            Tuple t = new Tuple("document", "doc-1", "editor", "user", "alice", null);
            assertThat(t.resource()).isEqualTo("document:doc-1");
        }

        @Test void equality() {
            Tuple a = new Tuple("doc", "1", "editor", "user", "alice", null);
            Tuple b = new Tuple("doc", "1", "editor", "user", "alice", null);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    // ---- PermissionSet ----
    @Nested class PermissionSetTest {
        private PermissionSet makeSet() {
            return new PermissionSet(Map.of(
                "view", CheckResult.allowed("tok1"),
                "edit", CheckResult.denied("tok2"),
                "delete", CheckResult.denied("tok3")
            ));
        }

        @Test void can() {
            PermissionSet ps = makeSet();
            assertThat(ps.can("view")).isTrue();
            assertThat(ps.can("edit")).isFalse();
            assertThat(ps.can("nonexistent")).isFalse();
        }

        @Test void toMap() {
            PermissionSet ps = makeSet();
            Map<String, Boolean> map = ps.toMap();
            assertThat(map.get("view")).isTrue();
            assertThat(map.get("edit")).isFalse();
        }

        @Test void allowed() {
            PermissionSet ps = makeSet();
            assertThat(ps.allowed()).containsExactly("view");
        }

        @Test void denied() {
            PermissionSet ps = makeSet();
            assertThat(ps.denied()).containsExactlyInAnyOrder("edit", "delete");
        }

        @Test void toList() {
            PermissionSet ps = makeSet();
            assertThat(ps.toList()).hasSize(3);
        }

        @Test void stream() {
            PermissionSet ps = makeSet();
            assertThat(ps.stream().count()).isEqualTo(3);
        }
    }

    // ---- PermissionMatrix ----
    @Nested class PermissionMatrixTest {
        private PermissionMatrix makeMatrix() {
            PermissionSet alice = new PermissionSet(Map.of("view", CheckResult.allowed("t1"), "edit", CheckResult.allowed("t2")));
            PermissionSet bob = new PermissionSet(Map.of("view", CheckResult.allowed("t3"), "edit", CheckResult.denied("t4")));
            PermissionSet carol = new PermissionSet(Map.of("view", CheckResult.denied("t5"), "edit", CheckResult.denied("t6")));
            return new PermissionMatrix(Map.of("alice", alice, "bob", bob, "carol", carol));
        }

        @Test void get() {
            PermissionMatrix m = makeMatrix();
            assertThat(m.get("alice").can("view")).isTrue();
            assertThat(m.get("carol").can("view")).isFalse();
            assertThat(m.get("nonexistent")).isNull();
        }

        @Test void toMapIsUnmodifiable() {
            PermissionMatrix m = makeMatrix();
            assertThatThrownBy(() -> m.toMap().put("x", null))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test void whoCanAll() {
            PermissionMatrix m = makeMatrix();
            assertThat(m.whoCanAll("view", "edit")).containsExactly("alice");
        }

        @Test void whoCanAny() {
            PermissionMatrix m = makeMatrix();
            assertThat(m.whoCanAny("edit")).containsExactlyInAnyOrder("alice");
        }

        @Test void whoCanAnyReturnsMultiple() {
            PermissionMatrix m = makeMatrix();
            assertThat(m.whoCanAny("view")).containsExactlyInAnyOrder("alice", "bob");
        }

        @Test void stream() {
            PermissionMatrix m = makeMatrix();
            assertThat(m.stream().count()).isEqualTo(3);
        }
    }

    // ---- BatchResult ----
    @Nested class BatchResultTest {
        @Test void asConsistencyWithToken() {
            BatchResult r = new BatchResult("token-abc");
            Consistency c = r.asConsistency();
            assertThat(c).isInstanceOf(Consistency.AtLeast.class);
            assertThat(((Consistency.AtLeast) c).zedToken()).isEqualTo("token-abc");
        }

        @Test void asConsistencyWithNullToken() {
            BatchResult r = new BatchResult(null);
            assertThat(r.asConsistency()).isInstanceOf(Consistency.Full.class);
        }
    }

    // ---- BulkCheckResult ----
    @Nested class BulkCheckResultTest {
        private BulkCheckResult makeBulk() {
            return new BulkCheckResult(Map.of(
                "alice", CheckResult.allowed("t1"),
                "bob", CheckResult.denied("t2"),
                "carol", CheckResult.allowed("t3")
            ));
        }

        @Test void get() {
            BulkCheckResult b = makeBulk();
            assertThat(b.get("alice").hasPermission()).isTrue();
            assertThat(b.get("bob").hasPermission()).isFalse();
            assertThat(b.get("nonexistent")).isNull();
        }

        @Test void asMapIsUnmodifiable() {
            BulkCheckResult b = makeBulk();
            assertThatThrownBy(() -> b.asMap().put("x", null))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test void allowed() {
            BulkCheckResult b = makeBulk();
            assertThat(b.allowed()).containsExactlyInAnyOrder("alice", "carol");
        }

        @Test void allowedSet() {
            BulkCheckResult b = makeBulk();
            assertThat(b.allowedSet()).containsExactlyInAnyOrder("alice", "carol");
        }

        @Test void denied() {
            BulkCheckResult b = makeBulk();
            assertThat(b.denied()).containsExactly("bob");
        }

        @Test void allAllowed() {
            BulkCheckResult b = makeBulk();
            assertThat(b.allAllowed()).isFalse();

            BulkCheckResult allYes = new BulkCheckResult(Map.of("a", CheckResult.allowed("t")));
            assertThat(allYes.allAllowed()).isTrue();
        }

        @Test void anyAllowed() {
            BulkCheckResult b = makeBulk();
            assertThat(b.anyAllowed()).isTrue();

            BulkCheckResult allNo = new BulkCheckResult(Map.of("a", CheckResult.denied("t")));
            assertThat(allNo.anyAllowed()).isFalse();
        }

        @Test void allowedCount() {
            BulkCheckResult b = makeBulk();
            assertThat(b.allowedCount()).isEqualTo(2);
        }
    }

    // ---- GrantResult ----
    @Nested class GrantResultTest {
        @Test void asConsistencyWithToken() {
            GrantResult r = new GrantResult("tok-1", 3);
            Consistency c = r.asConsistency();
            assertThat(c).isInstanceOf(Consistency.AtLeast.class);
        }

        @Test void asConsistencyWithNullToken() {
            GrantResult r = new GrantResult(null, 0);
            assertThat(r.asConsistency()).isInstanceOf(Consistency.Full.class);
        }

        @Test void count() {
            GrantResult r = new GrantResult("tok", 5);
            assertThat(r.count()).isEqualTo(5);
        }
    }

    // ---- RevokeResult ----
    @Nested class RevokeResultTest {
        @Test void asConsistencyWithToken() {
            RevokeResult r = new RevokeResult("tok-1", 2);
            assertThat(r.asConsistency()).isInstanceOf(Consistency.AtLeast.class);
        }

        @Test void asConsistencyWithNullToken() {
            RevokeResult r = new RevokeResult(null, 0);
            assertThat(r.asConsistency()).isInstanceOf(Consistency.Full.class);
        }
    }

    // ---- Consistency ----
    @Nested class ConsistencyTest {
        @Test void minimizeLatencyIsSingleton() {
            assertThat(Consistency.minimizeLatency()).isSameAs(Consistency.minimizeLatency());
        }

        @Test void fullIsSingleton() {
            assertThat(Consistency.full()).isSameAs(Consistency.full());
        }

        @Test void atLeastHoldsToken() {
            Consistency c = Consistency.atLeast("tok-1");
            assertThat(c).isInstanceOf(Consistency.AtLeast.class);
            assertThat(((Consistency.AtLeast) c).zedToken()).isEqualTo("tok-1");
        }

        @Test void atExactSnapshotHoldsToken() {
            Consistency c = Consistency.atExactSnapshot("tok-2");
            assertThat(c).isInstanceOf(Consistency.AtExactSnapshot.class);
            assertThat(((Consistency.AtExactSnapshot) c).zedToken()).isEqualTo("tok-2");
        }

        @Test void sealedInterfaceSubtypes() {
            assertThat(Consistency.minimizeLatency()).isInstanceOf(Consistency.MinimizeLatency.class);
            assertThat(Consistency.full()).isInstanceOf(Consistency.Full.class);
            assertThat(Consistency.atLeast("x")).isInstanceOf(Consistency.AtLeast.class);
            assertThat(Consistency.atExactSnapshot("x")).isInstanceOf(Consistency.AtExactSnapshot.class);
        }
    }

    // ---- RelationshipChange ----
    @Nested class RelationshipChangeTest {
        @Test void nullMetadataNormalized() {
            RelationshipChange change = new RelationshipChange(
                RelationshipChange.Operation.TOUCH, "doc", "1", "editor",
                "user", "alice", null, "tok", null, null, null);
            assertThat(change.transactionMetadata()).isNotNull().isEmpty();
        }

        @Test void emptyMetadataNormalized() {
            RelationshipChange change = new RelationshipChange(
                RelationshipChange.Operation.TOUCH, "doc", "1", "editor",
                "user", "alice", null, "tok", null, null, Map.of());
            assertThat(change.transactionMetadata()).isNotNull().isEmpty();
        }

        @Test void metadataIsCopiedImmutably() {
            HashMap<String, String> mutable = new HashMap<String, String>();
            mutable.put("actor", "system");
            RelationshipChange change = new RelationshipChange(
                RelationshipChange.Operation.DELETE, "doc", "1", "editor",
                "user", "alice", null, "tok", null, null, mutable);
            // mutating original does not affect change
            mutable.put("extra", "val");
            assertThat(change.transactionMetadata()).doesNotContainKey("extra");
            assertThat(change.transactionMetadata().get("actor")).isEqualTo("system");
        }

        @Test void operationEnumValues() {
            assertThat(RelationshipChange.Operation.values()).containsExactly(
                RelationshipChange.Operation.CREATE,
                RelationshipChange.Operation.TOUCH,
                RelationshipChange.Operation.DELETE
            );
        }

        @Test void fieldsAccessible() {
            Instant now = Instant.now();
            RelationshipChange change = new RelationshipChange(
                RelationshipChange.Operation.CREATE, "folder", "f1", "viewer",
                "group", "eng", "member", "tok-2", "geo_fence", now,
                Map.of("trace_id", "abc"));
            assertThat(change.operation()).isEqualTo(RelationshipChange.Operation.CREATE);
            assertThat(change.resourceType()).isEqualTo("folder");
            assertThat(change.subjectRelation()).isEqualTo("member");
            assertThat(change.caveatName()).isEqualTo("geo_fence");
            assertThat(change.expiresAt()).isEqualTo(now);
            assertThat(change.transactionMetadata()).containsEntry("trace_id", "abc");
        }
    }
}
