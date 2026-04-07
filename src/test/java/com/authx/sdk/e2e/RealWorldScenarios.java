package com.authx.sdk.e2e;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.*;

import java.util.*;

/**
 * 真实业务场景演示。用 InMemory 运行，不需要外部服务。
 */
public class RealWorldScenarios {

    public static void main(String[] args) {
        var client = AuthxClient.inMemory();

        System.out.println("=== 场景 1：飞书文档协作 ===\n");
        feishuDocCollaboration(client);

        System.out.println("\n=== 场景 2：文件夹权限继承 ===\n");
        folderInheritance(client);

        System.out.println("\n=== 场景 3：权限网关（API 层拦截） ===\n");
        permissionGateway(client);

        System.out.println("\n=== 场景 4：批量权限检查（渲染 UI 按钮） ===\n");
        renderUiButtons(client);

        System.out.println("\n=== 场景 5：转移文档所有权 ===\n");
        transferOwnership(client);

        System.out.println("\n=== 场景 6：部门级别授权 ===\n");
        departmentAccess(client);

        System.out.println("\n=== 场景 7：协作者列表 + 分组 ===\n");
        collaboratorList(client);

        System.out.println("\n=== 场景 8：用户能看哪些文档 ===\n");
        userDocumentList(client);

        System.out.println("\n=== 场景 9：跨资源原子操作 ===\n");
        crossResourceBatch(client);

        System.out.println("\n=== 场景 10：SDK 内部监控 ===\n");
        sdkMetrics(client);

        client.close();
        System.out.println("\n=== Done ===");
    }

    /**
     * 场景 1：飞书文档协作
     * 创建文档 → 设置协作者 → 检查权限 → 移除协作者
     */
    static void feishuDocCollaboration(AuthxClient client) {
        var doc = client.resource("document", "feishu-doc-001");

        // 创建者自动成为 owner
        doc.grant("owner").to("zhang-san");
        System.out.println("  张三创建了文档 feishu-doc-001");

        // 邀请协作者
        doc.grant("editor").to("li-si", "wang-wu");
        doc.grant("viewer").to("zhao-liu");
        System.out.println("  邀请李四、王五为编辑者，赵六为查看者");

        // 检查权限
        boolean canEdit = doc.check("editor").by("li-si").hasPermission();
        boolean canView = doc.check("viewer").by("zhao-liu").hasPermission();
        boolean noAccess = doc.check("editor").by("stranger").hasPermission();
        System.out.printf("  李四能编辑？%s  赵六能查看？%s  陌生人能编辑？%s%n", canEdit, canView, noAccess);

        // 查看谁是编辑者
        Set<String> editors = doc.who().withRelation("editor").fetchSet();
        System.out.println("  编辑者列表：" + editors);

        // 移除王五的编辑权限
        doc.revoke("editor").from("wang-wu");
        boolean wangwuStill = doc.check("editor").by("wang-wu").hasPermission();
        System.out.println("  移除王五后，王五还能编辑？" + wangwuStill);
    }

    /**
     * 场景 2：文件夹权限继承（InMemory 不做递归计算，但展示 API 用法）
     */
    static void folderInheritance(AuthxClient client) {
        // 创建文件夹层级：公司根目录 → 工程部 → 后端组 → 项目A
        var companyRoot = client.resource("folder", "company-root");
        var engDept = client.resource("folder", "eng-dept");
        var backendTeam = client.resource("folder", "backend-team");
        var projectA = client.resource("folder", "project-a");

        // 设置权限（真实 SpiceDB 会通过 parent 关系递归继承）
        companyRoot.grant("viewer").to("all-employees");
        engDept.grant("editor").to("eng-lead");
        backendTeam.grant("editor").to("backend-lead", "dev-alice", "dev-bob");
        projectA.grant("owner").to("pm-carol");

        System.out.println("  公司根目录 viewer: all-employees");
        System.out.println("  工程部 editor: eng-lead");
        System.out.println("  后端组 editor: backend-lead, dev-alice, dev-bob");
        System.out.println("  项目A owner: pm-carol");

        // 查看后端组有谁
        System.out.println("  后端组编辑者: " + backendTeam.who().withRelation("editor").fetch());
    }

    /**
     * 场景 3：API 权限网关
     * 在 Controller 层拦截无权限请求
     */
    static void permissionGateway(AuthxClient client) {
        // 先设置一些权限
        client.resource("document", "secret-report").grant("viewer").to("authorized-user");

        // 模拟 API 请求
        String[] requests = {
                "authorized-user:document:secret-report:viewer",
                "hacker:document:secret-report:viewer",
                "authorized-user:document:other-doc:viewer",
        };

        for (String req : requests) {
            String[] parts = req.split(":");
            String userId = parts[0], resType = parts[1], resId = parts[2], perm = parts[3];

            boolean allowed = client.resource(resType, resId).check(perm).by(userId).hasPermission();
            System.out.printf("  %s 访问 %s:%s [%s] → %s%n",
                    userId, resType, resId, perm, allowed ? "✓ 放行" : "✗ 拒绝 403");
        }
    }

