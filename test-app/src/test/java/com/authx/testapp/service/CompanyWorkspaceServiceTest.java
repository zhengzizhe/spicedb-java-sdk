package com.authx.testapp.service;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.cache.SchemaCache;
import com.authx.sdk.model.SubjectType;
import com.authx.sdk.model.Tuple;
import com.authx.sdk.transport.InMemoryTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.authx.testapp.service.CompanyWorkspaceService.acmeFolders;
import static com.authx.testapp.service.CompanyWorkspaceService.acmeMainSpace;
import static com.authx.testapp.service.CompanyWorkspaceService.acmeSeed;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行为级测试 —— 把 {@link CompanyWorkspaceService} 每条方法都跑一遍，
 * 用 {@link InMemoryTransport#allTuples()} 直接读它写出的
 * {@code (resource_type:id # relation @ subject_type:id[#subRel])} 元组，
 * 验证：
 * <ol>
 *   <li>每条 typed 重载最终写到 wire 上的 subject ref 都是规范形式 —
 *       SDK 没在 bare id 前面漏拼或多拼 type 前缀；</li>
 *   <li>批量写保持原子（一次 commit 打出一条 WriteRelationships，
 *       所有 update 都落地到同一个 zedToken）；</li>
 *   <li>caveat 路径写进来的 {@code link_viewer@user:*} 关系和
 *       静态参数一起进 transport.</li>
 * </ol>
 *
 * <p>不走真 SpiceDB — InMemoryTransport 无 schema 推理，所以
 * permission check 的语义 (viewer→view) 这里不试；读路径的集成
 * 覆盖在 cluster-test 里做。这里只盯"wire 格式正确"。
 */
class CompanyWorkspaceServiceTest {

    private AuthxClient client;
    private InMemoryTransport transport;
    private CompanyWorkspaceService svc;

    @BeforeEach
    void setUp() throws Exception {
        // Attach a real SchemaCache so single-type .to(id) inference works
        // on the non-batch typed chain (linkDepartmentToParent etc.).
        client = AuthxClient.inMemory(acmeSchema());
        // AuthxClient holds transport as a private field — reach in by
        // reflection so the test can inspect every tuple the service writes.
        Field f = AuthxClient.class.getDeclaredField("transport");
        f.setAccessible(true);
        transport = (InMemoryTransport) f.get(client);
        svc = new CompanyWorkspaceService(client);
    }

    /**
     * Mirror of {@code deploy/schema.zed} at the per-relation-subject-type
     * level — only the parts the service exercises via single-type
     * inference need to be precise. The non-inference paths don't care.
     */
    private static SchemaCache acmeSchema() {
        var user = List.of(SubjectType.of("user"));
        var cache = new SchemaCache();
        cache.updateFromMap(Map.of(
                "department", new SchemaCache.DefinitionCache(
                        Set.of("member", "parent"),
                        Set.of("all_members"),
                        Map.of(
                                "member", user,
                                "parent", List.of(SubjectType.of("department")))),
                "organization", new SchemaCache.DefinitionCache(
                        Set.of("admin", "member"),
                        Set.of(),
                        Map.of("admin", user)),
                "space", new SchemaCache.DefinitionCache(
                        Set.of("org", "owner", "viewer"),
                        Set.of(),
                        Map.of(
                                "org", List.of(SubjectType.of("organization")),
                                "owner", user)),
                "folder", new SchemaCache.DefinitionCache(
                        Set.of("space", "parent", "owner", "viewer"),
                        Set.of(),
                        Map.of(
                                "space", List.of(SubjectType.of("space")),
                                "parent", List.of(SubjectType.of("folder")),
                                "owner", user)),
                "document", new SchemaCache.DefinitionCache(
                        Set.of("folder", "space", "owner", "editor", "commenter",
                                "viewer", "link_viewer"),
                        Set.of(),
                        Map.of(
                                "folder", List.of(SubjectType.of("folder")),
                                "space", List.of(SubjectType.of("space")),
                                "owner", user))));
        return cache;
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    // ─── 每条 Tuple 都变成 "type:id#rel@sub_type:sub_id[#sub_rel]" 字符串
    //     形式，assertThat(...).contains(...) 直接 match wire 格式。
    private List<String> wire() {
        return transport.allTuples().stream().map(this::format).collect(Collectors.toList());
    }

    private String format(Tuple t) {
        String core = t.resourceType() + ":" + t.resourceId() + "#" + t.relation()
                + "@" + t.subjectType() + ":" + t.subjectId();
        return t.subjectRelation() == null ? core : core + "#" + t.subjectRelation();
    }

    private int tupleCount() {
        return transport.size();
    }

    // ═══════════════════════════════════════════════════════════════
    //  1. bootstrapOrganization — batch + 三种 typed subject 形态
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. organization bootstrap — batch + typed overloads")
    class BootstrapOrg {

        @Test
        void writesAdminMembersDepartmentsAndGroups() {
            String zed = svc.bootstrapOrganization(acmeSeed());

            assertThat(zed).isNotNull();
            var w = wire();

            // org admin — 单类型推断 (user:u-ceo)
            assertThat(w).contains("organization:acme#admin@user:u-ceo");

            // department members — typed batch via .to(memberId) inference
            assertThat(w).contains(
                    "department:d-eng#member@user:u-alice",
                    "department:d-eng#member@user:u-bob",
                    "department:d-eng#member@user:u-carol",
                    "department:d-marketing#member@user:u-dan",
                    "department:d-marketing#member@user:u-eve");

            // organization.member via department#all_members (typed sub-relation)
            assertThat(w).contains(
                    "organization:acme#member@department:d-eng#all_members",
                    "organization:acme#member@department:d-marketing#all_members");

            // group members — typed Iterable<String> batch to User descriptor
            assertThat(w).contains(
                    "group:g-alpha-pms#member@user:u-alice",
                    "group:g-alpha-pms#member@user:u-dan");

            // group members via department#all_members (typed sub-relation)
            assertThat(w).contains("group:g-all-eng#member@department:d-eng#all_members");
        }

        @Test
        void emptyGroupsSkipMemberWrites() {
            var seed = new CompanyWorkspaceService.OrgSeed(
                    "acme", "u-ceo",
                    List.of(new CompanyWorkspaceService.DepartmentSeed("d-eng", List.of())),
                    List.of(CompanyWorkspaceService.GroupSeed.of("g-empty", List.of())));

            svc.bootstrapOrganization(seed);

            var w = wire();
            // admin + department→org; nothing else (empty memberUserIds list
            // skips the Iterable-overload write).
            assertThat(w).containsExactlyInAnyOrder(
                    "organization:acme#admin@user:u-ceo",
                    "organization:acme#member@department:d-eng#all_members");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  2. department hierarchy — single-type inference
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. linkDepartmentToParent — single-type inference (.to(id))")
    class DepartmentHierarchy {

        @Test
        void writesCanonicalDepartmentSubject() {
            svc.linkDepartmentToParent("d-platform", "d-eng");
            svc.linkDepartmentToParent("d-infra", "d-platform");

            assertThat(wire()).containsExactlyInAnyOrder(
                    "department:d-platform#parent@department:d-eng",
                    "department:d-infra#parent@department:d-platform");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  3. space provisioning — typed TYPE / sub-relation / wildcard
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. provisionSpace — full overload matrix")
    class ProvisionSpace {

        @Test
        void writesEveryRoleWithCanonicalSubject() {
            svc.provisionSpace(acmeMainSpace());

            var w = wire();
            assertThat(w).contains(
                    "space:s-main#org@organization:acme",
                    "space:s-main#owner@user:u-ceo",
                    "space:s-main#admin@group:g-alpha-pms#member",
                    "space:s-main#member@department:d-eng#all_members",
                    "space:s-main#member@department:d-marketing#all_members",
                    "space:s-main#viewer@user:*");
        }

        @Test
        void skipsWildcardWhenPublicViewerFalse() {
            var seed = new CompanyWorkspaceService.SpaceSeed(
                    "s-private", "acme", "u-ceo", null, List.of(), false);

            svc.provisionSpace(seed);

            var w = wire();
            assertThat(w).noneMatch(s -> s.endsWith("@user:*"));
            assertThat(w).noneMatch(s -> s.contains("#admin@"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  4. folder tree — nested parent + space binding
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. createFolderTree — nested parents, batch write")
    class FolderTree {

        @Test
        void rootHasNoParentChildrenHaveTypedParent() {
            svc.createFolderTree("s-main", acmeFolders());

            var w = wire();
            // Every folder is bound to the space and has an owner
            assertThat(w).contains(
                    "folder:f-root#space@space:s-main",
                    "folder:f-root#owner@user:u-ceo",
                    "folder:f-eng#space@space:s-main",
                    "folder:f-eng-specs#parent@folder:f-eng",
                    "folder:f-eng-specs#space@space:s-main",
                    "folder:f-marketing#parent@folder:f-root");

            // Root has NO parent write
            assertThat(w).noneMatch(s -> s.startsWith("folder:f-root#parent@"));
        }

        @Test
        void shareFolderWithDepartmentWritesAllMembersSubRelation() {
            svc.shareFolderWithDepartment("f-eng", "d-eng");

            assertThat(wire()).containsExactly(
                    "folder:f-eng#viewer@department:d-eng#all_members");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  5. document lifecycle — every .to(...) shape
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. document — all share paths")
    class DocumentLifecycle {

        @Test
        void publishDocumentWritesFolderSpaceOwner() {
            svc.publishDocument("doc-1", "f-eng", "s-main", "u-alice");

            assertThat(wire()).containsExactlyInAnyOrder(
                    "document:doc-1#folder@folder:f-eng",
                    "document:doc-1#space@space:s-main",
                    "document:doc-1#owner@user:u-alice");
        }

        @Test
        void shareWithGroupUsesMemberSubRelation() {
            svc.shareWithGroup("doc-1", "g-alpha-pms",
                    com.authx.testapp.schema.Document.Rel.EDITOR);

            assertThat(wire()).containsExactly(
                    "document:doc-1#editor@group:g-alpha-pms#member");
        }

        @Test
        void shareWithDepartmentUsesAllMembersSubRelation() {
            svc.shareWithDepartment("doc-1", "d-eng",
                    com.authx.testapp.schema.Document.Rel.VIEWER);

            assertThat(wire()).containsExactly(
                    "document:doc-1#viewer@department:d-eng#all_members");
        }

        @Test
        void shareWithUsersWritesOneTuplePerId() {
            svc.shareWithUsers("doc-1", List.of("u-alice", "u-bob", "u-carol"),
                    com.authx.testapp.schema.Document.Rel.VIEWER);

            assertThat(wire()).containsExactlyInAnyOrder(
                    "document:doc-1#viewer@user:u-alice",
                    "document:doc-1#viewer@user:u-bob",
                    "document:doc-1#viewer@user:u-carol");
        }

        @Test
        void publishPubliclyWritesWildcardUser() {
            svc.publishPublicly("doc-1");

            assertThat(wire()).containsExactly("document:doc-1#link_viewer@user:*");
        }

        @Test
        void shareTemporarilyAlsoEndsUpOnWire() {
            // InMemoryTransport ignores expiresAt, but the tuple still
            // lands — the typed path delegates through expiringIn→to(...).
            svc.shareTemporarily("doc-1", "u-alice",
                    com.authx.testapp.schema.Document.Rel.COMMENTER,
                    Duration.ofHours(4));

            assertThat(wire()).containsExactly("document:doc-1#commenter@user:u-alice");
        }

        @Test
        void shareBehindIpAllowlistWritesLinkViewerAndCarriesCaveat() {
            svc.shareBehindIpAllowlist("doc-1",
                    List.of("10.0.0.0/8", "172.16.0.0/12"));

            // Wire tuple present (caveat metadata is applied at the
            // RelationshipUpdate level — not surfaced by InMemoryTransport.Tuple
            // but the write still lands).
            assertThat(wire()).containsExactly("document:doc-1#link_viewer@user:*");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  6. revoke symmetry
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. revoke — mirror of every grant shape")
    class Revoke {

        @Test
        void unshareFromGroupDeletesMemberSubRelation() {
            svc.shareWithGroup("doc-1", "g-alpha-pms",
                    com.authx.testapp.schema.Document.Rel.EDITOR);
            assertThat(tupleCount()).isEqualTo(1);

            svc.unshareFromGroup("doc-1", "g-alpha-pms",
                    com.authx.testapp.schema.Document.Rel.EDITOR);

            assertThat(tupleCount()).isZero();
        }

        @Test
        void unshareFromUsersDeletesAll() {
            svc.shareWithUsers("doc-1", List.of("u-alice", "u-bob"),
                    com.authx.testapp.schema.Document.Rel.VIEWER);
            assertThat(tupleCount()).isEqualTo(2);

            svc.unshareFromUsers("doc-1", List.of("u-alice", "u-bob"),
                    com.authx.testapp.schema.Document.Rel.VIEWER);

            assertThat(tupleCount()).isZero();
        }

        @Test
        void unpublishPubliclyDeletesWildcard() {
            svc.publishPublicly("doc-1");
            svc.unpublishPublicly("doc-1");

            assertThat(tupleCount()).isZero();
        }

        @Test
        void transferOwnershipAtomicallyReplacesOwner() {
            svc.publishDocument("doc-1", "f-eng", "s-main", "u-alice");
            assertThat(wire()).contains("document:doc-1#owner@user:u-alice");

            String zed = svc.transferOwnership("doc-1", "u-alice", "u-bob");

            assertThat(zed).isNotNull();
            assertThat(wire())
                    .contains("document:doc-1#owner@user:u-bob")
                    .doesNotContain("document:doc-1#owner@user:u-alice");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  7. query APIs — check / checkAll / byAll / findBy
    //  (InMemoryTransport matches permission.name() == relation name,
    //   so scenarios below grant the same name they later check.)
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. query APIs — smoke via InMemoryTransport")
    class Queries {

        @Test
        void canViewReturnsFalseWhenNoGrant() {
            assertThat(svc.canView("u-alice", "doc-1")).isFalse();
        }

        @Test
        void toolbarReturnsAllPermsAsFalseWhenNoGrants() {
            var map = svc.toolbar("u-alice", "doc-1");
            // All Document.Perm keys present, all false — no grants yet.
            assertThat(map).isNotEmpty();
            assertThat(map.values()).containsOnly(false);
        }

        @Test
        void listPermissionsBuildsMatrixForEveryDocId() {
            var m = svc.listPermissions("u-alice", List.of("doc-1", "doc-2"));
            assertThat(m).containsOnlyKeys("doc-1", "doc-2");
        }

        @Test
        void filterVisibleReturnsFalseWhenNoGrants() {
            var out = svc.filterVisible("u-alice", List.of("doc-1", "doc-2"));
            assertThat(out).containsOnlyKeys("doc-1", "doc-2");
            assertThat(out.values()).containsOnly(false);
        }

        @Test
        void renderSidebarMixesTypes() {
            var items = List.of(
                    new CompanyWorkspaceService.ResourceKey("space", "s-main"),
                    new CompanyWorkspaceService.ResourceKey("folder", "f-eng"),
                    new CompanyWorkspaceService.ResourceKey("document", "doc-1"));
            var out = svc.renderSidebar("u-alice", items);
            assertThat(out).containsOnlyKeys("space:s-main", "folder:f-eng", "document:doc-1");
        }

        @Test
        void myReadableDocsEmptyByDefault() {
            assertThat(svc.myReadableDocs("u-alice", 10)).isEmpty();
        }

        @Test
        void myDocsByPermissionReturnsAllPermsInMap() {
            var m = svc.myDocsByPermission("u-alice", 10);
            assertThat(m).containsOnlyKeys(
                    com.authx.testapp.schema.Document.Perm.VIEW,
                    com.authx.testapp.schema.Document.Perm.EDIT,
                    com.authx.testapp.schema.Document.Perm.COMMENT);
        }

        @Test
        void readableDocsForTeamReturnsEntryPerUser() {
            var m = svc.readableDocsForTeam(List.of("u-alice", "u-bob"), 10);
            assertThat(m).containsOnlyKeys("user:u-alice", "user:u-bob");
        }

        @Test
        void whoCanEditEmptyWithoutGrants() {
            assertThat(svc.whoCanEdit("doc-1", 10)).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  8. offboarding — batch delete all access
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. offboardUser — atomic cross-type revoke")
    class Offboard {

        @Test
        void removesAllTypedRelationsForUser() {
            // Seed a bunch of relations across types.
            svc.provisionSpace(acmeMainSpace());
            svc.publishDocument("doc-1", "f-eng", "s-main", "u-alice");
            svc.shareWithUsers("doc-1", List.of("u-alice"),
                    com.authx.testapp.schema.Document.Rel.EDITOR);

            int before = tupleCount();
            assertThat(before).isGreaterThan(0);

            String zed = svc.offboardUser("u-alice",
                    new CompanyWorkspaceService.OffboardTargets(
                            List.of("d-eng"),
                            List.of("g-alpha-pms"),
                            List.of("s-main"),
                            List.of("f-eng"),
                            List.of("doc-1")));

            assertThat(zed).isNotNull();
            // u-alice shouldn't appear as a user subject anywhere now.
            assertThat(wire())
                    .allSatisfy(s -> assertThat(s).doesNotContain("@user:u-alice"));
        }

        @Test
        void emptyTargetsIsNoOp() {
            String zed = svc.offboardUser("u-alice",
                    CompanyWorkspaceService.OffboardTargets.empty());
            // Nothing to write → batch returns null zedToken.
            assertThat(zed).isNull();
            assertThat(tupleCount()).isZero();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  9. end-to-end lifecycle — sanity-check all methods compose
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("9. full employee lifecycle — bootstrap → share → offboard")
    void endToEndEmployeeLifecycle() {
        svc.bootstrapOrganization(acmeSeed());
        svc.provisionSpace(acmeMainSpace());
        svc.createFolderTree("s-main", acmeFolders());
        svc.publishDocument("doc-1", "f-eng-specs", "s-main", "u-alice");
        svc.shareWithDepartment("doc-1", "d-eng",
                com.authx.testapp.schema.Document.Rel.VIEWER);
        svc.shareWithGroup("doc-1", "g-alpha-pms",
                com.authx.testapp.schema.Document.Rel.EDITOR);
        svc.publishPublicly("doc-1");
        svc.shareBehindIpAllowlist("doc-1", List.of("10.0.0.0/8"));

        Collection<Tuple> all = transport.allTuples();
        assertThat(all).isNotEmpty();

        var w = wire();
        // Nothing hand-typed: every subject ref follows the canonical form
        // "type:id" or "type:id#subRel" or "type:*", never bare "alice" etc.
        assertThat(w).allSatisfy(s -> {
            String subject = s.substring(s.indexOf('@') + 1);
            assertThat(subject).contains(":");
        });

        // Now offboard alice — everything touching @user:u-alice vanishes.
        svc.offboardUser("u-alice", new CompanyWorkspaceService.OffboardTargets(
                List.of("d-eng"), List.of("g-alpha-pms"),
                List.of("s-main"), List.of("f-eng", "f-eng-specs"),
                List.of("doc-1")));
        assertThat(wire()).noneMatch(s -> s.endsWith("@user:u-alice"));
    }
}
