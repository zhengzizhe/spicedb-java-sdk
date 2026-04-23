package com.authx.testapp.service;

import static com.authx.testapp.schema.Schema.Group;
import static com.authx.testapp.schema.Schema.Organization;
import static com.authx.testapp.schema.Schema.TenantDocument;
import static com.authx.testapp.schema.Schema.User;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.SubjectRef;
import com.authx.testapp.schema.TenantDocument.Perm;
import com.authx.testapp.schema.TenantDocument.Rel;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多租户隔离演示：每个 tenant_document 必须绑定一个 org，所有权限
 * 通过 {@code & org->access} 双重把关——离开 org 自动失去访问。
 *
 * <h2>使用模式</h2>
 *
 * 1. 先用 {@link #createInOrg} 创建文档并绑定租户 org（原子）
 * 2. 正常授权 {@link #addViewer} / {@link #addEditor}
 * 3. 用户进出 org 通过 {@link #addOrgMember} / {@link #removeOrgMember}
 * 4. 任何时刻的 {@link #can} check 会组合验证"直接授权 ∩ org 成员"
 *
 * <h2>关键语义</h2>
 *
 * <ul>
 *   <li>撤销 org 成员 → 所有 tenant_document 上的权限立即失效
 *       （tuple 不删，只是 intersection 不满足）</li>
 *   <li>org admin 自动拿到 org 内所有 doc 的所有权限</li>
 *   <li>banned 黑名单对 MANAGE 以外的权限生效（schema 层约束）</li>
 * </ul>
 */
@Service
public class TenantDocumentService {

    private final AuthxClient auth;

    public TenantDocumentService(AuthxClient auth) {
        this.auth = auth;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Create & tenant binding
    // ══════════════════════════════════════════════════════════════════

    /**
     * 创建文档，把它和所属 org 原子绑定并指派 owner。这三条 tuple 必须
     * 同时写入——少了 org 绑定，后续 check 永远返 false。
     */
    public void createInOrg(String docId, String orgId, String ownerId) {
        auth.batch()
                .on(TenantDocument, docId).grant(Rel.ORG).to(Organization, orgId)
                .on(TenantDocument, docId).grant(Rel.OWNER).to(User, ownerId)
                .execute();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Grants on the doc (typical authorization)
    // ══════════════════════════════════════════════════════════════════

    public void addViewer(String docId, String userId) {
        auth.on(TenantDocument).select(docId)
                .grant(Rel.VIEWER).to(User, userId)
                .commit();
    }

    public void addViewers(String docId, List<String> userIds) {
        auth.on(TenantDocument).select(docId)
                .grant(Rel.VIEWER).to(User, userIds)
                .commit();
    }

    public void addEditor(String docId, String userId) {
        auth.on(TenantDocument).select(docId)
                .grant(Rel.EDITOR).to(User, userId)
                .commit();
    }

    /** 分享给整个 group 的成员。group 必须在同一个 org 里才真正生效。 */
    public void shareWithGroup(String docId, String groupId) {
        auth.on(TenantDocument).select(docId)
                .grant(Rel.VIEWER).to(Group, groupId, Group.Rel.MEMBER)
                .commit();
    }

    /** 公开（在 org 内）：所有 org member 可看。user:* 仍要过 org->access 关卡。 */
    public void shareWithinOrgPublic(String docId) {
        auth.on(TenantDocument).select(docId)
                .grant(Rel.VIEWER).toWildcard(User)
                .commit();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Org membership (the tenant gate)
    // ══════════════════════════════════════════════════════════════════

    public void addOrgMember(String orgId, String userId) {
        auth.on(Organization).select(orgId)
                .grant(Organization.Rel.MEMBER).to(User, userId)
                .commit();
    }

    /** 把用户踢出 org。所有 tenant_document 上的权限同时失效，tuple 不用清。 */
    public void removeOrgMember(String orgId, String userId) {
        auth.on(Organization).select(orgId)
                .revoke(Organization.Rel.MEMBER).from(User, userId)
                .commit();
    }

    public void promoteOrgAdmin(String orgId, String userId) {
        auth.on(Organization).select(orgId)
                .grant(Organization.Rel.ADMIN).to(User, userId)
                .commit();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Role change (mixed revoke + grant atomic)
    // ══════════════════════════════════════════════════════════════════

    /**
     * 改角色：editor → viewer。利用 WriteFlow 混合 revoke + grant，一次原子
     * 提交，中间无可见状态。
     */
    public void changeRole(String docId, String userId, Rel fromRel, Rel toRel) {
        auth.on(TenantDocument).select(docId)
                .revoke(fromRel).from(User, userId)
                .grant(toRel).to(User, userId)
                .commit();
    }

    /** 转让所有权：owner 从 alice 换到 bob。 */
    public void transferOwnership(String docId, String fromUserId, String toUserId) {
        auth.on(TenantDocument).select(docId)
                .revoke(Rel.OWNER).from(User, fromUserId)
                .grant(Rel.OWNER).to(User, toUserId)
                .commit();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Revoke / ban
    // ══════════════════════════════════════════════════════════════════

    public void removeAccess(String docId, String userId) {
        // filter-based delete：清该 user 在这份 doc 上的所有 relation
        auth.resource(TenantDocument.name(), docId)
                .revokeAll()
                .from(SubjectRef.of(User.name(), userId));
    }

    /** 把用户加入该文档黑名单（schema 里的 - banned 生效）。 */
    public void ban(String docId, String userId) {
        auth.on(TenantDocument).select(docId)
                .grant(Rel.BANNED).to(User, userId)
                .commit();
    }

    public void unban(String docId, String userId) {
        auth.on(TenantDocument).select(docId)
                .revoke(Rel.BANNED).from(User, userId)
                .commit();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Check
    // ══════════════════════════════════════════════════════════════════

    public boolean can(String docId, Perm perm, String userId) {
        return auth.on(TenantDocument).select(docId)
                .check(perm)
                .by(User, userId);
    }

    /** 一次 RPC 拿 VIEW/EDIT/MANAGE 三个权限。适合工具栏按钮判定。 */
    public Map<String, Boolean> permissionsFor(String docId, String userId) {
        EnumMap<Perm, Boolean> raw = auth.on(TenantDocument).select(docId)
                .checkAll(TenantDocument.Perm)
                .by(User, userId);
        var out = new LinkedHashMap<String, Boolean>(raw.size());
        raw.forEach((k, v) -> out.put(k.permissionName(), v));
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Lookup
    // ══════════════════════════════════════════════════════════════════

    /** 反向：某 user 能看哪些文档（在 org 和直接授权共同限制下）。 */
    public List<String> visibleTo(String userId, int limit) {
        return auth.on(TenantDocument)
                .findBy(User, userId)
                .limit(limit)
                .can(Perm.VIEW);
    }

    /** 谁能看这份文档（返回的 subject 已经过 intersection 过滤）。 */
    public List<String> viewersOf(String docId) {
        return auth.on(TenantDocument).select(docId)
                .who(User, Perm.VIEW)
                .fetchIds();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Bulk queries
    // ══════════════════════════════════════════════════════════════════

    /**
     * 批量 check：UI 列表显示多份文档时，一次 RPC 拿到所有
     * (doc, perm) 的访问性。
     */
    public Map<String, Boolean> canViewBulk(List<String> docIds, String userId) {
        var subject = SubjectRef.of(User.name(), userId);
        var batch = auth.batchCheck();
        for (String id : docIds) {
            batch.add(TenantDocument, id, Perm.VIEW, subject);
        }
        var matrix = batch.fetch();
        var out = new LinkedHashMap<String, Boolean>(docIds.size());
        for (String id : docIds) {
            out.put(id, matrix.allowed(
                    TenantDocument.name() + ":" + id, "view", subject.toRefString()));
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Composite: cross-org leak self-check
    // ══════════════════════════════════════════════════════════════════

    /**
     * 给调用方明示：这份 doc 属于哪个 org。用于审计或诊断"为什么 alice
     * 看不到"。
     */
    public List<String> orgOf(String docId) {
        var tuples = auth.resource(TenantDocument.name(), docId)
                .relations("org")
                .fetch();
        var out = new ArrayList<String>(tuples.size());
        for (var t : tuples) {
            out.add(t.subjectType() + ":" + t.subjectId());
        }
        return out;
    }
}
