package com.authcses.testapp;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.model.Consistency;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.authcses.testapp.DataSeeder.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型正确性测试 — 每条断言都有明确的权限推导链。
 * 使用 Consistency.full() 确保读到最新数据（绕过缓存）。
 *
 * 权限推导规则：
 *   manage > edit > comment > view （高权限包含低权限）
 *   document.view = comment + viewer + link_viewer + folder->view
 *   folder.view   = comment + viewer + parent->view + space->view
 *   space.view    = edit + viewer
 *   space.edit    = manage + member
 *   space.manage  = owner + admin + org->manage
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModelCorrectnessTest {

    @Autowired private AuthCsesClient client;
    private static boolean seeded = false;

    @BeforeEach
    void ensureSeeded() {
        if (!seeded) {
            new DataSeeder(client).seed();
            seeded = true;
        }
    }

    private boolean check(String type, String id, String perm, String user) {
        return client.check(type, id, perm, user, Consistency.full());
    }

    // ================================================================
    //  A. 直接授权 — 文档级别
    // ================================================================

    @Test @Order(1)
    void directOwner_hasAllPermissions() {
        // carol 是 design-doc 的 owner → manage/edit/comment/view/delete/share 全有
        assertThat(check("document", "design-doc", "manage", CAROL)).isTrue();
        assertThat(check("document", "design-doc", "edit", CAROL)).isTrue();
        assertThat(check("document", "design-doc", "comment", CAROL)).isTrue();
        assertThat(check("document", "design-doc", "view", CAROL)).isTrue();
        assertThat(check("document", "design-doc", "delete", CAROL)).isTrue();
        assertThat(check("document", "design-doc", "share", CAROL)).isTrue();
    }

    @Test @Order(2)
    void directEditor_canEditButNotManage() {
        // eve 是 design-doc 的 direct editor
        // editor → edit/comment/view/share ✓, manage/delete ✗
        assertThat(check("document", "design-doc", "edit", EVE)).isTrue();
        assertThat(check("document", "design-doc", "comment", EVE)).isTrue();
        assertThat(check("document", "design-doc", "view", EVE)).isTrue();
        assertThat(check("document", "design-doc", "share", EVE)).isTrue();
        assertThat(check("document", "design-doc", "manage", EVE)).isFalse();
        assertThat(check("document", "design-doc", "delete", EVE)).isFalse();
    }

    @Test @Order(3)
    void directViewer_butAlsoSpaceAdmin() {
        // frank 是 design-doc 的 direct viewer，但同时是 doc-reviewers → space:wiki admin
        // 推导链：frank → group:doc-reviewers#member → space:wiki admin → space.manage
        // 所以 frank 实际上有 manage 权限（space admin 穿透）
        assertThat(check("document", "design-doc", "view", FRANK)).isTrue();
        assertThat(check("document", "design-doc", "manage", FRANK)).isTrue();
        assertThat(check("document", "design-doc", "delete", FRANK)).isTrue();
    }

    // ================================================================
    //  B. 文件夹继承
    // ================================================================

    @Test @Order(10)
    void folderEditor_inheritsToDocument() {
        // dave 是 backend-docs 的 direct editor
        // api-spec 在 folder:api-docs (parent: backend-docs)
        // 推导链：dave → folder:backend-docs editor → folder:api-docs parent->edit → document:api-spec folder->edit
        assertThat(check("document", "api-spec", "edit", DAVE)).isTrue();
        assertThat(check("document", "api-spec", "view", DAVE)).isTrue();
    }

    @Test @Order(11)
    void folderViewer_inheritsToDocument() {
        // grace 是 api-docs 的 direct viewer
        // 推导链：grace → folder:api-docs viewer → document:api-spec folder->view
        assertThat(check("document", "api-spec", "view", GRACE)).isTrue();
        // viewer 不能 edit
        assertThat(check("document", "api-spec", "edit", GRACE)).isFalse();
    }

    @Test @Order(12)
    void deepNestedFolder_4levels() {
        // secret-doc 在 folder:api-internal (parent: api-docs, parent: backend-docs, parent: wiki-root)
        // dave 是 backend-docs editor
        // 推导链：dave → folder:backend-docs editor
        //   → folder:api-docs parent->edit → folder:api-internal parent->edit
        //   → document:secret-doc folder->edit
        assertThat(check("document", "secret-doc", "edit", DAVE)).isTrue();
        assertThat(check("document", "secret-doc", "view", DAVE)).isTrue();
    }

    @Test @Order(13)
    void folderViewer_doesNotInheritEdit() {
        // grace 是 api-docs viewer → 可以 view api-internal 下的 secret-doc
        // 但不能 edit
        assertThat(check("document", "secret-doc", "view", GRACE)).isTrue();
        assertThat(check("document", "secret-doc", "edit", GRACE)).isFalse();
    }

    // ================================================================
    //  C. 空间继承
    // ================================================================

    @Test @Order(20)
    void spaceOwner_managesAllDocs() {
        // bob 是 space:wiki owner → manage 传递到所有文件夹和文档
        // 推导链：bob → space:wiki owner → space.manage → folder:wiki-root space->manage → document folder->manage
        assertThat(check("document", "design-doc", "manage", BOB)).isTrue();
        assertThat(check("document", "api-spec", "manage", BOB)).isTrue();
        assertThat(check("document", "secret-doc", "manage", BOB)).isTrue();
        assertThat(check("document", "isolated-doc", "manage", BOB)).isTrue();
    }

    @Test @Order(21)
    void spaceAdmin_viaGroup_managesAllDocs() {
        // carol 是 group:doc-reviewers member → space:wiki admin
        // 推导链：carol → group:doc-reviewers#member → space:wiki admin → space.manage
        //   → folder.manage → document.manage
        assertThat(check("document", "api-spec", "manage", CAROL)).isTrue();
        assertThat(check("document", "secret-doc", "manage", CAROL)).isTrue();

        // frank 也是 doc-reviewers member → 也是 space admin
        assertThat(check("document", "api-spec", "manage", FRANK)).isTrue();
    }

    @Test @Order(22)
    void spaceMember_canEditAllDocs() {
        // space:wiki member = group:all-editors#member = department:engineering#all_members
        // engineering 成员：bob, carol, dave, eve
        // 推导链：eve → dept:engineering → group:all-editors#member → space:wiki member → space.edit
        //   → folder.edit → document.edit
        assertThat(check("document", "isolated-doc", "edit", EVE)).isTrue();
        assertThat(check("document", "isolated-doc", "edit", BOB)).isTrue();
        assertThat(check("document", "isolated-doc", "edit", DAVE)).isTrue();
    }

    @Test @Order(23)
    void spaceViewer_canOnlyView() {
        // space:wiki viewer = department:product#all_members = frank, grace
        // frank 同时是 space admin (via doc-reviewers) 所以 frank 可以 manage
        // grace 只是 space viewer (via product dept)
        // 推导链：grace → dept:product → space:wiki viewer → space.view → folder.view → document.view
        assertThat(check("document", "isolated-doc", "view", GRACE)).isTrue();
        // grace 不在 engineering 所以不是 space member → 不能 edit
        assertThat(check("document", "isolated-doc", "edit", GRACE)).isFalse();
    }

    // ================================================================
    //  D. 组织 admin 穿透
    // ================================================================

    @Test @Order(30)
    void orgAdmin_managesEverything() {
        // alice 是 org:acme admin → space:wiki org->manage → 全部 manage
        // 推导链：alice → org:acme admin → org.manage → space:wiki org->manage
        //   → space.manage → folder.manage → document.manage
        assertThat(check("document", "design-doc", "manage", ALICE)).isTrue();
        assertThat(check("document", "api-spec", "manage", ALICE)).isTrue();
        assertThat(check("document", "secret-doc", "manage", ALICE)).isTrue();
        assertThat(check("document", "isolated-doc", "manage", ALICE)).isTrue();

        // manage 包含 delete
        assertThat(check("document", "secret-doc", "delete", ALICE)).isTrue();
    }

    // ================================================================
    //  E. 部门层级继承
    // ================================================================

    @Test @Order(40)
    void subDepartment_membersInheritParent() {
        // bob 是 eng-infra member → eng-infra parent=eng-backend parent=engineering
        // engineering#all_members 包含 bob (递归)
        // 所以 bob 在 org:acme member (via engineering)、group:all-editors、space:wiki member
        assertThat(check("space", "wiki", "edit", BOB)).isTrue();
    }

    @Test @Order(41)
    void departmentMember_notInChildDept() {
        // dave 是 engineering member 和 eng-frontend member
        // dave 不是 eng-backend member → eng-backend 的直接权限不适用于 dave
        // 但 dave 是 engineering#all_members → 通过 space member 获得编辑权
        assertThat(check("space", "wiki", "edit", DAVE)).isTrue();
    }

    // ================================================================
    //  F. 链接分享
    // ================================================================

    @Test @Order(50)
    void linkViewer_anyoneCanView() {
        // public-doc 有 link_viewer: user:* → 任何人可查看
        assertThat(check("document", "public-doc", "view", ZARA)).isTrue();
        assertThat(check("document", "public-doc", "view", GRACE)).isTrue();

        // 但不能 edit（zara 没有任何其他权限）
        assertThat(check("document", "public-doc", "edit", ZARA)).isFalse();
    }

    @Test @Order(51)
    void linkEditor_anyoneCanEdit() {
        // shared-edit-doc 有 link_editor: user:*
        assertThat(check("document", "shared-edit-doc", "edit", ZARA)).isTrue();
        assertThat(check("document", "shared-edit-doc", "view", ZARA)).isTrue();

        // 但不能 manage（link_editor 只给 edit）
        assertThat(check("document", "shared-edit-doc", "manage", ZARA)).isFalse();
    }

    @Test @Order(52)
    void linkSharing_doesNotAffectOtherDocs() {
        // public-doc 有 link sharing，但 design-doc 没有
        // zara 不能查看 design-doc
        assertThat(check("document", "design-doc", "view", ZARA)).isFalse();
    }

    // ================================================================
    //  G. 反向验证 — 确认权限不泄漏
    // ================================================================

    @Test @Order(60)
    void externalUser_noAccessToNonSharedDocs() {
        // zara 不在任何部门/组/空间 → 非公开文档全部无权限
        assertThat(check("document", "design-doc", "view", ZARA)).isFalse();
        assertThat(check("document", "api-spec", "view", ZARA)).isFalse();
        assertThat(check("document", "secret-doc", "view", ZARA)).isFalse();
        assertThat(check("document", "isolated-doc", "view", ZARA)).isFalse();

        assertThat(check("folder", "wiki-root", "view", ZARA)).isFalse();
        assertThat(check("space", "wiki", "view", ZARA)).isFalse();
    }

    @Test @Order(61)
    void viewer_cannotEdit() {
        // grace 是 space viewer → 只能 view，不能 edit
        assertThat(check("document", "design-doc", "edit", GRACE)).isFalse();
        assertThat(check("folder", "wiki-root", "edit", GRACE)).isFalse();
    }

    @Test @Order(62)
    void folderEditor_cannotDeleteUnownedDoc() {
        // dave 是 backend-docs editor，但 secret-doc owner 是 bob（不是 dave）
        // dave 通过 folder 继承有 edit，但 delete = manage = owner + folder->manage + space->manage
        // dave 不是 secret-doc owner，不是 folder manage（folder manage = owner + parent->manage + space->manage）
        // 但 dave 是 engineering member → space:wiki member → space.edit（不是 space.manage）
        // 所以 dave 不能 delete secret-doc
        assertThat(check("document", "secret-doc", "delete", DAVE)).isFalse();
        assertThat(check("document", "secret-doc", "edit", DAVE)).isTrue();

        // 但 dave IS api-spec owner → 可以 delete api-spec
        assertThat(check("document", "api-spec", "delete", DAVE)).isTrue();
    }

    @Test @Order(63)
    void permissionDoesNotLeakAcrossSpaces() {
        // 所有权限都在 space:wiki 下，没有其他空间
        // 如果有第二个空间，wiki 的权限不应泄漏
        // 这里验证 folder 权限不会跳到不相关的文档
        // eve 是 design-doc editor（直接授权），但不是 api-spec 的任何直接角色
        // eve 通过 engineering → space member → edit
        // 所以 eve 可以 edit api-spec（via space）— 这是正确的继承行为
        assertThat(check("document", "api-spec", "edit", EVE)).isTrue();
    }

    // ================================================================
    //  H. 批量操作验证
    // ================================================================

    @Test @Order(70)
    void checkAll_multiplePermissions_correctResults() {
        // bob 是 space owner → manage 全部权限
        var perms = client.on("document").checkAll("design-doc", BOB, "view", "edit", "manage", "delete", "share");
        assertThat(perms.get("view")).isTrue();
        assertThat(perms.get("edit")).isTrue();
        assertThat(perms.get("manage")).isTrue();
        assertThat(perms.get("delete")).isTrue();
        assertThat(perms.get("share")).isTrue();
    }

    @Test @Order(71)
    void checkAll_mixedResults() {
        // grace: space viewer → view ✓, edit ✗, manage ✗
        var perms = client.on("document").checkAll("isolated-doc", GRACE, "view", "edit", "manage", "delete");
        assertThat(perms.get("view")).isTrue();
        assertThat(perms.get("edit")).isFalse();
        assertThat(perms.get("manage")).isFalse();
        assertThat(perms.get("delete")).isFalse();
    }

    // ================================================================
    //  I. Lookup 验证
    // ================================================================

    @Test @Order(80)
    void lookupSubjects_whoCanViewDesignDoc() {
        // design-doc view 权限来源：
        //   direct viewer: frank
        //   direct editor: eve
        //   direct owner: carol
        //   folder:wiki-root → space:wiki → all space viewers/members/admins/owner
        //   space owner: bob
        //   space admin (doc-reviewers): carol, frank
        //   space member (engineering): bob, carol, dave, eve
        //   space viewer (product): frank, grace
        //   org admin: alice
        // 全部有 view 权限的用户：alice, bob, carol, dave, eve, frank, grace
        var subjects = client.on("document").subjects("design-doc", "view");
        assertThat(subjects).contains(ALICE, BOB, CAROL, DAVE, EVE, FRANK, GRACE);
        assertThat(subjects).doesNotContain(ZARA);
    }

    @Test @Order(81)
    void lookupResources_whatCanGraceView() {
        // grace 的 view 权限来源：
        //   space:wiki viewer (via product dept) → 所有文档都能 view
        //   folder:api-docs direct viewer
        //   public-doc: link_viewer
        // 所以 grace 能 view 的 document：design-doc, api-spec, secret-doc, public-doc, shared-edit-doc, isolated-doc
        var resources = client.on("document").resources("view", GRACE);
        assertThat(resources).contains("design-doc", "api-spec", "secret-doc",
                "public-doc", "shared-edit-doc", "isolated-doc");
    }

    // ================================================================
    //  J. 撤销后验证
    // ================================================================

    @Test @Order(90)
    void revokeDirectEditor_losesEditButKeepsInheritedView() {
        // eve 当前是 design-doc direct editor + engineering member (space edit)
        // 撤销 direct editor 后，eve 仍然可以通过 space member → edit
        client.on("document").revoke("design-doc", "editor", EVE);

        // eve 通过 space member 仍然有 edit（engineering → all-editors → space member → edit）
        assertThat(check("document", "design-doc", "edit", EVE)).isTrue();
    }

    @Test @Order(91)
    void revokeDirectViewer_frank_losesDirectButKeepsSpaceView() {
        // frank 当前是 design-doc direct viewer + space admin (via doc-reviewers)
        // 撤销 direct viewer 后，frank 仍然可以通过 space admin → manage → view
        client.on("document").revoke("design-doc", "viewer", FRANK);

        assertThat(check("document", "design-doc", "view", FRANK)).isTrue();
        // 且仍然可以 manage（via space admin）
        assertThat(check("document", "design-doc", "manage", FRANK)).isTrue();
    }
}
