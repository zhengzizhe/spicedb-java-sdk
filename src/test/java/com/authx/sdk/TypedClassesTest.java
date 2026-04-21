package com.authx.sdk;

import com.authx.sdk.model.*;
import com.authx.sdk.transport.InMemoryTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for root typed classes: TypedHandle, TypedCheckAction,
 * TypedCheckAllAction, TypedGrantAction, TypedRevokeAction,
 * TypedFinder, TypedWhoQuery, TypedResourceEntry, ResourceFactory,
 * ResourceHandle, BatchCheckBuilder, CrossResourceBatchBuilder.
 */
class TypedClassesTest {

    // ================================================================
    //  Test enums — simulate codegen output
    // ================================================================

    enum TestRel implements Relation.Named {
        EDITOR("editor"), VIEWER("viewer"), OWNER("owner");
        private final String name;
        TestRel(String n) { this.name = n; }
        @Override public String relationName() { return name; }
    }

    enum TestPerm implements Permission.Named {
        VIEW("view"), EDIT("edit"), DELETE("delete");
        private final String name;
        TestPerm(String n) { this.name = n; }
        @Override public String permissionName() { return name; }
    }

    static final ResourceType<TestRel, TestPerm> DOC_TYPE =
            ResourceType.of("document", TestRel.class, TestPerm.class);

    // Second type for cross-resource tests
    enum TaskRel implements Relation.Named {
        ASSIGNEE("assignee");
        private final String name;
        TaskRel(String n) { this.name = n; }
        @Override public String relationName() { return name; }
    }

    enum TaskPerm implements Permission.Named {
        COMPLETE("complete");
        private final String name;
        TaskPerm(String n) { this.name = n; }
        @Override public String permissionName() { return name; }
    }

    static final ResourceType<TaskRel, TaskPerm> TASK_TYPE =
            ResourceType.of("task", TaskRel.class, TaskPerm.class);

    private static final String DEFAULT_SUBJECT = "user";
    private static final Executor SYNC_EXEC = Runnable::run;

    private InMemoryTransport transport;
    private ResourceFactory docFactory;

    @BeforeEach
    void setUp() {
        transport = new InMemoryTransport();
        docFactory = new ResourceFactory("document", transport, DEFAULT_SUBJECT, SYNC_EXEC);
    }

    // ================================================================
    //  ResourceFactory
    // ================================================================

    @Nested
    class ResourceFactoryTests {

        @Test
        void resourceType_returns_boundType() {
            assertThat(docFactory.resourceType()).isEqualTo("document");
        }

        @Test
        void check_stringBased() {
            docFactory.grant("doc-1", "view", "alice");
            assertThat(docFactory.check("doc-1", "view", "alice")).isTrue();
            assertThat(docFactory.check("doc-1", "view", "bob")).isFalse();
        }

        @Test
        void check_withConsistency() {
            docFactory.grant("doc-1", "view", "alice");
            assertThat(docFactory.check("doc-1", "view", "alice", Consistency.full())).isTrue();
        }

        @Test
        void grant_stringBased() {
            GrantResult result = docFactory.grant("doc-1", "editor", "alice", "bob");
            assertThat(result.count()).isEqualTo(2);
        }

        @Test
        void grantToSubjects() {
            GrantResult result = docFactory.grantToSubjects("doc-1", "viewer", "group:eng#member");
            assertThat(result.count()).isEqualTo(1);
        }

        @Test
        void revoke_stringBased() {
            docFactory.grant("doc-1", "editor", "alice");
            RevokeResult result = docFactory.revoke("doc-1", "editor", "alice");
            assertThat(result.count()).isEqualTo(1);
        }

        @Test
        void revokeFromSubjects() {
            docFactory.grantToSubjects("doc-1", "viewer", "group:eng#member");
            RevokeResult result = docFactory.revokeFromSubjects("doc-1", "viewer", "group:eng#member");
            assertThat(result.count()).isEqualTo(1);
        }

        @Test
        void allRelations() {
            docFactory.grant("doc-1", "editor", "alice");
            docFactory.grant("doc-1", "viewer", "bob");
            Map<String, List<String>> grouped = docFactory.allRelations("doc-1");

            assertThat(grouped).containsKeys("editor", "viewer");
        }

        @Test
        void resource_returnsHandle() {
            ResourceHandle handle = docFactory.resource("doc-1");
            assertThat(handle.resourceType()).isEqualTo("document");
            assertThat(handle.resourceId()).isEqualTo("doc-1");
        }

        @Test
        void lookup_returnsQuery() {
            docFactory.grant("doc-1", "viewer", "alice");
            List<String> ids = docFactory.lookup().withPermission("viewer").by("alice").fetch();
            assertThat(ids).contains("doc-1");
        }
    }

    // ================================================================
    //  ResourceHandle
    // ================================================================

    @Nested
    class ResourceHandleTests {

        @Test
        void grant_and_check() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("alice");
            assertThat(handle.check("editor").by("alice").hasPermission()).isTrue();
        }

