package com.authcses.testapp;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.sdk.model.Consistency;

import java.util.*;

/**
 * 确定性数据构建器 — 每条关系都可追溯，每个权限预期都能独立推导。
 *
 * 组织结构：
 *
 *   organization: acme
 *     admin: [alice]
 *     member: department:engineering#all_members, department:product#all_members
 *
 *   department:engineering
 *     member: [bob, carol, dave, eve]
 *     └── department:eng-backend
 *           member: [bob, carol]
 *           └── department:eng-infra
 *                 member: [bob]
 *     └── department:eng-frontend
 *           member: [dave, eve]
 *
 *   department:product
 *     member: [frank, grace]
 *
 *   group:doc-reviewers
 *     member: [carol, frank]
 *
 *   group:all-editors
 *     member: department:engineering#all_members  (即 bob,carol,dave,eve 都是)
 *
 * 空间/文件夹/文档：
 *
 *   space:wiki
 *     org: organization:acme
 *     owner: bob
 *     admin: group:doc-reviewers#member  (carol, frank)
 *     member: group:all-editors#member   (engineering 全员)
 *     viewer: department:product#all_members  (frank, grace)
 *
 *   folder:wiki-root (space:wiki)
 *     └── folder:backend-docs (parent: wiki-root)
 *           editor: [dave]
 *           └── folder:api-docs (parent: backend-docs)
 *                 viewer: [grace]
 *                 └── folder:api-internal (parent: api-docs)
 *
 *   document:design-doc (folder: wiki-root)
 *     owner: carol
 *     editor: [eve]
 *     viewer: [frank]
 *
 *   document:api-spec (folder: api-docs)
 *     owner: dave
 *     (no direct editor/viewer — all permissions come from inheritance)
 *
 *   document:secret-doc (folder: api-internal)
 *     owner: bob
 *     (deepest nesting — tests 4-level folder inheritance)
 *
 *   document:public-doc (folder: wiki-root)
 *     owner: bob
 *     link_viewer: user:*   (任何人通过链接可查看)
 *
 *   document:shared-edit-doc (folder: wiki-root)
 *     owner: carol
 *     link_editor: user:*   (任何人通过链接可编辑)
 *
 *   document:isolated-doc (folder: wiki-root)
 *     owner: alice
 *     (no extra grants — only org admin + space inheritance)
 *
 * 额外测试用户：
 *   zara — 不在任何部门/组/空间，纯外部用户
 */
public class DataSeeder {

    private final AuthCsesClient client;

    // 固定用户
    public static final String ALICE = "alice";   // org admin
    public static final String BOB = "bob";       // eng > eng-backend > eng-infra, space owner
    public static final String CAROL = "carol";   // eng > eng-backend, doc-reviewers, design-doc owner
    public static final String DAVE = "dave";     // eng > eng-frontend, backend-docs editor, api-spec owner
    public static final String EVE = "eve";       // eng > eng-frontend, design-doc editor
    public static final String FRANK = "frank";   // product, doc-reviewers, design-doc viewer
    public static final String GRACE = "grace";   // product, api-docs viewer
    public static final String ZARA = "zara";     // 外部用户，无任何关系

    public static final String[] ALL_USERS = {ALICE, BOB, CAROL, DAVE, EVE, FRANK, GRACE, ZARA};

    public DataSeeder(AuthCsesClient client) {
        this.client = client;
    }

    public void seed() {
        long start = System.currentTimeMillis();
        System.out.println("=== Seeding deterministic test data ===");

        seedDepartments();
        seedGroups();
        seedOrganization();
        seedSpace();
        seedFolders();
        seedDocuments();

        System.out.printf("Seeded in %dms%n", System.currentTimeMillis() - start);
    }

    private void seedDepartments() {
        var dept = client.on("department");

        // engineering (top) — members: bob, carol, dave, eve
        dept.grant("engineering", "member", BOB, CAROL, DAVE, EVE);

        // eng-backend (child of engineering) — members: bob, carol
        dept.grant("eng-backend", "member", BOB, CAROL);
        dept.grantToSubjects("eng-backend", "parent", "department:engineering");

        // eng-infra (child of eng-backend) — member: bob
        dept.grant("eng-infra", "member", BOB);
        dept.grantToSubjects("eng-infra", "parent", "department:eng-backend");

        // eng-frontend (child of engineering) — members: dave, eve
        dept.grant("eng-frontend", "member", DAVE, EVE);
        dept.grantToSubjects("eng-frontend", "parent", "department:engineering");

        // product (top) — members: frank, grace
        dept.grant("product", "member", FRANK, GRACE);
    }

