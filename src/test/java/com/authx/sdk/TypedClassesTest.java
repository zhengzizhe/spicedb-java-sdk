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
 * TypedCheckAllAction, GrantFlow, RevokeFlow,
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
        docFactory = new ResourceFactory("document", transport, SYNC_EXEC);
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
            docFactory.resource("doc-1").grant("view").to("user:alice");
            assertThat(docFactory.resource("doc-1").check("view").by("user:alice").hasPermission()).isTrue();
            assertThat(docFactory.resource("doc-1").check("view").by("user:bob").hasPermission()).isFalse();
        }

        @Test
        void check_withConsistency() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            assertThat(docFactory.resource("doc-1").check("view").withConsistency(Consistency.full()).by("user:alice").hasPermission()).isTrue();
        }

        @Test
        void grant_stringBased() {
            GrantResult result = docFactory.resource("doc-1").grant("editor").to("user:alice", "user:bob");
            assertThat(result.count()).isEqualTo(2);
        }

        @Test
        void grantToSubjects() {
            GrantResult result = docFactory.resource("doc-1").grant("viewer").to("group:eng#member");
            assertThat(result.count()).isEqualTo(1);
        }

        @Test
        void revoke_stringBased() {
            docFactory.resource("doc-1").grant("editor").to("user:alice");
            RevokeResult result = docFactory.resource("doc-1").revoke("editor").from("user:alice");
            assertThat(result.count()).isEqualTo(1);
        }

        @Test
        void revokeFromSubjects() {
            docFactory.resource("doc-1").grant("viewer").to("group:eng#member");
            RevokeResult result = docFactory.resource("doc-1").revoke("viewer").from("group:eng#member");
            assertThat(result.count()).isEqualTo(1);
        }

        @Test
        void allRelations() {
            docFactory.resource("doc-1").grant("editor").to("user:alice");
            docFactory.resource("doc-1").grant("viewer").to("user:bob");
            Map<String, List<String>> grouped = docFactory.resource("doc-1").relations().groupByRelation();

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
            docFactory.resource("doc-1").grant("viewer").to("user:alice");
            List<String> ids = docFactory.lookup().withPermission("viewer").by("user:alice").fetch();
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
            handle.grant("editor").to("user:alice");
            assertThat(handle.check("editor").by("user:alice").hasPermission()).isTrue();
        }

        @Test
        void revoke() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("user:alice");
            handle.revoke("editor").from("user:alice");
            assertThat(handle.check("editor").by("user:alice").hasPermission()).isFalse();
        }

        @Test
        void revokeAll_noRelations() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("user:alice");
            handle.grant("viewer").to("user:alice");
            handle.revokeAll().from("user:alice");

            assertThat(handle.check("editor").by("user:alice").hasPermission()).isFalse();
            assertThat(handle.check("viewer").by("user:alice").hasPermission()).isFalse();
        }

        @Test
        void revokeAll_withRelations() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("user:alice");
            handle.grant("viewer").to("user:alice");
            handle.revokeAll("editor").from("user:alice");

            assertThat(handle.check("editor").by("user:alice").hasPermission()).isFalse();
            assertThat(handle.check("viewer").by("user:alice").hasPermission()).isTrue();
        }

        @Test
        void checkAll() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("user:alice");
            PermissionSet set = handle.checkAll("editor", "viewer").by("user:alice");

            assertThat(set.can("editor")).isTrue();
            assertThat(set.can("viewer")).isFalse();
        }

        @Test
        void who() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("user:alice", "user:bob");
            List<String> editors = handle.who("user").withPermission("editor").fetch();

            assertThat(editors).containsExactlyInAnyOrder("alice", "bob");
        }

        @Test
        void relations() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("user:alice");
            List<Tuple> tuples = handle.relations("editor").fetch();

            assertThat(tuples).hasSize(1);
        }

        @Test
        void batch() {
            ResourceHandle handle = docFactory.resource("doc-1");
            BatchResult result = handle.batch()
                    .grant("editor").to("user:alice")
                    .grant("viewer").to("user:bob")
                    .execute();

            assertThat(result.zedToken()).isNotNull();
            assertThat(handle.check("editor").by("user:alice").hasPermission()).isTrue();
            assertThat(handle.check("viewer").by("user:bob").hasPermission()).isTrue();
        }

        @Test
        void expand() {
            ResourceHandle handle = docFactory.resource("doc-1");
            handle.grant("editor").to("user:alice");
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
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});

            TypedCheckAction action = handle.check(TestPerm.VIEW);
            assertThat(action.by("user:alice")).isTrue();
            assertThat(action.by("user:bob")).isFalse();
        }

        @Test
        void check_multiple_permissions() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            docFactory.resource("doc-1").grant("edit").to("user:alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});

            CheckMatrix matrix = handle.check(TestPerm.VIEW, TestPerm.EDIT).byAll("user:alice");
            assertThat(matrix.allowed("doc-1", "view", "user:alice")).isTrue();
            assertThat(matrix.allowed("doc-1", "edit", "user:alice")).isTrue();
        }

        @Test
        void check_collection() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});

            TypedCheckAction action = handle.check(List.of(TestPerm.VIEW));
            CheckMatrix matrix = action.byAll("user:alice");
            assertThat(matrix.allowed("doc-1", "view", "user:alice")).isTrue();
        }

        @Test
        void grant_typed() {
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});
            // No schema cache => validation is skipped
            handle.grant(TestRel.EDITOR).to("user:alice").commit();

            assertThat(docFactory.resource("doc-1").check("editor").by("user:alice").hasPermission()).isTrue();
        }

        @Test
        void revoke_typed() {
            docFactory.resource("doc-1").grant("editor").to("user:alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});
            handle.revoke(TestRel.EDITOR).from("user:alice").commit();

            assertThat(docFactory.resource("doc-1").check("editor").by("user:alice").hasPermission()).isFalse();
        }

        @Test
        void checkAll_with_class() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            docFactory.resource("doc-1").grant("edit").to("user:alice");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});

            TypedCheckAllAction<TestPerm> action = handle.checkAll(TestPerm.class);
            EnumMap<TestPerm, Boolean> result = action.by("user:alice");

            assertThat(result.get(TestPerm.VIEW)).isTrue();
            assertThat(result.get(TestPerm.EDIT)).isTrue();
            assertThat(result.get(TestPerm.DELETE)).isFalse();
        }

        @Test
        void checkAll_parameterless_withPermClass() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
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
            docFactory.resource("doc-1").grant("view").to("user:alice", "user:bob");
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1"});

            TypedWhoQuery query = handle.who("user", TestPerm.VIEW);
            List<String> ids = query.fetchIds();
            assertThat(ids).containsExactlyInAnyOrder("alice", "bob");
        }

        @Test
        void who_multipleIds_throws() {
            var handle = new TypedHandle<TestRel, TestPerm>(docFactory, new String[]{"doc-1", "doc-2"});

            assertThatThrownBy(() -> handle.who("user", TestPerm.VIEW))
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
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            assertThat(action.by("user:alice")).isTrue();
        }

        @Test
        void by_singleId_singlePerm_denied() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            assertThat(action.by("user:alice")).isFalse();
        }

        @Test
        void by_multipleIds_throws() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1", "doc-2"}, new String[]{"view"});

            assertThatThrownBy(() -> action.by("user:alice"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void by_multiplePerms_throws() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view", "edit"});

            assertThatThrownBy(() -> action.by("user:alice"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void by_subjectRef() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
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
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            CheckResult result = action.detailedBy("user:alice");
            assertThat(result.hasPermission()).isTrue();
        }

        @Test
        void detailedBy_multipleIds_throws() {
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1", "doc-2"}, new String[]{"view"});

            assertThatThrownBy(() -> action.detailedBy("user:alice"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void detailedBy_subjectRef() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
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
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});
            action.withConsistency(Consistency.full());

            assertThat(action.by("user:alice")).isTrue();
        }

        @Test
        void withContext() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});
            action.withContext(Map.of("ip", "10.0.0.1"));

            assertThat(action.by("user:alice")).isTrue();
        }

        @Test
        void byAll_strings() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            CheckMatrix matrix = action.byAll("user:alice", "user:bob");
            assertThat(matrix.allowed("doc-1", "view", "user:alice")).isTrue();
            assertThat(matrix.allowed("doc-1", "view", "user:bob")).isFalse();
        }

        @Test
        void byAll_collection() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            CheckMatrix matrix = action.byAll("user:alice");
            assertThat(matrix.allowed("doc-1", "view", "user:alice")).isTrue();
        }

        @Test
        void byAll_subjectRefs() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            CheckMatrix matrix = action.byAll(SubjectRef.of("user", "alice"), SubjectRef.of("user", "bob"));
            assertThat(matrix.allowed("doc-1", "view", "user:alice")).isTrue();
            assertThat(matrix.allowed("doc-1", "view", "user:bob")).isFalse();
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
            docFactory.resource("doc-1").grant("view").to("user:alice");
            docFactory.resource("doc-2").grant("edit").to("user:bob");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1", "doc-2"}, new String[]{"view", "edit"});

            CheckMatrix matrix = action.byAll("user:alice", "user:bob");
            assertThat(matrix.allowed("doc-1", "view", "user:alice")).isTrue();
            assertThat(matrix.allowed("doc-2", "edit", "user:bob")).isTrue();
            assertThat(matrix.allowed("doc-1", "edit", "user:alice")).isFalse();
        }

        @Test
        void byAll_singleCell_usesSimplePath() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var action = new TypedCheckAction(docFactory, new String[]{"doc-1"}, new String[]{"view"});

            // Single id x single perm x single subject -> simple path
            CheckMatrix matrix = action.byAll(SubjectRef.of("user", "alice"));
            assertThat(matrix.allowed("doc-1", "view", "user:alice")).isTrue();
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
            docFactory.resource("doc-1").grant("view").to("user:alice");
            docFactory.resource("doc-1").grant("edit").to("user:alice");
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1"}, TestPerm.class);

            EnumMap<TestPerm, Boolean> result = action.by("user:alice");
            assertThat(result.get(TestPerm.VIEW)).isTrue();
            assertThat(result.get(TestPerm.EDIT)).isTrue();
            assertThat(result.get(TestPerm.DELETE)).isFalse();
        }

        @Test
        void by_subjectRef() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1"}, TestPerm.class);

            EnumMap<TestPerm, Boolean> result = action.by(SubjectRef.of("user", "alice"));
            assertThat(result.get(TestPerm.VIEW)).isTrue();
        }

        @Test
        void by_multipleIds_throws() {
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1", "doc-2"}, TestPerm.class);

            assertThatThrownBy(() -> action.by("user:alice"))
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
            docFactory.resource("doc-1").grant("view").to("user:alice");
            docFactory.resource("doc-2").grant("edit").to("user:alice");
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1", "doc-2"}, TestPerm.class);

            Map<String, EnumMap<TestPerm, Boolean>> result = action.byAll("user:alice");
            assertThat(result).containsKeys("doc-1", "doc-2");
            assertThat(result.get("doc-1").get(TestPerm.VIEW)).isTrue();
            assertThat(result.get("doc-2").get(TestPerm.EDIT)).isTrue();
        }

        @Test
        void byAll_subjectRef() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1"}, TestPerm.class);

            Map<String, EnumMap<TestPerm, Boolean>> result = action.byAll(SubjectRef.of("user", "alice"));
            assertThat(result.get("doc-1").get(TestPerm.VIEW)).isTrue();
        }

        @Test
        void withConsistency_and_withContext() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var action = new TypedCheckAllAction<>(docFactory, new String[]{"doc-1"}, TestPerm.class);
            action.withConsistency(Consistency.full());
            action.withContext(Map.of("ip", "10.0.0.1"));

            EnumMap<TestPerm, Boolean> result = action.by("user:alice");
            assertThat(result.get(TestPerm.VIEW)).isTrue();
        }
    }


    // ================================================================
    //  TypedWhoQuery
    // ================================================================

    @Nested
    class TypedWhoQueryTests {

        @BeforeEach
        void grantSome() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            docFactory.resource("doc-1").grant("view").to("user:bob");
            docFactory.resource("doc-1").grant("view").to("user:carol");
        }

        @Test
        void asUserIds() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "user", "view");
            List<String> ids = query.fetchIds();

            assertThat(ids).containsExactlyInAnyOrder("alice", "bob", "carol");
        }

        @Test
        void asSubjectRefs() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "user", "view");
            List<SubjectRef> refs = query.asSubjectRefs();

            assertThat(refs).hasSize(3);
            assertThat(refs).allMatch(r -> r.type().equals("user"));
        }

        @Test
        void count() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "user", "view");
            assertThat(query.count()).isEqualTo(3);
        }

        @Test
        void exists_true() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "user", "view");
            assertThat(query.exists()).isTrue();
        }

        @Test
        void exists_false() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "user", "edit");
            assertThat(query.exists()).isFalse();
        }

        @Test
        void limit() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "user", "view");
            query.limit(2);
            List<String> ids = query.fetchIds();

            assertThat(ids).hasSize(2);
        }

        @Test
        void exists_restoresLimit() {
            var query = new TypedWhoQuery(docFactory, "doc-1", "user", "view");
            query.limit(0);
            query.exists(); // temporarily sets limit=1, then restores
            List<String> ids = query.fetchIds();

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
            docFactory.resource("doc-1").grant("view").to("user:alice");
            docFactory.resource("doc-2").grant("view").to("user:alice");
            docFactory.resource("doc-3").grant("edit").to("user:alice");
            docFactory.resource("doc-1").grant("view").to("user:bob");
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
            docFactory.resource("doc-1").grant("view").to("user:alice");
            TypedFinder<TestPerm> finder = entry.findBy("user:alice");
            List<String> ids = finder.can(TestPerm.VIEW);

            assertThat(ids).contains("doc-1");
        }

        @Test
        void findBy_subjectRef() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            TypedFinder<TestPerm> finder = entry.findBy(SubjectRef.of("user", "alice"));
            List<String> ids = finder.can(TestPerm.VIEW);

            assertThat(ids).contains("doc-1");
        }

        @Test
        void findByUsers() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            docFactory.resource("doc-2").grant("view").to("user:bob");

            var multi = entry.findBy("user:alice", "user:bob");
            Map<String, List<String>> result = multi.can(TestPerm.VIEW);

            assertThat(result.get("user:alice")).contains("doc-1");
            assertThat(result.get("user:bob")).contains("doc-2");
        }

        @Test
        void findBy_subjectRefs_varargs() {
            docFactory.resource("doc-1").grant("view").to("user:alice");

            var multi = entry.findBy(SubjectRef.of("user", "alice"), SubjectRef.of("user", "bob"));
            Map<String, List<String>> result = multi.can(TestPerm.VIEW);

            assertThat(result.get("user:alice")).contains("doc-1");
        }

        @Test
        void findBy_subjectRefs_collection() {
            docFactory.resource("doc-1").grant("view").to("user:alice");

            var multi = entry.findBy(List.of(SubjectRef.of("user", "alice")));
            Map<String, List<String>> result = multi.can(TestPerm.VIEW);

            assertThat(result.get("user:alice")).contains("doc-1");
        }

        @Test
        void multiFinder_withLimit() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            docFactory.resource("doc-2").grant("view").to("user:alice");

            var multi = entry.findBy(List.of(SubjectRef.of("user", "alice")));
            multi.limit(1);
            Map<String, List<String>> result = multi.can(TestPerm.VIEW);

            assertThat(result.get("user:alice")).hasSize(1);
        }
    }

    // ================================================================
    //  BatchCheckBuilder
    // ================================================================

    @Nested
    class BatchCheckBuilderTests {

        @BeforeEach
        void grantSome() {
            docFactory.resource("doc-1").grant("view").to("user:alice");
            var taskFactory = new ResourceFactory("task", transport, SYNC_EXEC);
            taskFactory.resource("t-1").grant("complete").to("user:alice");
        }

        @Test
        void add_stringBased_and_fetch() {
            var builder = new BatchCheckBuilder(transport);
            builder.add("document", "doc-1", "view", SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "user:alice")).isTrue();
        }

        @Test
        void add_permissionNamed() {
            var builder = new BatchCheckBuilder(transport);
            builder.add("document", "doc-1", TestPerm.VIEW, SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "user:alice")).isTrue();
        }

        @Test
        void add_permissionNamed_userId() {
            var builder = new BatchCheckBuilder(transport);
            builder.add("document", "doc-1", TestPerm.VIEW, SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "user:alice")).isTrue();
        }

        @Test
        void add_resourceType_descriptor() {
            var builder = new BatchCheckBuilder(transport);
            builder.add(DOC_TYPE, "doc-1", TestPerm.VIEW, SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "user:alice")).isTrue();
        }

        @Test
        void add_resourceType_userId() {
            var builder = new BatchCheckBuilder(transport);
            builder.add(DOC_TYPE, "doc-1", TestPerm.VIEW, SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "user:alice")).isTrue();
        }

        @Test
        void addAll_resourceType() {
            var builder = new BatchCheckBuilder(transport);
            builder.addAll(DOC_TYPE, List.of("doc-1", "doc-2"), TestPerm.VIEW, SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "user:alice")).isTrue();
            assertThat(matrix.allowed("document:doc-2", "view", "user:alice")).isFalse();
        }

        @Test
        void addAll_string() {
            var builder = new BatchCheckBuilder(transport);
            builder.addAll("document", List.of("doc-1"), TestPerm.VIEW, SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "user:alice")).isTrue();
        }

        @Test
        void addAll_cells() {
            var builder = new BatchCheckBuilder(transport);
            builder.addAll(List.of(
                    BatchCheckBuilder.Cell.of(DOC_TYPE, "doc-1", TestPerm.VIEW, SubjectRef.of("user", "alice")),
                    BatchCheckBuilder.Cell.of(DOC_TYPE, "doc-1", TestPerm.VIEW, SubjectRef.of("user", "bob"))
            ));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "user:alice")).isTrue();
            assertThat(matrix.allowed("document:doc-1", "view", "user:bob")).isFalse();
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
            builder.add("document", "doc-1", TestPerm.VIEW, SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "user:alice")).isTrue();
        }

        @Test
        void crossResource_batch() {
            var builder = new BatchCheckBuilder(transport);
            builder.add("document", "doc-1", TestPerm.VIEW, SubjectRef.of("user", "alice"));
            builder.add("task", "t-1", TaskPerm.COMPLETE, SubjectRef.of("user", "alice"));

            CheckMatrix matrix = builder.fetch();
            assertThat(matrix.allowed("document:doc-1", "view", "user:alice")).isTrue();
            assertThat(matrix.allowed("task:t-1", "complete", "user:alice")).isTrue();
        }
    }

    // ================================================================
    //  CrossResourceBatchBuilder
    // ================================================================

    @Nested
    class CrossResourceBatchBuilderTests {

        @Test
        void on_string_grant_and_revoke() {
            docFactory.resource("doc-1").grant("owner").to("user:alice");

            var builder = new CrossResourceBatchBuilder(transport);
            BatchResult result = builder
                    .on("document", "doc-1")
                        .grant("editor").to("user:bob")
                        .revoke("owner").from("user:alice")
                    .execute();

            assertThat(result.zedToken()).isNotNull();
            assertThat(docFactory.resource("doc-1").check("editor").by("user:bob").hasPermission()).isTrue();
            assertThat(docFactory.resource("doc-1").check("owner").by("user:alice").hasPermission()).isFalse();
        }

        @Test
        void on_resourceHandle() {
            ResourceHandle handle = docFactory.resource("doc-1");
            var builder = new CrossResourceBatchBuilder(transport);
            builder.on(handle).grant("editor").to("user:alice").execute();

            assertThat(docFactory.resource("doc-1").check("editor").by("user:alice").hasPermission()).isTrue();
        }

        @Test
        void on_resourceType() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.on(DOC_TYPE, "doc-1").grant("editor").to("user:alice").execute();

            assertThat(docFactory.resource("doc-1").check("editor").by("user:alice").hasPermission()).isTrue();
        }

        @Test
        void multipleResources() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder
                    .on("document", "doc-1").grant("editor").to("user:alice")
                    .on("document", "doc-2").grant("viewer").to("user:bob")
                    .execute();

            assertThat(docFactory.resource("doc-1").check("editor").by("user:alice").hasPermission()).isTrue();
            assertThat(docFactory.resource("doc-2").check("viewer").by("user:bob").hasPermission()).isTrue();
        }

        @Test
        void grant_toSubjects() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.on("document", "doc-1").grant("viewer").to("group:eng#member").execute();

            assertThat(transport.size()).isEqualTo(1);
        }

        @Test
        void grant_typedRelation() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.on("document", "doc-1").grant(TestRel.EDITOR).to("user:alice").execute();

            assertThat(docFactory.resource("doc-1").check("editor").by("user:alice").hasPermission()).isTrue();
        }

        @Test
        void revoke_typedRelation() {
            docFactory.resource("doc-1").grant("editor").to("user:alice");
            var builder = new CrossResourceBatchBuilder(transport);
            builder.on("document", "doc-1").revoke(TestRel.EDITOR).from("user:alice").execute();

            assertThat(docFactory.resource("doc-1").check("editor").by("user:alice").hasPermission()).isFalse();
        }

        @Test
        void revoke_fromCollection() {
            docFactory.resource("doc-1").grant("editor").to("user:alice");
            docFactory.resource("doc-1").grant("editor").to("user:bob");
            var builder = new CrossResourceBatchBuilder(transport);
            builder.on("document", "doc-1").revoke("editor").from("user:alice", "user:bob").execute();

            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void grant_toCollection() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.on("document", "doc-1").grant("editor").to("user:alice", "user:bob").execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void commit_alias() {
            var builder = new CrossResourceBatchBuilder(transport);
            BatchResult result = builder.on("document", "doc-1").grant("editor").to("user:alice").commit();

            assertThat(result.zedToken()).isNotNull();
        }

        @Test
        void empty_execute() {
            var builder = new CrossResourceBatchBuilder(transport);
            BatchResult result = builder.execute();

            assertThat(result.zedToken()).isNull();
        }

        @Test
        void scope_chaining_on() {
            var builder = new CrossResourceBatchBuilder(transport);
            // Test chaining through ResourceScope.on()
            builder
                    .on("document", "doc-1").grant("editor").to("user:alice")
                    .on("document", "doc-2").grant("viewer").to("user:bob")
                    .on(DOC_TYPE, "doc-3").grant("owner").to("user:carol")
                    .execute();

            assertThat(transport.size()).isEqualTo(3);
        }

        @Test
        void scope_commit_from_resourceScope() {
            var builder = new CrossResourceBatchBuilder(transport);
            BatchResult result = builder.on("document", "doc-1").grant("editor").to("user:alice").commit();

            assertThat(result.zedToken()).isNotNull();
        }

        @Test
        void scope_execute_from_resourceScope() {
            var builder = new CrossResourceBatchBuilder(transport);
            BatchResult result = builder.on("document", "doc-1").grant("editor").to("user:alice").execute();

            assertThat(result.zedToken()).isNotNull();
        }

        // ---- MultiResourceScope tests ----

        @Test
        void onAll_grant_fansAcrossIds() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2", "doc-3"))
                    .grant("viewer").to("user:alice")
                    .execute();

            assertThat(transport.size()).isEqualTo(3);
            assertThat(docFactory.resource("doc-1").check("viewer").by("user:alice").hasPermission()).isTrue();
            assertThat(docFactory.resource("doc-2").check("viewer").by("user:alice").hasPermission()).isTrue();
            assertThat(docFactory.resource("doc-3").check("viewer").by("user:alice").hasPermission()).isTrue();
        }

        @Test
        void onAll_string_grant() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.onAll("document", List.of("doc-1", "doc-2"))
                    .grant("editor").to("user:alice")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void onAll_grant_toSubjects() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2"))
                    .grant("viewer").to("group:eng#member")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void onAll_grant_typedRelation() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2"))
                    .grant(TestRel.EDITOR).to("user:alice")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void onAll_revoke() {
            docFactory.resource("doc-1").grant("editor").to("user:alice");
            docFactory.resource("doc-2").grant("editor").to("user:alice");

            var builder = new CrossResourceBatchBuilder(transport);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2"))
                    .revoke("editor").from("user:alice")
                    .execute();

            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void onAll_revoke_typedRelation() {
            docFactory.resource("doc-1").grant("editor").to("user:alice");
            docFactory.resource("doc-2").grant("editor").to("user:alice");

            var builder = new CrossResourceBatchBuilder(transport);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2"))
                    .revoke(TestRel.EDITOR).from("user:alice")
                    .execute();

            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void onAll_revoke_fromCollection() {
            docFactory.resource("doc-1").grant("editor").to("user:alice");
            docFactory.resource("doc-1").grant("editor").to("user:bob");

            var builder = new CrossResourceBatchBuilder(transport);
            builder.onAll(DOC_TYPE, List.of("doc-1"))
                    .revoke("editor").from("user:alice", "user:bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(0);
        }

        @Test
        void onAll_chaining_to_on() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.onAll(DOC_TYPE, List.of("doc-1", "doc-2"))
                    .grant("viewer").to("user:alice")
                    .on(DOC_TYPE, "doc-3").grant("editor").to("user:bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(3);
        }

        @Test
        void onAll_chaining_to_onAll() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.onAll(DOC_TYPE, List.of("doc-1"))
                    .grant("viewer").to("user:alice")
                    .onAll(DOC_TYPE, List.of("doc-2"))
                    .grant("editor").to("user:bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void multiScope_on_string() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.onAll(DOC_TYPE, List.of("doc-1"))
                    .grant("viewer").to("user:alice")
                    .on("document", "doc-2").grant("editor").to("user:bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(2);
        }

        @Test
        void multiScope_execute_and_commit() {
            var builder = new CrossResourceBatchBuilder(transport);
            BatchResult r1 = builder.onAll(DOC_TYPE, List.of("doc-1")).grant("viewer").to("user:alice").execute();
            assertThat(r1.zedToken()).isNotNull();

            var builder2 = new CrossResourceBatchBuilder(transport);
            BatchResult r2 = builder2.onAll(DOC_TYPE, List.of("doc-2")).grant("viewer").to("user:bob").commit();
            assertThat(r2.zedToken()).isNotNull();
        }

        @Test
        void scope_onAll_from_resourceScope() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.on("document", "doc-1").grant("editor").to("user:alice")
                    .onAll(DOC_TYPE, List.of("doc-2", "doc-3")).grant("viewer").to("user:bob")
                    .execute();

            assertThat(transport.size()).isEqualTo(3);
        }

        @Test
        void scope_onAll_string_from_resourceScope() {
            var builder = new CrossResourceBatchBuilder(transport);
            builder.on("document", "doc-1").grant("editor").to("user:alice")
                    .onAll("document", List.of("doc-2")).grant("viewer").to("user:bob")
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