        @Test
        void revoke() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("alice");
            handle.revoke("editor").from("alice");
            assertThat(handle.check("editor").by("alice").hasPermission()).isFalse();
        }

        @Test
        void revokeAll_noRelations() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("alice");
            handle.grant("viewer").to("alice");
            handle.revokeAll().from("alice");

            assertThat(handle.check("editor").by("alice").hasPermission()).isFalse();
            assertThat(handle.check("viewer").by("alice").hasPermission()).isFalse();
        }

        @Test
        void revokeAll_withRelations() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("alice");
            handle.grant("viewer").to("alice");
            handle.revokeAll("editor").from("alice");

            assertThat(handle.check("editor").by("alice").hasPermission()).isFalse();
            assertThat(handle.check("viewer").by("alice").hasPermission()).isTrue();
        }

        @Test
        void checkAll() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("alice");
            PermissionSet set = handle.checkAll("editor", "viewer").by("alice");

            assertThat(set.can("editor")).isTrue();
            assertThat(set.can("viewer")).isFalse();
        }

        @Test
        void who() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("alice", "bob");
            List<String> editors = handle.who().withPermission("editor").fetch();

            assertThat(editors).containsExactlyInAnyOrder("alice", "bob");
        }

        @Test
        void relations() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("alice");
            List<Tuple> tuples = handle.relations("editor").fetch();

            assertThat(tuples).hasSize(1);
        }

        @Test
        void batch() {
            ResourceHandle handle = docFactory.resource("doc-1");
            BatchResult result = handle.batch()
                    .grant("editor").to("alice")
                    .grant("viewer").to("bob")
                    .execute();

            assertThat(result.zedToken()).isNotNull();
            assertThat(handle.check("editor").by("alice").hasPermission()).isTrue();
            assertThat(handle.check("viewer").by("bob").hasPermission()).isTrue();
        }

        @Test
        void expand() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("alice");
            ExpandTree tree = handle.expand("editor");

            assertThat(tree).isNotNull();
        }
    }

    // ================================================================
    //  TypedHandle
    // ================================================================

    @Nested
    class TypedHandleTests {

        @Test
        void check_single_permission() {
            docFactory.grant("doc-1", "view", "alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});

            TypedCheckAction action = handle.check(TestPerm.VIEW);
            assertThat(action.by("alice")).isTrue();
            assertThat(action.by("bob")).isFalse();
        }

        @Test
        void check_multiple_permissions() {
            docFactory.grant("doc-1", "view", "alice");
            docFactory.grant("doc-1", "edit", "alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});

            CheckMatrix matrix = handle.check(TestPerm.VIEW, TestPerm.EDIT).byAll("alice");
            assertThat(matrix.allowed("doc-1", "view", "alice")).isTrue();
            assertThat(matrix.allowed("doc-1", "edit", "alice")).isTrue();
        }

        @Test
        void check_collection() {
            docFactory.grant("doc-1", "view", "alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});

            TypedCheckAction action = handle.check(List.of(TestPerm.VIEW));
            CheckMatrix matrix = action.byAll("alice");
            assertThat(matrix.allowed("doc-1", "view", "alice")).isTrue();
        }

        @Test
        void grant_typed() {
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});
            // No schema cache => validation is skipped
            handle.grant(TestRel.EDITOR).toUser("alice");

            assertThat(docFactory.check("doc-1", "editor", "alice")).isTrue();
        }

        @Test
        void grant_collection() {
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});
            handle.grant(List.of(TestRel.EDITOR)).toUser("alice");

            assertThat(docFactory.check("doc-1", "editor", "alice")).isTrue();
        }

        @Test
        void revoke_typed() {
            docFactory.grant("doc-1", "editor", "alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});
            handle.revoke(TestRel.EDITOR).fromUser("alice");

            assertThat(docFactory.check("doc-1", "editor", "alice")).isFalse();
        }

        @Test
        void revoke_collection() {
            docFactory.grant("doc-1", "editor", "alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});
            handle.revoke(List.of(TestRel.EDITOR)).fromUser("alice");

            assertThat(docFactory.check("doc-1", "editor", "alice")).isFalse();
        }

        @Test
        void checkAll_with_class() {
            docFactory.grant("doc-1", "view", "alice");
            docFactory.grant("doc-1", "edit", "alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});

            TypedCheckAllAction<TestPerm> action = handle.checkAll(TestPerm.class);
            EnumMap<TestPerm, Boolean> result = action.by("alice");

            assertThat(result.get(TestPerm.VIEW)).isTrue();
            assertThat(result.get(TestPerm.EDIT)).isTrue();
            assertThat(result.get(TestPerm.DELETE)).isFalse();
        }

        @Test
        void checkAll_parameterless_withPermClass() {
            docFactory.grant("doc-1", "view", "alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"}, TestPerm.class);

            var action = handle.checkAll();
            // Should work because permClass was provided
            assertThat(action).isNotNull();
        }

        @Test
        void checkAll_parameterless_withoutPermClass_throws() {
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});

            assertThatThrownBy(handle::checkAll)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void who() {
            docFactory.grant("doc-1", "view", "alice", "bob");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});

            TypedWhoQuery query = handle.who(TestPerm.VIEW);
            List<String> ids = query.asUserIds();
            assertThat(ids).containsExactlyInAnyOrder("alice", "bob");
        }

        @Test
        void who_multipleIds_throws() {
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1", "doc-2"});

            assertThatThrownBy(() -> handle.who(TestPerm.VIEW))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ================================================================
    //  TypedCheckAction
    // ================================================================

    @Nested
    class TypedCheckActionTests {

        @Test
        void by_singleId_singlePerm_allowed() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            assertThat(action.by("alice")).isTrue();
        }

        @Test
        void by_singleId_singlePerm_denied() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            assertThat(action.by("alice")).isFalse();
        }

        @Test
        void by_multipleIds_throws() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1", "doc-2"}, new String[]{"view"});

            assertThatThrownBy(() -> action.by("alice"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void by_multiplePerms_throws() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view", "edit"});

            assertThatThrownBy(() -> action.by("alice"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void by_subjectRef() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            assertThat(action.by(SubjectRef.of("user", "alice"))).isTrue();
        }

        @Test
        void by_subjectRef_multipleIds_throws() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1", "doc-2"}, new String[]{"view"});

            assertThatThrownBy(() -> action.by(SubjectRef.of("user", "alice")))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void detailedBy_returnsCheckResult() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            CheckResult result = action.detailedBy("alice");
            assertThat(result.hasPermission()).isTrue();
        }

        @Test
        void detailedBy_multipleIds_throws() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1", "doc-2"}, new String[]{"view"});

            assertThatThrownBy(() -> action.detailedBy("alice"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void detailedBy_subjectRef() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            CheckResult result = action.detailedBy(SubjectRef.of("user", "alice"));
            assertThat(result.hasPermission()).isTrue();
        }

        @Test
        void detailedBy_subjectRef_multiplePerms_throws() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view", "edit"});

            assertThatThrownBy(() -> action.detailedBy(SubjectRef.of("user", "alice")))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void withConsistency() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});
            action.withConsistency(Consistency.full());

            assertThat(action.by("alice")).isTrue();
        }

        @Test
        void withContext() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});
            action.withContext(Map.of("ip", "10.0.0.1"));

            assertThat(action.by("alice")).isTrue();
        }

        @Test
        void byAll_strings() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            CheckMatrix matrix = action.byAll("alice", "bob");
            assertThat(matrix.allowed("doc-1", "view", "alice")).isTrue();
            assertThat(matrix.allowed("doc-1", "view", "bob")).isFalse();
        }

        @Test
        void byAll_collection() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            CheckMatrix matrix = action.byAll(List.of("alice"));
            assertThat(matrix.allowed("doc-1", "view", "alice")).isTrue();
        }

        @Test
        void byAll_subjectRefs() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            CheckMatrix matrix = action.byAll(SubjectRef.of("user", "alice"), SubjectRef.of("user", "bob"));
            assertThat(matrix.allowed("doc-1", "view", "alice")).isTrue();
            assertThat(matrix.allowed("doc-1", "view", "bob")).isFalse();
        }

        @Test
        void byAll_empty_throws() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            assertThatThrownBy(() -> action.byAll(new String[]{}))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void byAll_nullStrings_throws() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            assertThatThrownBy(() -> action.byAll((String[]) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void byAll_multiResource_multiPerm_multiSubject() {
            docFactory.grant("doc-1", "view", "alice");
            docFactory.grant("doc-2", "edit", "bob");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1", "doc-2"}, new String[]{"view", "edit"});

            CheckMatrix matrix = action.byAll("alice", "bob");
            assertThat(matrix.allowed("doc-1", "view", "alice")).isTrue();
            assertThat(matrix.allowed("doc-2", "edit", "bob")).isTrue();
            assertThat(matrix.allowed("doc-1", "edit", "alice")).isFalse();
        }

        @Test
        void byAll_singleCell_usesSimplePath() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            // Single id x single perm x single subject -> simple path
            CheckMatrix matrix = action.byAll(SubjectRef.of("user", "alice"));
            assertThat(matrix.allowed("doc-1", "view", "alice")).isTrue();
            assertThat(matrix.size()).isEqualTo(1);
        }
    }

    // ================================================================
    //  TypedCheckAllAction
    // ================================================================

    @Nested
    class TypedCheckAllActionTests {

        @Test
        void by_singleUser() {
            docFactory.grant("doc-1", "view", "alice");
            docFactory.grant("doc-1", "edit", "alice");
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1"}, TestPerm.class);

            EnumMap<TestPerm, Boolean> result = action.by("alice");
            assertThat(result.get(TestPerm.VIEW)).isTrue();
            assertThat(result.get(TestPerm.EDIT)).isTrue();
            assertThat(result.get(TestPerm.DELETE)).isFalse();
        }

        @Test
        void by_subjectRef() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1"}, TestPerm.class);

            EnumMap<TestPerm, Boolean> result = action.by(SubjectRef.of("user", "alice"));
            assertThat(result.get(TestPerm.VIEW)).isTrue();
        }

        @Test
        void by_multipleIds_throws() {
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1", "doc-2"}, TestPerm.class);

            assertThatThrownBy(() -> action.by("alice"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void by_subjectRef_multipleIds_throws() {
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1", "doc-2"}, TestPerm.class);

            assertThatThrownBy(() -> action.by(SubjectRef.of("user", "alice")))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void byAll_singleUser() {
            docFactory.grant("doc-1", "view", "alice");
            docFactory.grant("doc-2", "edit", "alice");
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1", "doc-2"}, TestPerm.class);

            Map<String, EnumMap<TestPerm, Boolean>> result = action.byAll("alice");
            assertThat(result).containsKeys("doc-1", "doc-2");
            assertThat(result.get("doc-1").get(TestPerm.VIEW)).isTrue();
            assertThat(result.get("doc-2").get(TestPerm.EDIT)).isTrue();
        }

        @Test
        void byAll_subjectRef() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1"}, TestPerm.class);

            Map<String, EnumMap<TestPerm, Boolean>> result = action.byAll(SubjectRef.of("user", "alice"));
            assertThat(result.get("doc-1").get(TestPerm.VIEW)).isTrue();
        }

        @Test
        void withConsistency_and_withContext() {
            docFactory.grant("doc-1", "view", "alice");
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1"}, TestPerm.class);
            action.withConsistency(Consistency.full());
            action.withContext(Map.of("ip", "10.0.0.1"));

            EnumMap<TestPerm, Boolean> result = action.by("alice");
            assertThat(result.get(TestPerm.VIEW)).isTrue();
        }
    }

    // ================================================================
    //  TypedGrantAction
    // ================================================================

    @Nested
    class TypedGrantActionTests {

        @Test
        void toUser_single() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.toUser("alice");

            assertThat(docFactory.check("doc-1", "editor", "alice")).isTrue();
        }

        @Test
        void toUser_multiple() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.toUser("alice", "bob");

            assertThat(docFactory.check("doc-1", "editor", "alice")).isTrue();
            assertThat(docFactory.check("doc-1", "editor", "bob")).isTrue();
        }

        @Test
        void toUser_collection() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.toUser(List.of("alice", "bob"));

            assertThat(docFactory.check("doc-1", "editor", "alice")).isTrue();
        }

        @Test
        void toGroupMember() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.VIEWER);
            action.toGroupMember("eng");

            var tuples = transport.allTuples();
            assertThat(tuples).hasSize(1);
            var t = tuples.iterator().next();
            assertThat(t.subjectType()).isEqualTo("group");
            assertThat(t.subjectId()).isEqualTo("eng");
            assertThat(t.subjectRelation()).isEqualTo("member");
        }

        @Test
        void toGroupMember_collection() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.VIEWER);
            action.toGroupMember(List.of("eng", "sales"));

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void toUserAll() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.VIEWER);
            action.toUserAll();

            var tuples = transport.allTuples();
            assertThat(tuples).hasSize(1);
            var t = tuples.iterator().next();
            assertThat(t.subjectType()).isEqualTo("user");
            assertThat(t.subjectId()).isEqualTo("*");
        }

        @Test
        void to_subjectRef() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.to(SubjectRef.of("department", "eng", "all_members"));

            var tuples = transport.allTuples();
            assertThat(tuples).hasSize(1);
            var t = tuples.iterator().next();
            assertThat(t.subjectType()).isEqualTo("department");
            assertThat(t.subjectId()).isEqualTo("eng");
            assertThat(t.subjectRelation()).isEqualTo("all_members");
        }

        @Test
        void to_subjectRef_collection() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.to(List.of(SubjectRef.of("user", "alice"), SubjectRef.of("user", "bob")));

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void to_null_noop() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.to((SubjectRef[]) null);
            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void to_empty_noop() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.to(new SubjectRef[]{});
            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void toSubjectRefs_strings() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.toSubjectRefs("user:alice", "group:eng#member");

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void toSubjectRefs_collection() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.toSubjectRefs(List.of("user:alice"));

            assertThat(transport.size()).isEqualTo(1);
        }

        @Test
        void toSubjectRefs_null_noop() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.toSubjectRefs((String[]) null);
            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void toSubjectRefs_emptyCollection_noop() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.toSubjectRefs(List.of());
            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void multipleIds_multipleRelations() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1", "doc-2"}, TestRel.EDITOR, TestRel.VIEWER);
            action.toUser("alice");

            // 2 ids x 2 relations x 1 user = 4
            assertThat(transport.size()).isEqualTo(4);
        }

        @Test
        void withCaveat() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.withCaveat("ip_range", Map.of("allowed_cidr", "10.0.0.0/8")).toUser("alice");

            assertThat(transport.size()).isEqualTo(1);
        }

        @Test
        void toUser_null_noop() {
            var action = new TypedGrantAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.toUser((String[]) null);
            assertThat(transport.size()).isEqualTo(0);
        }
    }

    // ================================================================
    //  TypedRevokeAction
    // ================================================================

    @Nested
    class TypedRevokeActionTests {

        @BeforeEach
        void grantSome() {
            docFactory.grant("doc-1", "editor", "alice");
            docFactory.grant("doc-1", "editor", "bob");
            docFactory.grantToSubjects("doc-1", "viewer", "group:eng#member");
        }

        @Test
        void fromUser() {
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.fromUser("alice");

            assertThat(docFactory.check("doc-1", "editor", "alice")).isFalse();
            assertThat(docFactory.check("doc-1", "editor", "bob")).isTrue();
        }

        @Test
        void fromUser_collection() {
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.fromUser(List.of("alice", "bob"));

            assertThat(docFactory.check("doc-1", "editor", "alice")).isFalse();
            assertThat(docFactory.check("doc-1", "editor", "bob")).isFalse();
        }

        @Test
        void fromGroupMember() {
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.VIEWER);
            action.fromGroupMember("eng");

            assertThat(transport.size()).isEqualTo(2); // editors remain
        }

        @Test
        void fromGroupMember_collection() {
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.VIEWER);
            action.fromGroupMember(List.of("eng"));

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void fromUserAll() {
            docFactory.grantToSubjects("doc-1", "viewer", "user:*");
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.VIEWER);
            action.fromUserAll();

            // user:* and group:eng#member viewer both revoked; editors remain
            // Actually only user:* is revoked by fromUserAll
        }

        @Test
        void from_subjectRef() {
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.VIEWER);
            action.from(SubjectRef.of("group", "eng", "member"));

            assertThat(transport.size()).isEqualTo(2); // editors remain
        }

        @Test
        void from_subjectRef_collection() {
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.VIEWER);
            action.from(List.of(SubjectRef.of("group", "eng", "member")));

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void from_null_noop() {
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.from((SubjectRef[]) null);
            assertThat(transport.size()).isEqualTo(3); // nothing changed
        }

        @Test
        void fromSubjectRefs_string() {
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.fromSubjectRefs("user:alice");

            assertThat(docFactory.check("doc-1", "editor", "alice")).isFalse();
        }

        @Test
        void fromSubjectRefs_collection() {
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.fromSubjectRefs(List.of("user:alice"));

            assertThat(docFactory.check("doc-1", "editor", "alice")).isFalse();
        }

        @Test
        void fromSubjectRefs_null_noop() {
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.fromSubjectRefs((String[]) null);
            assertThat(transport.size()).isEqualTo(3);
        }

        @Test
        void fromSubjectRefs_emptyCollection_noop() {
            var action = new TypedRevokeAction<>(docFactory, new String[]{"doc-1"}, TestRel.EDITOR);
            action.fromSubjectRefs(List.of());
            assertThat(transport.size()).isEqualTo(3);
        }
    }

    // ================================================================
    //  TypedWhoQuery
    // ================================================================

    @Nested
    class TypedWhoQueryTests {

        @BeforeEach
        void grantSome() {
            docFactory.grant("doc-1", "view", "alice");
            docFactory.grant("doc-1", "view", "bob");
            docFactory.grant("doc-1", "view", "carol");
        }

        @Test
        void asUserIds() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "view");
            List<String> ids = query.asUserIds();

            assertThat(ids).containsExactlyInAnyOrder("alice", "bob", "carol");
        }

        @Test
        void asSubjectRefs() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "view");
            List<SubjectRef> refs = query.asSubjectRefs();

            assertThat(refs).hasSize(3);
            assertThat(refs).allMatch(r -> r.type().equals("user"));
        }

        @Test
        void count() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "view");
            assertThat(query.count()).isEqualTo(3);
        }

        @Test
        void exists_true() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "view");
            assertThat(query.exists()).isTrue();
        }

        @Test
        void exists_false() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "edit");
            assertThat(query.exists()).isFalse();
        }

        @Test
        void limit() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "view");
            query.limit(2);
            List<String> ids = query.asUserIds();

            assertThat(ids).hasSize(2);
        }

        @Test
        void exists_restoresLimit() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "view");
            query.limit(0);
            query.exists(); // temporarily sets limit=1, then restores
            List<String> ids = query.asUserIds();

            assertThat(ids).hasSize(3); // limit was restored to 0
        }
    }

    // ================================================================
    //  TypedFinder
    // ================================================================

    @Nested
    class TypedFinderTests {

        @BeforeEach
        void grantSome() {
            docFactory.grant("doc-1", "view", "alice");
            docFactory.grant("doc-2", "view", "alice");
            docFactory.grant("doc-3", "edit", "alice");
            docFactory.grant("doc-1", "view", "bob");
        }

        @Test
        void can_single() {
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            List<String> ids = finder.can(TestPerm.VIEW);

            assertThat(ids).containsExactlyInAnyOrder("doc-1", "doc-2");
        }

        @Test
        void can_withLimit() {
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            finder.limit(1);
            List<String> ids = finder.can(TestPerm.VIEW);

            assertThat(ids).hasSize(1);
        }

        @Test
        void can_multiple() {
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            Map<TestPerm, List<String>> result = finder.can(TestPerm.VIEW, TestPerm.EDIT);

            assertThat(result.get(TestPerm.VIEW)).containsExactlyInAnyOrder("doc-1", "doc-2");
            assertThat(result.get(TestPerm.EDIT)).containsExactly("doc-3");
        }

        @Test
        void can_multiple_empty() {
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            Map<TestPerm, List<String>> result = finder.can();
            assertThat(result).isEmpty();
        }

        @Test
        void can_multiple_null() {
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            Map<TestPerm, List<String>> result = finder.can((TestPerm[]) null);
            assertThat(result).isEmpty();
        }

        @Test
        void can_collection() {
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            Map<TestPerm, List<String>> result = finder.can(List.of(TestPerm.VIEW));

            assertThat(result.get(TestPerm.VIEW)).containsExactlyInAnyOrder("doc-1", "doc-2");
        }

        @Test
        void can_collection_empty() {
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            Map<TestPerm, List<String>> result = finder.can(List.of());
            assertThat(result).isEmpty();
        }

        @Test
        void canAny() {
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            List<String> ids = finder.canAny(TestPerm.VIEW, TestPerm.EDIT);

            assertThat(ids).containsExactlyInAnyOrder("doc-1", "doc-2", "doc-3");
        }

        @Test
        void canAny_empty() {
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            List<String> ids = finder.canAny();
            assertThat(ids).isEmpty();
        }

        @Test
        void canAll() {
            // alice has view on doc-1 and doc-2, edit on doc-3
            // Only doc-1 and doc-2 have view; none have both view AND edit
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            List<String> ids = finder.canAll(TestPerm.VIEW, TestPerm.EDIT);

            assertThat(ids).isEmpty(); // no doc has both view and edit
        }

        @Test
        void canAll_singlePerm() {
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            List<String> ids = finder.canAll(TestPerm.VIEW);

            assertThat(ids).containsExactlyInAnyOrder("doc-1", "doc-2");
        }

        @Test
        void canAll_empty() {
            var finder = new TypedFinder<TestPerm>(docFactory, SubjectRef.of("user", "alice"));
            List<String> ids = finder.canAll();
            assertThat(ids).isEmpty();
        }
    }

    // ================================================================
    //  TypedResourceEntry
    // ================================================================

    @Nested
    class TypedResourceEntryTests {

        private TypedResourceEntry<TestRel, TestPerm> entry;

        @BeforeEach
        void setUp() {
            entry = new TypedResourceEntry<>(docFactory, DOC_TYPE);
        }

        @Test
        void type() {
            assertThat(entry.type()).isEqualTo(DOC_TYPE);
        }

        @Test
        void select_single() {
            TypedHandle<TestRel, TestPerm> handle = entry.select("doc-1");
            assertThat(handle).isNotNull();
        }

        @Test
        void select_multiple() {
            TypedHandle<TestRel, TestPerm> handle = entry.select("doc-1", "doc-2");
            assertThat(handle).isNotNull();
        }

        @Test
        void select_collection() {
            TypedHandle<TestRel, TestPerm> handle = entry.select(List.of("doc-1"));
            assertThat(handle).isNotNull();
        }

        @Test
        void select_empty_throws() {
            assertThatThrownBy(() -> entry.select())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void select_null_throws() {
            assertThatThrownBy(() -> entry.select((String[]) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void findByUser() {
            docFactory.grant("doc-1", "view", "alice");
            TypedFinder<TestPerm> finder = entry.findByUser("alice");
            List<String> ids = finder.can(TestPerm.VIEW);

            assertThat(ids).contains("doc-1");
        }

        @Test
        void findBy_subjectRef() {
            docFactory.grant("doc-1", "view", "alice");
            TypedFinder<TestPerm> finder = entry.findBy(SubjectRef.of("user", "alice"));
            List<String> ids = finder.can(TestPerm.VIEW);

            assertThat(ids).contains("doc-1");
        }

        @Test
        void findByUsers() {
            docFactory.grant("doc-1", "view", "alice");
            docFactory.grant("doc-2", "view", "bob");

            var multi = entry.findByUsers("alice", "bob");
            Map<String, List<String>> result = multi.can(TestPerm.VIEW);

            assertThat(result.get("alice")).contains("doc-1");
            assertThat(result.get("bob")).contains("doc-2");
        }

        @Test
        void findByUsers_collection() {
            docFactory.grant("doc-1", "view", "alice");

            var multi = entry.findByUsers(List.of("alice"));
            Map<String, List<String>> result = multi.can(TestPerm.VIEW);

            assertThat(result.get("alice")).contains("doc-1");
        }

        @Test
        void findBy_subjectRefs_varargs() {
            docFactory.grant("doc-1", "view", "alice");

            var multi = entry.findBy(new SubjectRef[]{SubjectRef.of("user", "alice"), SubjectRef.of("user", "bob")});
            Map<String, List<String>> result = multi.can(TestPerm.VIEW);

            assertThat(result.get("alice")).contains("doc-1");
        }

        @Test
        void findBy_subjectRefs_collection() {
            docFactory.grant("doc-1", "view", "alice");

            var multi = entry.findBy(List.of(SubjectRef.of("user", "alice")));
            Map<String, List<String>> result = multi.can(TestPerm.VIEW);

            assertThat(result.get("alice")).contains("doc-1");
        }

        @Test
        void multiFinder_withLimit() {
            docFactory.grant("doc-1", "view", "alice");
            docFactory.grant("doc-2", "view", "alice");

            var multi = entry.findByUsers("alice");
            multi.limit(1);
            Map<String, List<String>> result = multi.can(TestPerm.VIEW);

            assertThat(result.get("alice")).hasSize(1);
        }
    }

    // ================================================================
    //  BatchCheckBuilder
    // ================================================================

    @Nested
    class BatchCheckBuilderTests {

        @BeforeEach
        void grantSome() {
            docFactory.grant("doc-1", "view", "alice");
            var taskFactory = new ResourceFactory("task", transport, DEFAULT_SUBJECT, SYNC_EXEC);
            taskFactory.grant("t-1", "complete", "alice");
        }

        @Test
        void add_stringBased_and_fetch() {
            var builder = new BatchCheckBuilder(transport);
            builder.add("document", "doc-1", "view", SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "alice")).isTrue();
        }

        @Test
        void add_permissionNamed() {
            var builder = new BatchCheckBuilder(transport);
            builder.add("document", "doc-1", TestPerm.VIEW, SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "alice")).isTrue();
        }

        @Test
        void add_permissionNamed_userId() {
            var builder = new BatchCheckBuilder(transport);
            builder.add("document", "doc-1", TestPerm.VIEW, "alice");

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "alice")).isTrue();
        }

        @Test
        void add_resourceType_descriptor() {
            var builder = new BatchCheckBuilder(transport);
            builder.add(DOC_TYPE, "doc-1", TestPerm.VIEW, SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "alice")).isTrue();
        }

        @Test
        void add_resourceType_userId() {
            var builder = new BatchCheckBuilder(transport);
            builder.add(DOC_TYPE, "doc-1", TestPerm.VIEW, "alice");

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "alice")).isTrue();
        }

        @Test
        void addAll_resourceType() {
            var builder = new BatchCheckBuilder(transport);
            builder.addAll(DOC_TYPE, List.of("doc-1", "doc-2"), TestPerm.VIEW, "alice");

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "alice")).isTrue();
            assertThat(matrix.allowed("document:doc-2", "view", "alice")).isFalse();
        }

        @Test
        void addAll_string() {
            var builder = new BatchCheckBuilder(transport);
            builder.addAll("document", List.of("doc-1"), TestPerm.VIEW, "alice");

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "alice")).isTrue();
        }

        @Test
        void addAll_cells() {
            var builder = new BatchCheckBuilder(transport);
            builder.addAll(List.of(
                    BatchCheckBuilder.Cell.of(DOC_TYPE, "doc-1", TestPerm.VIEW, SubjectRef.of("user", "alice")),
                    BatchCheckBuilder.Cell.of(DOC_TYPE, "doc-1", TestPerm.VIEW, "bob")
            ));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "alice")).isTrue();
            assertThat(matrix.allowed("document:doc-1", "view", "bob")).isFalse();
        }

        @Test
        void fetch_empty() {
            var builder = new BatchCheckBuilder(transport);
            CheckMatrix matrix = builder.fetch();

            assertThat(matrix.size()).isEqualTo(0);
        }

        @Test
        void withConsistency() {
            var builder = new BatchCheckBuilder(transport);
            builder.withConsistency(Consistency.full());
            builder.add("document", "doc-1", TestPerm.VIEW, "alice");

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "alice")).isTrue();
        }

        @Test
        void crossResource_batch() {
            var builder = new BatchCheckBuilder(transport);
            builder.add("document", "doc-1", TestPerm.VIEW, "alice");
            builder.add("task", "t-1", TaskPerm.COMPLETE, "alice");

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "alice")).isTrue();
            assertThat(matrix.allowed("task:t-1", "complete", "alice")).isTrue();
        }
    }

    // ================================================================
    //  CrossResourceBatchBuilder
    // ================================================================

    @Nested
    class CrossResourceBatchBuilderTests {

        @Test
        void on_string_grant_and_revoke() {
            docFactory.grant("doc-1", "owner", "alice");

            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            BatchResult result = builder
                    .on("document", "doc-1")
                        .grant("editor").to("bob")
                        .revoke("owner").from("alice")
                    .execute();

            assertThat(result.zedToken()).isNotNull();
            assertThat(docFactory.check("doc-1", "editor", "bob")).isTrue();
            assertThat(docFactory.check("doc-1", "owner", "alice")).isFalse();
        }

        @Test
        void on_resourceHandle() {
            ResourceHandle handle = docFactory.resource("doc-1");
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.on(handle).grant("editor").to("alice").execute();

            assertThat(docFactory.check("doc-1", "editor", "alice")).isTrue();
        }

        @Test
        void on_resourceType() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.on(DOC_TYPE, "doc-1").grant("editor").to("alice").execute();

            assertThat(docFactory.check("doc-1", "editor", "alice")).isTrue();
        }

        @Test
        void multipleResources() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder
                    .on("document", "doc-1").grant("editor").to("alice")
                    .on("document", "doc-2").grant("viewer").to("bob")
                    .execute();

            assertThat(docFactory.check("doc-1", "editor", "alice")).isTrue();
            assertThat(docFactory.check("doc-2", "viewer", "bob")).isTrue();
        }

        @Test
        void grant_toSubjects() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.on("document", "doc-1").grant("viewer").toSubjects("group:eng#member").execute();

            assertThat(transport.size()).isEqualTo(1);
        }

        @Test
        void grant_typedRelation() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.on("document", "doc-1").grant(TestRel.EDITOR).to("alice").execute();

            assertThat(docFactory.check("doc-1", "editor", "alice")).isTrue();
        }

        @Test
        void revoke_typedRelation() {
            docFactory.grant("doc-1", "editor", "alice");
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.on("document", "doc-1").revoke(TestRel.EDITOR).from("alice").execute();

            assertThat(docFactory.check("doc-1", "editor", "alice")).isFalse();
        }

        @Test
        void revoke_fromCollection() {
            docFactory.grant("doc-1", "editor", "alice");
            docFactory.grant("doc-1", "editor", "bob");
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.on("document", "doc-1").revoke("editor").from(List.of("alice", "bob")).execute();

            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void grant_toCollection() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.on("document", "doc-1").grant("editor").to(List.of("alice", "bob")).execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void commit_alias() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            BatchResult result = builder.on("document", "doc-1").grant("editor").to("alice").commit();

            assertThat(result.zedToken()).isNotNull();
        }

        @Test
        void empty_execute() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            BatchResult result = builder.execute();

            assertThat(result.zedToken()).isNull();
        }

        @Test
        void scope_chaining_on() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            // Test chaining through ResourceScope.on()
            builder
                    .on("document", "doc-1").grant("editor").to("alice")
                    .on("document", "doc-2").grant("viewer").to("bob")
                    .on(DOC_TYPE, "doc-3").grant("owner").to("carol")
                    .execute();

            assertThat(transport.size()).isEqualTo(3);
        }

        @Test
        void scope_commit_from_resourceScope() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            BatchResult result = builder.on("document", "doc-1").grant("editor").to("alice").commit();

            assertThat(result.zedToken()).isNotNull();
        }

        @Test
        void scope_execute_from_resourceScope() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            BatchResult result = builder.on("document", "doc-1").grant("editor").to("alice").execute();

            assertThat(result.zedToken()).isNotNull();
        }

        // ---- MultiResourceScope tests ----

        @Test
        void onAll_grant_fansAcrossIds() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2", "doc-3"))
                    .grant("viewer").to("alice")
                    .execute();

            assertThat(transport.size()).isEqualTo(3);
            assertThat(docFactory.check("doc-1", "viewer", "alice")).isTrue();
            assertThat(docFactory.check("doc-2", "viewer", "alice")).isTrue();
            assertThat(docFactory.check("doc-3", "viewer", "alice")).isTrue();
        }

        @Test
        void onAll_string_grant() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.onAll("document", List.of("doc-1", "doc-2"))
                    .grant("editor").to("alice")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void onAll_grant_toSubjects() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2"))
                    .grant("viewer").toSubjects("group:eng#member")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void onAll_grant_typedRelation() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2"))
                    .grant(TestRel.EDITOR).to("alice")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void onAll_revoke() {
            docFactory.grant("doc-1", "editor", "alice");
            docFactory.grant("doc-2", "editor", "alice");

            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2"))
                    .revoke("editor").from("alice")
                    .execute();

            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void onAll_revoke_typedRelation() {
            docFactory.grant("doc-1", "editor", "alice");
            docFactory.grant("doc-2", "editor", "alice");

            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2"))
                    .revoke(TestRel.EDITOR).from("alice")
                    .execute();

            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void onAll_revoke_fromCollection() {
            docFactory.grant("doc-1", "editor", "alice");
            docFactory.grant("doc-1", "editor", "bob");

            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.onAll(DOC_TYPE, List.of("doc-1"))
                    .revoke("editor").from(List.of("alice", "bob"))
                    .execute();

            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void onAll_chaining_to_on() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2"))
                    .grant("viewer").to("alice")
                    .on(DOC_TYPE, "doc-3").grant("editor").to("bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(3);
        }

        @Test
        void onAll_chaining_to_onAll() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.onAll(DOC_TYPE, List.of("doc-1"))
                    .grant("viewer").to("alice")
                    .onAll(DOC_TYPE, List.of("doc-2"))
                    .grant("editor").to("bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void multiScope_on_string() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.onAll(DOC_TYPE, List.of("doc-1"))
                    .grant("viewer").to("alice")
                    .on("document", "doc-2").grant("editor").to("bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void multiScope_execute_and_commit() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            BatchResult r1 = builder.onAll(DOC_TYPE, List.of("doc-1")).grant("viewer").to("alice").execute();
            assertThat(r1.zedToken()).isNotNull();

            var builder2 = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            BatchResult r2 = builder2.onAll(DOC_TYPE, List.of("doc-2")).grant("viewer").to("bob").commit();
            assertThat(r2.zedToken()).isNotNull();
        }

        @Test
        void scope_onAll_from_resourceScope() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.on("document", "doc-1").grant("editor").to("alice")
                    .onAll(DOC_TYPE, List.of("doc-2", "doc-3")).grant("viewer").to("bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(3);
        }

        @Test
        void scope_onAll_string_from_resourceScope() {
            var builder = new CrossResourceBatchBuilder(transport, DEFAULT_SUBJECT);
            builder.on("document", "doc-1").grant("editor").to("alice")
                    .onAll("document", List.of("doc-2")).grant("viewer").to("bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }
    }

    // ================================================================
    //  PermissionResource annotation
    // ================================================================

    @Nested
    class PermissionResourceTests {

        @PermissionResource("document")
        static class TestPermResource extends ResourceFactory {}

        @Test
        void annotationValue() {
            var ann = TestPermResource.class.getAnnotation(PermissionResource.class);
            assertThat(ann).isNotNull();
            assertThat(ann.value()).isEqualTo("document");
        }
    }
}
