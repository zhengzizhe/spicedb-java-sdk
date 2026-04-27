package com.authx.sdk.model;

import com.authx.sdk.model.enums.Permissionship;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            com.authx.sdk.model.CaveatRef ref = new CaveatRef("geo_fence", null);
            assertThat(ref.name()).isEqualTo("geo_fence");
            assertThat(ref.context()).isNull();
        }

        @Test void equalityWithContext() {
            java.util.Map<java.lang.String,java.lang.Object> ctx = Map.<String, Object>of("region", "us-east");
            com.authx.sdk.model.CaveatRef a = new CaveatRef("geo_fence", ctx);
            com.authx.sdk.model.CaveatRef b = new CaveatRef("geo_fence", ctx);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test void inequalityDifferentName() {
            com.authx.sdk.model.CaveatRef a = new CaveatRef("cav_a", null);
            com.authx.sdk.model.CaveatRef b = new CaveatRef("cav_b", null);
            assertThat(a).isNotEqualTo(b);
        }
    }

    // ---- CheckKey ----
    @Nested class CheckKeyTest {
        @Test void resourceIndexAutoComputed() {
            com.authx.sdk.model.CheckKey key = new CheckKey(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.of("user", "alice"));
            assertThat(key.resourceIndex()).isEqualTo("doc:1");
        }

        @Test void factoryMethodOf() {
            com.authx.sdk.model.CheckKey key = CheckKey.of(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.of("user", "alice"));
            assertThat(key.resourceIndex()).isEqualTo("doc:1");
        }

        @Test void explicitResourceIndex() {
            com.authx.sdk.model.CheckKey key = new CheckKey(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.of("user", "alice"), "custom:idx");
            assertThat(key.resourceIndex()).isEqualTo("custom:idx");
        }

        @Test void equalityAndHashCode() {
            com.authx.sdk.model.CheckKey k1 = CheckKey.of(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.of("user", "alice"));
            com.authx.sdk.model.CheckKey k2 = CheckKey.of(ResourceRef.of("doc", "1"), Permission.of("view"), SubjectRef.of("user", "alice"));
            assertThat(k1).isEqualTo(k2);
            assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
        }
    }

    // ---- ExpandTree ----
    @Nested class ExpandTreeTest {
        @Test void leafNodeLeavesReturnsSubjects() {
            com.authx.sdk.model.ExpandTree leaf = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:alice", "user:bob"));
            assertThat(leaf.leaves()).containsExactly("user:alice", "user:bob");
        }

        @Test void nonLeafCollectsLeavesRecursively() {
            com.authx.sdk.model.ExpandTree left = new ExpandTree("leaf", "doc", "1", "editor", List.of(), List.of("user:alice"));
            com.authx.sdk.model.ExpandTree right = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:bob"));
            com.authx.sdk.model.ExpandTree union = new ExpandTree("union", "doc", "1", "access", List.of(left, right), List.of());
            assertThat(union.leaves()).containsExactlyInAnyOrder("user:alice", "user:bob");
        }

        @Test void depthOfLeaf() {
            com.authx.sdk.model.ExpandTree leaf = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:a"));
            assertThat(leaf.depth()).isEqualTo(1);
        }

        @Test void depthOfNestedTree() {
            com.authx.sdk.model.ExpandTree leaf = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:a"));
            com.authx.sdk.model.ExpandTree mid = new ExpandTree("union", "doc", "1", "x", List.of(leaf), List.of());
            com.authx.sdk.model.ExpandTree root = new ExpandTree("union", "doc", "1", "y", List.of(mid), List.of());
            assertThat(root.depth()).isEqualTo(3);
        }

        @Test void containsFindsSubjectInLeaf() {
            com.authx.sdk.model.ExpandTree leaf = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:alice"));
            assertThat(leaf.contains("user:alice")).isTrue();
            assertThat(leaf.contains("user:bob")).isFalse();
        }

        @Test void containsFindsSubjectInNestedTree() {
            com.authx.sdk.model.ExpandTree leaf = new ExpandTree("leaf", "doc", "1", "viewer", List.of(), List.of("user:deep"));
            com.authx.sdk.model.ExpandTree root = new ExpandTree("union", "doc", "1", "access", List.of(leaf), List.of());
            assertThat(root.contains("user:deep")).isTrue();
            assertThat(root.contains("user:missing")).isFalse();
        }

        @Test void nullChildrenReturnsEmptyLeaves() {
            com.authx.sdk.model.ExpandTree tree = new ExpandTree("union", "doc", "1", "access", null, null);
            assertThat(tree.leaves()).isEmpty();
        }

        @Test void nullChildrenDepthIsOne() {
            com.authx.sdk.model.ExpandTree tree = new ExpandTree("union", "doc", "1", "access", null, null);
            assertThat(tree.depth()).isEqualTo(1);
        }

        @Test void containsWithNullSubjectsAndChildren() {
            com.authx.sdk.model.ExpandTree tree = new ExpandTree("union", "doc", "1", "access", null, null);
            assertThat(tree.contains("user:any")).isFalse();
        }
    }

    // ---- Tuple ----
    @Nested class TupleTest {
        @Test void subjectWithoutRelation() {
            com.authx.sdk.model.Tuple t = new Tuple("document", "doc-1", "editor", "user", "alice", null);
            assertThat(t.subject()).isEqualTo("user:alice");
        }

        @Test void subjectWithRelation() {
            com.authx.sdk.model.Tuple t = new Tuple("document", "doc-1", "viewer", "group", "eng", "member");
            assertThat(t.subject()).isEqualTo("group:eng#member");
        }

        @Test void resource() {
            com.authx.sdk.model.Tuple t = new Tuple("document", "doc-1", "editor", "user", "alice", null);
            assertThat(t.resource()).isEqualTo("document:doc-1");
        }

        @Test void equality() {
            com.authx.sdk.model.Tuple a = new Tuple("doc", "1", "editor", "user", "alice", null);
            com.authx.sdk.model.Tuple b = new Tuple("doc", "1", "editor", "user", "alice", null);
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
            com.authx.sdk.model.PermissionSet ps = makeSet();
            assertThat(ps.can("view")).isTrue();
            assertThat(ps.can("edit")).isFalse();
            assertThat(ps.can("nonexistent")).isFalse();
        }

        @Test void toMap() {
            com.authx.sdk.model.PermissionSet ps = makeSet();
            java.util.Map<java.lang.String,java.lang.Boolean> map = ps.toMap();
            assertThat(map.get("view")).isTrue();
            assertThat(map.get("edit")).isFalse();
        }

        @Test void allowed() {
            com.authx.sdk.model.PermissionSet ps = makeSet();
            assertThat(ps.allowed()).containsExactly("view");
        }

        @Test void denied() {
            com.authx.sdk.model.PermissionSet ps = makeSet();
            assertThat(ps.denied()).containsExactlyInAnyOrder("edit", "delete");
        }

        @Test void toList() {
            com.authx.sdk.model.PermissionSet ps = makeSet();
            assertThat(ps.toList()).hasSize(3);
        }

        @Test void stream() {
            com.authx.sdk.model.PermissionSet ps = makeSet();
            assertThat(ps.stream().count()).isEqualTo(3);
        }
    }

    // ---- PermissionMatrix ----
    @Nested class PermissionMatrixTest {
        private PermissionMatrix makeMatrix() {
            com.authx.sdk.model.PermissionSet alice = new PermissionSet(Map.of("view", CheckResult.allowed("t1"), "edit", CheckResult.allowed("t2")));
            com.authx.sdk.model.PermissionSet bob = new PermissionSet(Map.of("view", CheckResult.allowed("t3"), "edit", CheckResult.denied("t4")));
            com.authx.sdk.model.PermissionSet carol = new PermissionSet(Map.of("view", CheckResult.denied("t5"), "edit", CheckResult.denied("t6")));
            return new PermissionMatrix(Map.of("alice", alice, "bob", bob, "carol", carol));
        }

        @Test void get() {
            com.authx.sdk.model.PermissionMatrix m = makeMatrix();
            assertThat(m.get("alice").can("view")).isTrue();
            assertThat(m.get("carol").can("view")).isFalse();
            assertThat(m.get("nonexistent")).isNull();
        }

        @Test void toMapIsUnmodifiable() {
            com.authx.sdk.model.PermissionMatrix m = makeMatrix();
            assertThatThrownBy(() -> m.toMap().put("x", null))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test void whoCanAll() {
            com.authx.sdk.model.PermissionMatrix m = makeMatrix();
            assertThat(m.whoCanAll("view", "edit")).containsExactly("alice");
        }

        @Test void whoCanAny() {
            com.authx.sdk.model.PermissionMatrix m = makeMatrix();
            assertThat(m.whoCanAny("edit")).containsExactlyInAnyOrder("alice");
        }

        @Test void whoCanAnyReturnsMultiple() {
            com.authx.sdk.model.PermissionMatrix m = makeMatrix();
            assertThat(m.whoCanAny("view")).containsExactlyInAnyOrder("alice", "bob");
        }

        @Test void stream() {
            com.authx.sdk.model.PermissionMatrix m = makeMatrix();
            assertThat(m.stream().count()).isEqualTo(3);
        }
    }

    // ---- BatchResult ----
    @Nested class BatchResultTest {
        @Test void asConsistencyWithToken() {
            com.authx.sdk.model.BatchResult r = new BatchResult("token-abc");
            com.authx.sdk.model.Consistency c = r.asConsistency();
            assertThat(c).isInstanceOf(Consistency.AtLeast.class);
            assertThat(((Consistency.AtLeast) c).zedToken()).isEqualTo("token-abc");
        }

        @Test void asConsistencyWithNullToken() {
            com.authx.sdk.model.BatchResult r = new BatchResult(null);
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
            com.authx.sdk.model.BulkCheckResult b = makeBulk();
            assertThat(b.get("alice").hasPermission()).isTrue();
            assertThat(b.get("bob").hasPermission()).isFalse();
            assertThat(b.get("nonexistent")).isNull();
        }

        @Test void asMapIsUnmodifiable() {
            com.authx.sdk.model.BulkCheckResult b = makeBulk();
            assertThatThrownBy(() -> b.asMap().put("x", null))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test void allowed() {
            com.authx.sdk.model.BulkCheckResult b = makeBulk();
            assertThat(b.allowed()).containsExactlyInAnyOrder("alice", "carol");
        }

        @Test void allowedSet() {
            com.authx.sdk.model.BulkCheckResult b = makeBulk();
            assertThat(b.allowedSet()).containsExactlyInAnyOrder("alice", "carol");
        }

        @Test void denied() {
            com.authx.sdk.model.BulkCheckResult b = makeBulk();
            assertThat(b.denied()).containsExactly("bob");
        }

        @Test void allAllowed() {
            com.authx.sdk.model.BulkCheckResult b = makeBulk();
            assertThat(b.allAllowed()).isFalse();

            com.authx.sdk.model.BulkCheckResult allYes = new BulkCheckResult(Map.of("a", CheckResult.allowed("t")));
            assertThat(allYes.allAllowed()).isTrue();
        }

        @Test void anyAllowed() {
            com.authx.sdk.model.BulkCheckResult b = makeBulk();
            assertThat(b.anyAllowed()).isTrue();

            com.authx.sdk.model.BulkCheckResult allNo = new BulkCheckResult(Map.of("a", CheckResult.denied("t")));
            assertThat(allNo.anyAllowed()).isFalse();
        }

        @Test void allowedCount() {
            com.authx.sdk.model.BulkCheckResult b = makeBulk();
            assertThat(b.allowedCount()).isEqualTo(2);
        }
    }

    // ---- GrantResult ----
    @Nested class GrantResultTest {
        @Test void asConsistencyWithToken() {
            com.authx.sdk.model.GrantResult r = new GrantResult("tok-1", 3);
            com.authx.sdk.model.Consistency c = r.asConsistency();
            assertThat(c).isInstanceOf(Consistency.AtLeast.class);
        }

        @Test void asConsistencyWithNullToken() {
            com.authx.sdk.model.GrantResult r = new GrantResult(null, 0);
            assertThat(r.asConsistency()).isInstanceOf(Consistency.Full.class);
        }

        @Test void count() {
            com.authx.sdk.model.GrantResult r = new GrantResult("tok", 5);
            assertThat(r.count()).isEqualTo(5);
        }
    }

    // ---- RevokeResult ----
    @Nested class RevokeResultTest {
        @Test void asConsistencyWithToken() {
            com.authx.sdk.model.RevokeResult r = new RevokeResult("tok-1", 2);
            assertThat(r.asConsistency()).isInstanceOf(Consistency.AtLeast.class);
        }

        @Test void asConsistencyWithNullToken() {
            com.authx.sdk.model.RevokeResult r = new RevokeResult(null, 0);
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
            com.authx.sdk.model.Consistency c = Consistency.atLeast("tok-1");
            assertThat(c).isInstanceOf(Consistency.AtLeast.class);
            assertThat(((Consistency.AtLeast) c).zedToken()).isEqualTo("tok-1");
        }

        @Test void atExactSnapshotHoldsToken() {
            com.authx.sdk.model.Consistency c = Consistency.atExactSnapshot("tok-2");
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
            com.authx.sdk.model.RelationshipChange change = new RelationshipChange(
                RelationshipChange.Operation.TOUCH, "doc", "1", "editor",
                "user", "alice", null, "tok", null, null, null);
            assertThat(change.transactionMetadata()).isNotNull().isEmpty();
        }

        @Test void emptyMetadataNormalized() {
            com.authx.sdk.model.RelationshipChange change = new RelationshipChange(
                RelationshipChange.Operation.TOUCH, "doc", "1", "editor",
                "user", "alice", null, "tok", null, null, Map.of());
            assertThat(change.transactionMetadata()).isNotNull().isEmpty();
        }

        @Test void metadataIsCopiedImmutably() {
            java.util.HashMap<java.lang.String,java.lang.String> mutable = new java.util.HashMap<String, String>();
            mutable.put("actor", "system");
            com.authx.sdk.model.RelationshipChange change = new RelationshipChange(
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
            java.time.Instant now = Instant.now();
            com.authx.sdk.model.RelationshipChange change = new RelationshipChange(
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