    /**
     * 场景 4：批量检查权限 → 渲染前端按钮
     * 一次请求拿到所有权限，决定哪些按钮可点击
     */
    static void renderUiButtons(AuthxClient client) {
        var doc = client.resource("document", "design-spec");
        doc.grant("editor").to("designer");
        doc.grant("owner").to("pm");

        // 一次拿到所有权限
        PermissionSet designerPerms = doc.checkAll("owner", "editor", "viewer").by("designer");
        PermissionSet pmPerms = doc.checkAll("owner", "editor", "viewer").by("pm");

        System.out.println("  设计师权限: " + designerPerms.toMap());
        System.out.println("  PM 权限: " + pmPerms.toMap());

        // 渲染按钮
        System.out.println("  设计师看到的按钮:");
        System.out.println("    [编辑] " + (designerPerms.can("editor") ? "可点击" : "灰色"));
        System.out.println("    [删除] " + (designerPerms.can("owner") ? "可点击" : "灰色"));

        // 权限矩阵：多人 × 多权限
        PermissionMatrix matrix = doc.checkAll("owner", "editor", "viewer").byAll("designer", "pm", "intern");
        System.out.println("  谁能编辑: " + matrix.whoCanAny("editor"));
        System.out.println("  谁能删除: " + matrix.whoCanAny("owner"));
    }

    /**
     * 场景 5：原子性转移所有权
     * 老 owner → 降为 editor，新 owner 接管
     */
    static void transferOwnership(AuthxClient client) {
        var doc = client.resource("document", "important-doc");
        doc.grant("owner").to("old-boss");
        doc.grant("editor").to("worker");

        System.out.println("  转移前 owner: " + doc.who().withRelation("owner").fetch());

        // 原子操作：一个 gRPC 调用完成
        doc.batch()
                .revoke("owner").from("old-boss")
                .grant("owner").to("new-boss")
                .grant("editor").to("old-boss")  // 降级为 editor
                .execute();

        System.out.println("  转移后 owner: " + doc.who().withRelation("owner").fetch());
        System.out.println("  转移后 editor: " + doc.who().withRelation("editor").fetch());
    }

    /**
     * 场景 6：部门级别授权
     * 给整个工程部的人授权查看文档
     */
    static void departmentAccess(AuthxClient client) {
        // 先把人加到部门
        client.resource("group", "engineering").grant("member").to("alice", "bob", "carol", "dave");
        System.out.println("  工程部成员: " + client.resource("group", "engineering").who().withRelation("member").fetch());

        // 给整个部门授权（通过 group#member 引用）
        client.resource("document", "arch-design")
                .grant("viewer").toSubjects("group:engineering#member");

        System.out.println("  arch-design 的 viewer subjects: " +
                client.resource("document", "arch-design").relations("viewer").fetchSubjectIds());
    }

    /**
     * 场景 7：协作者列表 + 按角色分组
     */
    static void collaboratorList(AuthxClient client) {
        var doc = client.resource("document", "team-wiki");
        doc.grant("owner").to("team-lead");
        doc.grant("editor").to("senior-dev", "mid-dev");
        doc.grant("viewer").to("junior-dev", "intern-1", "intern-2");

        // 按角色分组
        Map<String, List<String>> grouped = doc.relations().groupByRelation();
        for (var entry : grouped.entrySet()) {
            System.out.printf("  %s: %s%n", entry.getKey(), entry.getValue());
        }

        // 总人数
        System.out.println("  总协作者数: " + doc.relations().fetchCount());

        // 某人是不是协作者
        boolean isCollab = doc.relations().fetchSubjectIdSet().contains("intern-1");
        System.out.println("  intern-1 是协作者？" + isCollab);
    }

    /**
     * 场景 8：用户能看哪些文档（反向查询）
     */
    static void userDocumentList(AuthxClient client) {
        // 给 alice 在多个文档上设权限
        client.resource("document", "doc-a").grant("editor").to("alice");
        client.resource("document", "doc-b").grant("viewer").to("alice");
        client.resource("document", "doc-c").grant("editor").to("alice");
        client.resource("document", "doc-d").grant("owner").to("bob");

        // alice 能编辑哪些文档？（InMemory 按 relation 匹配）
        List<String> aliceEditable = client.lookup("document").withPermission("editor").by("alice").fetch();
        System.out.println("  alice 能编辑的文档: " + aliceEditable);

        // alice 有没有能编辑的文档？
        boolean hasAny = client.lookup("document").withPermission("editor").by("alice").fetchExists();
        System.out.println("  alice 有可编辑文档？" + hasAny);

        // bob 能编辑的？
        List<String> bobEditable = client.lookup("document").withPermission("editor").by("bob").fetch();
        System.out.println("  bob 能编辑的文档: " + bobEditable);
    }

    /**
     * 场景 9：跨资源原子操作
     * 把用户从一个项目转到另一个项目
     */
    static void crossResourceBatch(AuthxClient client) {
        var projectOld = client.resource("folder", "project-old");
        var projectNew = client.resource("folder", "project-new");

        projectOld.grant("editor").to("migrating-user");
        System.out.println("  转移前 project-old editor: " + projectOld.who().withRelation("editor").fetch());

        // 原子操作：从旧项目移除 + 加到新项目
        client.batch()
                .on(projectOld).revoke("editor").from("migrating-user")
                .on(projectNew).grant("editor").to("migrating-user")
                .execute();

        System.out.println("  转移后 project-old editor: " + projectOld.who().withRelation("editor").fetch());
        System.out.println("  转移后 project-new editor: " + projectNew.who().withRelation("editor").fetch());
    }

    /**
     * 场景 10：查看 SDK 内部指标
     */
    static void sdkMetrics(AuthxClient client) {
        // 先跑一些操作让指标有数据
        var doc = client.resource("document", "metrics-test");
        for (int i = 0; i < 100; i++) {
            doc.grant("viewer").to("user-" + i);
        }
        for (int i = 0; i < 1000; i++) {
            doc.check("viewer").by("user-" + (i % 100));
        }

        var m = client.metrics();
        System.out.println("  " + m.snapshot());
    }
}