    private void seedGroups() {
        var group = client.on("group");

        // doc-reviewers: carol, frank
        group.grant("doc-reviewers", "member", CAROL, FRANK);

        // all-editors: entire engineering department
        group.grantToSubjects("all-editors", "member", "department:engineering#all_members");
    }

    private void seedOrganization() {
        var org = client.on("organization");

        org.grant("acme", "admin", ALICE);
        org.grantToSubjects("acme", "member",
                "department:engineering#all_members",
                "department:product#all_members");
    }

    private void seedSpace() {
        var space = client.on("space");

        // space:wiki
        space.grantToSubjects("wiki", "org", "organization:acme");
        space.grant("wiki", "owner", BOB);
        space.grantToSubjects("wiki", "admin", "group:doc-reviewers#member");
        space.grantToSubjects("wiki", "member", "group:all-editors#member");
        space.grantToSubjects("wiki", "viewer", "department:product#all_members");
    }

    private void seedFolders() {
        var folder = client.on("folder");

        // wiki-root (直属 space:wiki)
        folder.grantToSubjects("wiki-root", "space", "space:wiki");

        // backend-docs (parent: wiki-root)
        folder.grantToSubjects("backend-docs", "space", "space:wiki");
        folder.grantToSubjects("backend-docs", "parent", "folder:wiki-root");
        folder.grant("backend-docs", "editor", DAVE);

        // api-docs (parent: backend-docs)
        folder.grantToSubjects("api-docs", "space", "space:wiki");
        folder.grantToSubjects("api-docs", "parent", "folder:backend-docs");
        folder.grant("api-docs", "viewer", GRACE);

        // api-internal (parent: api-docs) — deepest level
        folder.grantToSubjects("api-internal", "space", "space:wiki");
        folder.grantToSubjects("api-internal", "parent", "folder:api-docs");
    }

    private void seedDocuments() {
        var doc = client.on("document");

        // design-doc (folder: wiki-root)
        doc.grantToSubjects("design-doc", "folder", "folder:wiki-root");
        doc.grantToSubjects("design-doc", "space", "space:wiki");
        doc.grant("design-doc", "owner", CAROL);
        doc.grant("design-doc", "editor", EVE);
        doc.grant("design-doc", "viewer", FRANK);

        // api-spec (folder: api-docs) — pure inheritance, no direct grants
        doc.grantToSubjects("api-spec", "folder", "folder:api-docs");
        doc.grantToSubjects("api-spec", "space", "space:wiki");
        doc.grant("api-spec", "owner", DAVE);

        // secret-doc (folder: api-internal) — deepest nesting
        doc.grantToSubjects("secret-doc", "folder", "folder:api-internal");
        doc.grantToSubjects("secret-doc", "space", "space:wiki");
        doc.grant("secret-doc", "owner", BOB);

        // public-doc — link sharing (anyone can view)
        doc.grantToSubjects("public-doc", "folder", "folder:wiki-root");
        doc.grantToSubjects("public-doc", "space", "space:wiki");
        doc.grant("public-doc", "owner", BOB);
        doc.grantToSubjects("public-doc", "link_viewer", "user:*");

        // shared-edit-doc — link sharing (anyone can edit)
        doc.grantToSubjects("shared-edit-doc", "folder", "folder:wiki-root");
        doc.grantToSubjects("shared-edit-doc", "space", "space:wiki");
        doc.grant("shared-edit-doc", "owner", CAROL);
        doc.grantToSubjects("shared-edit-doc", "link_editor", "user:*");

        // isolated-doc — only owner + space/org inheritance
        doc.grantToSubjects("isolated-doc", "folder", "folder:wiki-root");
        doc.grantToSubjects("isolated-doc", "space", "space:wiki");
        doc.grant("isolated-doc", "owner", ALICE);
    }
}
