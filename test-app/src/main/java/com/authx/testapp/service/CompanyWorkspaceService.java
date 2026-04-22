package com.authx.testapp.service;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.SubjectRef;
import com.authx.testapp.schema.Caveats;
import com.authx.testapp.schema.Department;
import com.authx.testapp.schema.Document;
import com.authx.testapp.schema.Folder;
import com.authx.testapp.schema.Group;
import com.authx.testapp.schema.IpAllowlist;
import com.authx.testapp.schema.Organization;
import com.authx.testapp.schema.Space;
import com.authx.testapp.schema.User;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端到端工作空间示范：覆盖 schema 的整条层级 —
 * {@code organization → space → folder(+ nested) → document} —
 * 以及所有主体 ({@code user / department#all_members / group#member / user:*}) 的分享路径，
 * 加上 caveat 受限分享。
 *
 * <p>这个 service 是 <b>"API usage cookbook"</b>：每个方法把一条典型
 * 业务场景翻译成 SDK 调用链，并在 JavaDoc 里注明它演示的是哪条
 * typed 重载，这样读 service 的人一眼就能看懂每个 API 的用法和适用场景。
 *
 * <p>核心约束（来自项目 CLAUDE.md）：
 * <ul>
 *   <li>业务代码里不手写 {@code "user:"} / {@code "group#member"} 等 wire 字符串 —
 *       所有 subject ref 都通过 {@code Xxx.TYPE} 常量和 typed 重载构造；</li>
 *   <li>caveat name / parameter name 从 codegen 常量读（{@code IpAllowlist.NAME}、
 *       {@code IpAllowlist.CIDRS}、{@code IpAllowlist.CLIENT_IP}），
 *       业务代码零字符串拼接；</li>
 *   <li>批量跨类型写 → {@code client.batch()}；批量跨类型检查 →
 *       {@code client.batchCheck()}；两个入口都接 typed {@code TYPE} 描述符。</li>
 * </ul>
 *
 * <p>业务骨架是一个虚构的 Acme Corp：engineering 部门、marketing 部门、
 * "alpha-pms" 跨部门小组、"acme-main" 空间、一棵 folder 树、若干文档。
 * 每个 {@code Scenario.xxx} 方法可以独立跑，也可以串起来看一个完整员工
 * 入职 → 共享 → 离职的生命周期。
 */
@Service
public class CompanyWorkspaceService {

    private final AuthxClient client;

    public CompanyWorkspaceService(AuthxClient client) {
        this.client = client;
    }

    // ════════════════════════════════════════════════════════════════
    //  1. 组织骨架搭建 — 原子批量写 (client.batch() + typed .to(id))
    // ════════════════════════════════════════════════════════════════

    /**
     * 搭一个新组织的骨架：挂 admin + 两个部门 + 一个跨部门 group。
     *
     * <p>所有写入走同一个 {@code client.batch()} —— 要么全部落地，要么全部
     * 撤销，SpiceDB 保证原子性。返回 {@code zedToken} 供后续 SESSION
     * 一致性读使用（"我刚写完，立刻问能不能看"的场景）。
     *
     * <p>演示重载：
     * <ul>
     *   <li>{@code .to(userId)} — {@code organization.admin} 只接 {@code user}，
     *       单类型推断，bare id 即可；</li>
     *   <li>{@code .to(User.TYPE, userId)} — 跨类型显式 typed ref；</li>
     *   <li>{@code .to(Department.TYPE, deptId, "all_members")} — typed
     *       sub-relation，消除 {@code "department:eng#all_members"} 手拼。</li>
     * </ul>
     */
    public String bootstrapOrganization(OrgSeed seed) {
        // NOTE: inside client.batch() there is no single-type inference —
        // the batch chain has no SchemaCache in scope — so we always hand
        // in an explicit typed subject via .to(User.TYPE, id) etc. Outside
        // of batch (client.on(T).select(id).grant(R).to(bareId)) the typed
        // chain does have the cache and can infer.
        var batch = client.batch()
                // Organization admin（typed subject — batch chain 强制类型显式）
                .on(Organization.TYPE, seed.orgId())
                .grant(Organization.Rel.ADMIN)
                .to(User.TYPE, seed.adminUserId());

        // 每个部门挂上 member（User typed batch），再把部门当作 member 挂到 organization
        for (var dept : seed.departments()) {
            if (!dept.memberUserIds().isEmpty()) {
                // typed Iterable 批量 — 一次 append 所有 member tuples
                batch.on(Department.TYPE, dept.id())
                        .grant(Department.Rel.MEMBER)
                        .to(User.TYPE, dept.memberUserIds());
            }
            // organization.member 接 user 或 department#all_members 两种 subject，
            // typed sub-relation 重载把整个部门作为 group-like 成员挂进 org。
            batch.on(Organization.TYPE, seed.orgId())
                    .grant(Organization.Rel.MEMBER)
                    .to(Department.TYPE, dept.id(), "all_members");
        }

        // group.member 接 user 或 department#all_members。演示两种都能往同一个 relation 上挂：
        for (var group : seed.groups()) {
            if (!group.memberUserIds().isEmpty()) {
                batch.on(Group.TYPE, group.id())
                        .grant(Group.Rel.MEMBER)
                        .to(User.TYPE, group.memberUserIds());   // typed Iterable<String>
            }
            for (String deptId : group.memberDepartmentIds()) {
                batch.on(Group.TYPE, group.id())
                        .grant(Group.Rel.MEMBER)
                        .to(Department.TYPE, deptId, "all_members");
            }
        }

        return batch.commit().zedToken();
    }

    // ════════════════════════════════════════════════════════════════
    //  2. 部门层级 — .to(String bareId) 单类型推断
    // ════════════════════════════════════════════════════════════════

    /**
     * 建立部门父子关系：{@code engineering → platform → infra}。
     *
     * <p>{@code department.parent} relation 在 schema 里只接
     * {@code department} 一种 subject 类型，所以 {@code .to(parentId)}
     * 可以直接传 bare id — codegen 的 {@link com.authx.sdk.model.SubjectType#inferSingleType}
     * 会在运行时查 schema，自动拼 {@code "department:" + parentId}。
     *
     * <p>这里演示 <b>单类型推断</b>：业务代码不知道也不关心"父是哪种类型"，
     * schema 说了算。schema 明天改成多类型，这行代码会 fail-fast 抛异常，
     * 不会默默写错。
     */
    public void linkDepartmentToParent(String childDeptId, String parentDeptId) {
        client.on(Department.TYPE)
                .select(childDeptId)
                .grant(Department.Rel.PARENT)
                .to(parentDeptId);    // 单类型推断 — SDK 自动补 "department:" 前缀
    }

    // ════════════════════════════════════════════════════════════════
    //  3. 空间开通 — .to(ResourceType, id) + toWildcard
    // ════════════════════════════════════════════════════════════════

    /**
     * 开通一个 space：挂 org、owner、admin 群、viewer 部门，
     * 再可选地开成"组织内全员只读"。
     *
     * <p>演示重载矩阵：
     * <ul>
     *   <li>{@code space.org} 单类型 → {@code .to(orgId)} 推断</li>
     *   <li>{@code space.owner} 单类型 {@code user} → {@code .to(User.TYPE, id)} 显式</li>
     *   <li>{@code space.admin} 多类型 → {@code .to(Group.TYPE, id, "member")} typed sub-rel</li>
     *   <li>{@code space.member} 多类型 → {@code .to(Department.TYPE, id, "all_members")}</li>
     *   <li>{@code space.viewer} 含 {@code user:*} → {@code .toWildcard(User.TYPE)}</li>
     * </ul>
     */
    public void provisionSpace(SpaceSeed seed) {
        var on = client.on(Space.TYPE).select(seed.spaceId());

        // org 绑定 — space.org 只接 organization 一种类型，推断即可
        on.grant(Space.Rel.ORG).to(seed.orgId());

        // owner 是个具体 user
        on.grant(Space.Rel.OWNER).to(User.TYPE, seed.ownerUserId());

        // admin 权限给 "alpha-pms" 整个 group，typed sub-relation
        if (seed.adminGroupId() != null) {
            on.grant(Space.Rel.ADMIN).to(Group.TYPE, seed.adminGroupId(), "member");
        }

        // member 权限给多个部门 — 每个部门都是 department#all_members
        for (String deptId : seed.memberDepartmentIds()) {
            on.grant(Space.Rel.MEMBER).to(Department.TYPE, deptId, "all_members");
        }

        // 开放给全员只读 — wildcard typed 重载，消除 "user:*" 手拼
        if (seed.publicViewer()) {
            on.grant(Space.Rel.VIEWER).toWildcard(User.TYPE);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  4. 文件夹树 — 推断 + 嵌套父子
    // ════════════════════════════════════════════════════════════════

    /**
     * 一次创建一棵 folder 树，每层 parent 都通过单类型推断挂到上一层。
     *
     * <p>{@code folder.parent} 和 {@code folder.space} 都是单类型，
     * 所以 bare id 就够了。业务代码读起来最清爽："move f-2 under f-1"。
     */
    public void createFolderTree(String spaceId, List<FolderNode> breadthFirst) {
        var batch = client.batch();
        for (var node : breadthFirst) {
            // typed subject — 批量链上没有单类型推断
            batch.on(Folder.TYPE, node.id())
                    .grant(Folder.Rel.SPACE)
                    .to(Space.TYPE, spaceId);
            batch.on(Folder.TYPE, node.id())
                    .grant(Folder.Rel.OWNER)
                    .to(User.TYPE, node.ownerUserId());
            if (node.parentFolderId() != null) {
                batch.on(Folder.TYPE, node.id())
                        .grant(Folder.Rel.PARENT)
                        .to(Folder.TYPE, node.parentFolderId());
            }
        }
        batch.commit();
    }

    /**
     * 把整个部门全体当 folder viewer 挂上 —— 部门加人、减人、
     * 子部门关系的变化自动级联到 folder.view。schema 里 {@code folder.viewer}
     * 的允许主体包含 {@code department#all_members}，走 typed sub-relation。
     */
    public void shareFolderWithDepartment(String folderId, String deptId) {
        client.on(Folder.TYPE)
                .select(folderId)
                .grant(Folder.Rel.VIEWER)
                .to(Department.TYPE, deptId, "all_members");
    }

    // ════════════════════════════════════════════════════════════════
    //  5. 文档生命周期 — 每种分享路径一条
    // ════════════════════════════════════════════════════════════════

    /**
     * 文档初始化：放入 folder + space，挂 owner。
     *
     * <p>{@code document.folder} / {@code document.space} 都是单类型，
     * bare id 推断。{@code document.owner} 接 user — 推断到 user。
     */
    public void publishDocument(String docId, String folderId, String spaceId, String ownerUserId) {
        client.batch()
                .on(Document.TYPE, docId).grant(Document.Rel.FOLDER).to(Folder.TYPE, folderId)
                .on(Document.TYPE, docId).grant(Document.Rel.SPACE).to(Space.TYPE, spaceId)
                .on(Document.TYPE, docId).grant(Document.Rel.OWNER).to(User.TYPE, ownerUserId)
                .commit();
    }

    /**
     * 分享给一个组的全体成员（group#member）。一次 grant，
     * 该 group 加/减人会自动反映到这篇文档的 editor 集合上 ——
     * 这是 ReBAC 相比 "把 user 一个个挂"的核心优势。
     */
    public void shareWithGroup(String docId, String groupId, Document.Rel relation) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(relation)
                .to(Group.TYPE, groupId, "member");
    }

    /**
     * 分享给整个部门（含其子部门递归成员）。
     * {@code department#all_members} 是 schema 里的 computed permission，
     * SpiceDB 会在 check 时递归展开子部门。
     */
    public void shareWithDepartment(String docId, String deptId, Document.Rel relation) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(relation)
                .to(Department.TYPE, deptId, "all_members");
    }

    /**
     * 批量分享给多个具体 user — typed {@code Iterable<String>} 重载，
     * 每个 id 自动补 {@code "user:"} 前缀。
     */
    public void shareWithUsers(String docId, Iterable<String> userIds, Document.Rel relation) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(relation)
                .to(User.TYPE, userIds);
    }

    /** 公开文档：挂 {@code link_viewer: user:*} —— typed wildcard 重载。 */
    public void publishPublicly(String docId) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(Document.Rel.LINK_VIEWER)
                .toWildcard(User.TYPE);
    }

    /**
     * 限时分享：{@code expiringIn(Duration)} 让 SpiceDB 在指定 TTL 后
     * 自动让这个关系失效 — 不需要业务代码跑定时任务删关系。
     */
    public void shareTemporarily(String docId, String userId, Document.Rel relation, Duration ttl) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(relation)
                .expiringIn(ttl)
                .to(User.TYPE, userId);
    }

    /**
     * 受 IP 白名单约束的公开分享：{@code onlyIf(CaveatRef)} 绑定静态
     * 参数 {@code cidrs}，check 时通过 {@link IpAllowlist#CLIENT_IP}
     * 传入动态 {@code client_ip}。CEL 表达式 {@code cidrs.exists(c, client_ip.startsWith(c))}
     * 由 SpiceDB 服务端评估。
     *
     * <p>演示：<b>业务代码没有任何字符串字面量</b> —— caveat name 来自
     * {@link Caveats#IP_ALLOWLIST}（间接 via {@code IpAllowlist.ref}），
     * 参数名来自 {@link IpAllowlist#CIDRS}。
     */
    public void shareBehindIpAllowlist(String docId, List<String> allowedCidrs) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(Document.Rel.LINK_VIEWER)
                .onlyIf(IpAllowlist.ref(IpAllowlist.CIDRS, allowedCidrs))
                .toWildcard(User.TYPE);
    }

    // ════════════════════════════════════════════════════════════════
    //  6. 撤销 — 每条 grant 的 from 对称
    // ════════════════════════════════════════════════════════════════

    /** 从一个 group 回收文档编辑权。与 {@link #shareWithGroup} 对称。 */
    public void unshareFromGroup(String docId, String groupId, Document.Rel relation) {
        client.on(Document.TYPE)
                .select(docId)
                .revoke(relation)
                .from(Group.TYPE, groupId, "member");
    }

    /** 批量从多个 user 收权。typed Iterable from 重载。 */
    public void unshareFromUsers(String docId, Iterable<String> userIds, Document.Rel relation) {
        client.on(Document.TYPE)
                .select(docId)
                .revoke(relation)
                .from(User.TYPE, userIds);
    }

    /** 取消公开。typed {@code fromWildcard} 重载。 */
    public void unpublishPublicly(String docId) {
        client.on(Document.TYPE)
                .select(docId)
                .revoke(Document.Rel.LINK_VIEWER)
                .fromWildcard(User.TYPE);
    }

    /**
     * 文档所有权转让：一笔 batch 里先撤旧 owner 再挂新 owner，
     * SpiceDB 保证原子性 — 不会出现"转让中途 owner 为空"的时间窗。
     */
    public String transferOwnership(String docId, String oldOwnerId, String newOwnerId) {
        return client.batch()
                .on(Document.TYPE, docId).revoke(Document.Rel.OWNER).from(User.TYPE, oldOwnerId)
                .on(Document.TYPE, docId).grant(Document.Rel.OWNER).to(User.TYPE, newOwnerId)
                .commit()
                .zedToken();
    }

    // ════════════════════════════════════════════════════════════════
    //  7. 权限查询 — check / checkAll / 矩阵
    // ════════════════════════════════════════════════════════════════

    /**
     * 单点检查：{@code check(Perm).by(ResourceType, id)} —
     * 消除 {@code by("user:alice")} 的手拼。
     */
    public boolean canView(String userId, String docId) {
        return client.on(Document.TYPE)
                .select(docId)
                .check(Document.Perm.VIEW)
                .by(User.TYPE, userId);
    }

    /**
     * caveat check：加上运行时 context（client_ip）。
     * 没有 caveat 的路径（比如走 owner→edit→view）照常返回 true，
     * 有 caveat 的路径（link_viewer→view）才会评估 CEL。
     */
    public boolean canViewFrom(String userId, String docId, String clientIp) {
        return client.on(Document.TYPE)
                .select(docId)
                .check(Document.Perm.VIEW)
                .given(IpAllowlist.CLIENT_IP, clientIp)
                .by(User.TYPE, userId);
    }

    /**
     * 文档详情页工具栏：一次 RPC 拿所有 permission 的状态。
     * schema 新增 permission 后只要重跑 codegen，工具栏自动带上。
     */
    public EnumMap<Document.Perm, Boolean> toolbar(String userId, String docId) {
        return client.on(Document.TYPE)
                .select(docId)
                .checkAll()
                .by(User.TYPE, userId);
    }

    /** 文档列表批量检查：N 个文档 × 所有 perm，一次 RPC。 */
    public Map<String, EnumMap<Document.Perm, Boolean>> listPermissions(
            String userId, List<String> docIds) {
        if (docIds.isEmpty()) return Map.of();
        return client.on(Document.TYPE)
                .select(docIds)
                .checkAll()
                .byAll(User.TYPE, userId);
    }

    /** N 个 doc × 单 perm × 1 user 矩阵 — 用 typed subject 批量 byAll。 */
    public Map<String, Boolean> filterVisible(String userId, List<String> docIds) {
        if (docIds.isEmpty()) return Map.of();
        CheckMatrix m = client.on(Document.TYPE)
                .select(docIds)
                .check(Document.Perm.VIEW)
                .byAll(User.TYPE, List.of(userId));
        var out = new LinkedHashMap<String, Boolean>();
        for (String id : docIds) {
            out.put(id, m.allowed(id, "view", userId));
        }
        return out;
    }

    /**
     * 跨资源类型一次性决定侧边栏可见性：
     * {@code client.batchCheck()} 混合 space/folder/document，
     * 每个条目用 typed {@code TYPE} 描述符 + typed Perm。
     */
    public Map<String, Boolean> renderSidebar(String userId, List<ResourceKey> items) {
        if (items.isEmpty()) return Map.of();
        var builder = client.batchCheck();
        var subject = SubjectRef.of(User.TYPE.name(), userId);
        for (var item : items) {
            switch (item.type()) {
                case "space"    -> builder.add(Space.TYPE,    item.id(), Space.Perm.VIEW,    subject);
                case "folder"   -> builder.add(Folder.TYPE,   item.id(), Folder.Perm.VIEW,   subject);
                case "document" -> builder.add(Document.TYPE, item.id(), Document.Perm.VIEW, subject);
                default         -> throw new IllegalArgumentException("unknown item type: " + item.type());
            }
        }
        var matrix = builder.fetch();
        var out = new LinkedHashMap<String, Boolean>();
        for (var item : items) {
            out.put(item.type() + ":" + item.id(),
                    matrix.allowed(item.type() + ":" + item.id(), "view", userId));
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    //  8. 反向查询 — who / findBy
    // ════════════════════════════════════════════════════════════════

    /** "谁是这篇文档的 editor?"（只列 user 主体） */
    public List<String> whoCanEdit(String docId, int max) {
        return client.on(Document.TYPE)
                .select(docId)
                .who(User.TYPE.name(), Document.Perm.EDIT)
                .limit(max)
                .fetchIds();
    }

    /** "alice 能看的所有文档 id" — typed findBy + single perm. */
    public List<String> myReadableDocs(String userId, int max) {
        return client.on(Document.TYPE)
                .findBy(User.TYPE, userId)
                .limit(max)
                .can(Document.Perm.VIEW);
    }

    /** "alice 能看 / 能编辑 / 能评论的文档分别有哪些" — 多 perm 一次返回. */
    public Map<Document.Perm, List<String>> myDocsByPermission(String userId, int max) {
        return client.on(Document.TYPE)
                .findBy(User.TYPE, userId)
                .limit(max)
                .can(Document.Perm.VIEW, Document.Perm.EDIT, Document.Perm.COMMENT);
    }

    /** "团队每人能看的 doc" — typed 多 subject findBy. */
    public Map<String, List<String>> readableDocsForTeam(List<String> userIds, int max) {
        return client.on(Document.TYPE)
                .findBy(User.TYPE, userIds)
                .limit(max)
                .can(Document.Perm.VIEW);
    }

    // ════════════════════════════════════════════════════════════════
    //  9. 离职清权 — batch revoke 跨类型
    // ════════════════════════════════════════════════════════════════

    /**
     * 员工离职一键清权：user 从 space.member / folder.editor /
     * document.editor / document.viewer / department.member 全部下掉。
     * 一个原子 batch，要么全撤要么全不撤。
     */
    public String offboardUser(String userId, OffboardTargets targets) {
        var batch = client.batch();

        // 部门成员关系
        for (String deptId : targets.departmentIds()) {
            batch.on(Department.TYPE, deptId).revoke(Department.Rel.MEMBER).from(User.TYPE, userId);
        }

        // group 成员关系 — 因为 group#member 的级联（文档/folder/space
        // 的 editor 等可能来自 group#member），所以离职也要从 group 退出
        for (String groupId : targets.groupIds()) {
            batch.on(Group.TYPE, groupId).revoke(Group.Rel.MEMBER).from(User.TYPE, userId);
        }

        // space 成员关系 — 可能同时占多种角色，一次清
        for (String spaceId : targets.spaceIds()) {
            batch.on(Space.TYPE, spaceId).revoke(
                    Space.Rel.OWNER, Space.Rel.ADMIN, Space.Rel.MEMBER, Space.Rel.VIEWER
            ).from(User.TYPE, userId);
        }

        // folder：viewer/editor/commenter/owner 四种，一次清
        for (String folderId : targets.folderIds()) {
            batch.on(Folder.TYPE, folderId).revoke(
                    Folder.Rel.OWNER, Folder.Rel.EDITOR, Folder.Rel.COMMENTER, Folder.Rel.VIEWER
            ).from(User.TYPE, userId);
        }

        // document：owner/editor/commenter/viewer 四种，一次清
        for (String docId : targets.documentIds()) {
            batch.on(Document.TYPE, docId).revoke(
                    Document.Rel.OWNER, Document.Rel.EDITOR,
                    Document.Rel.COMMENTER, Document.Rel.VIEWER
            ).from(User.TYPE, userId);
        }

        return batch.commit().zedToken();
    }

    // ════════════════════════════════════════════════════════════════
    //  Records — 业务输入形状
    // ════════════════════════════════════════════════════════════════

    public record OrgSeed(
            String orgId,
            String adminUserId,
            List<DepartmentSeed> departments,
            List<GroupSeed> groups) {}

    public record DepartmentSeed(String id, List<String> memberUserIds) {}

    public record GroupSeed(
            String id,
            List<String> memberUserIds,
            List<String> memberDepartmentIds) {
        public static GroupSeed of(String id, List<String> userIds) {
            return new GroupSeed(id, userIds, List.of());
        }
        public static GroupSeed ofDepartments(String id, List<String> deptIds) {
            return new GroupSeed(id, List.of(), deptIds);
        }
    }

    public record SpaceSeed(
            String spaceId,
            String orgId,
            String ownerUserId,
            String adminGroupId,
            List<String> memberDepartmentIds,
            boolean publicViewer) {}

    public record FolderNode(String id, String parentFolderId, String ownerUserId) {
        public static FolderNode root(String id, String ownerUserId) {
            return new FolderNode(id, null, ownerUserId);
        }
        public static FolderNode child(String id, String parentId, String ownerUserId) {
            return new FolderNode(id, parentId, ownerUserId);
        }
    }

    public record ResourceKey(String type, String id) {}

    public record OffboardTargets(
            List<String> departmentIds,
            List<String> groupIds,
            List<String> spaceIds,
            List<String> folderIds,
            List<String> documentIds) {
        public static OffboardTargets empty() {
            return new OffboardTargets(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  便利构造器：减少测试代码里的 List.of() 嵌套
    // ════════════════════════════════════════════════════════════════

    public static OrgSeed acmeSeed() {
        return new OrgSeed(
                "acme",
                "u-ceo",
                List.of(
                        new DepartmentSeed("d-eng",
                                List.of("u-alice", "u-bob", "u-carol")),
                        new DepartmentSeed("d-marketing",
                                List.of("u-dan", "u-eve"))),
                List.of(
                        GroupSeed.of("g-alpha-pms", List.of("u-alice", "u-dan")),
                        GroupSeed.ofDepartments("g-all-eng", List.of("d-eng"))));
    }

    public static SpaceSeed acmeMainSpace() {
        return new SpaceSeed(
                "s-main", "acme", "u-ceo",
                "g-alpha-pms",
                List.of("d-eng", "d-marketing"),
                true);
    }

    public static List<FolderNode> acmeFolders() {
        var folders = new ArrayList<FolderNode>();
        folders.add(FolderNode.root("f-root", "u-ceo"));
        folders.add(FolderNode.child("f-eng", "f-root", "u-alice"));
        folders.add(FolderNode.child("f-eng-specs", "f-eng", "u-alice"));
        folders.add(FolderNode.child("f-marketing", "f-root", "u-dan"));
        return folders;
    }
}
