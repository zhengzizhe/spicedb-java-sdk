package com.authx.testapp.service;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.SubjectRef;
import com.authx.testapp.schema.Document;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档分享相关的业务逻辑。
 *
 * <p>这个 service 只持有 {@link AuthxClient}。所有操作都从 {@code client}
 * 开始链式调用 —— {@code client.on(Document.TYPE).select(...).xxx(...)} ——
 * 没有 {@code Document.check(client, ...)} 这种把 client 塞回去的静态方法，
 * 也没有包装对象。{@code Document} 类本身只是类型元数据 (enum + ResourceType 常量)。
 */
@Service
public class DocumentSharingService {

    private final AuthxClient client;

    public DocumentSharingService(AuthxClient client) {
        this.client = client;
    }

    public enum ShareLevel { VIEWER, COMMENTER, EDITOR }

    // ═══════════════════════════════════════════════════════════════
    //  读取权限
    // ═══════════════════════════════════════════════════════════════

    /** 用户能打开这个文档吗? 一个 check RPC. */
    public boolean canOpen(String userId, String docId) {
        return client.on(Document.TYPE)
                .select(docId)
                .check(Document.Perm.VIEW)
                .by(userId);
    }

    public boolean canEdit(String userId, String docId) {
        return client.on(Document.TYPE)
                .select(docId)
                .check(Document.Perm.EDIT)
                .by(userId);
    }

    /**
     * 文档详情页工具栏：一次 RPC 拿 schema 里**所有** permission 的状态。
     * 未来 schema 加 permission，codegen 重跑就自动带上 —— {@code checkAll()}
     * 的 permission 集合是从 {@code Document.TYPE} 描述符里拿的。
     */
    public EnumMap<Document.Perm, Boolean> computeToolbarFor(String userId, String docId) {
        return client.on(Document.TYPE)
                .select(docId)
                .checkAll()
                .by(userId);
    }

    /** 文档列表页：N 个 doc × 所有 perm，一次 RPC. */
    public Map<String, EnumMap<Document.Perm, Boolean>> permissionsForList(
            String userId, List<String> docIds) {
        if (docIds.isEmpty()) return Map.of();
        return client.on(Document.TYPE)
                .select(docIds)
                .checkAll()
                .byAll(userId);
    }

    /** 只要 view 的轻量版过滤. */
    public Map<String, Boolean> filterVisible(String userId, List<String> candidateDocIds) {
        if (candidateDocIds.isEmpty()) return Map.of();
        CheckMatrix m = client.on(Document.TYPE)
                .select(candidateDocIds)
                .check(Document.Perm.VIEW)
                .byAll(userId);
        var out = new LinkedHashMap<String, Boolean>();
        for (String id : candidateDocIds) {
            out.put(id, m.allowed(id, "view", userId));
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    //  分享 (grant)
    // ═══════════════════════════════════════════════════════════════

    /** 单用户分享. */
    public void shareWithUser(String docId, String targetUserId, ShareLevel level) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(relFor(level))
                .to("user:" + targetUserId);
    }

    /** 批量分享 —— 给 userIds 批量 grant. */
    public void shareWithUsers(String docId, List<String> userIds, ShareLevel level) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(relFor(level))
                .to(userIds.stream().map(u -> "user:" + u).toArray(String[]::new));
    }

    /** 分享给一个组 (group#member subject). */
    public void shareWithGroup(String docId, String groupId, ShareLevel level) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(relFor(level))
                .to("group:" + groupId + "#member");
    }

    /** 公开文档 —— 所有用户可见 (user:* 通配符). */
    public void makePublic(String docId) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(Document.Rel.VIEWER)
                .to("user:*");
    }

    /** 限时分享 —— SpiceDB 关系过期字段, 到期自动失效. */
    public void shareTemporarily(String docId, String targetUserId, ShareLevel level, Duration ttl) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(relFor(level))
                .expiringIn(ttl)
                .to("user:" + targetUserId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  撤销分享
    // ═══════════════════════════════════════════════════════════════

    public void unshareWithUser(String docId, String targetUserId) {
        String subj = "user:" + targetUserId;
        client.on(Document.TYPE).select(docId).revoke(Document.Rel.VIEWER).from(subj);
        client.on(Document.TYPE).select(docId).revoke(Document.Rel.COMMENTER).from(subj);
        client.on(Document.TYPE).select(docId).revoke(Document.Rel.EDITOR).from(subj);
    }

    // ═══════════════════════════════════════════════════════════════
    //  层级关系 (跨类型 grant)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 把文档挂到文件夹下 —— 建立 document.folder 跨类型关系。
     * subject 是 folder 不是 user，用 to(SubjectRef). 写错 subject 类型会由
     * SpiceDB 服务端以 INVALID_ARGUMENT 拒绝并映射为
     * AuthxInvalidArgumentException.
     */
    public void moveIntoFolder(String docId, String folderId) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(Document.Rel.FOLDER)
                .to(SubjectRef.of("folder", folderId, null));
    }

    public void attachToSpace(String docId, String spaceId) {
        client.on(Document.TYPE)
                .select(docId)
                .grant(Document.Rel.SPACE)
                .to(SubjectRef.of("space", spaceId, null));
    }

    // ═══════════════════════════════════════════════════════════════
    //  反查 / 正查
    // ═══════════════════════════════════════════════════════════════

    /** "谁能编辑这个文档?" (只查 user 主体) */
    public List<String> listEditors(String docId, int max) {
        return client.on(Document.TYPE)
                .select(docId)
                .who("user", Document.Perm.EDIT)
                .limit(max)
                .fetchIds();
    }

    public List<String> listViewers(String docId, int max) {
        return client.on(Document.TYPE)
                .select(docId)
                .who("user", Document.Perm.VIEW)
                .limit(max)
                .fetchIds();
    }

    /** "用户能看的所有文档" —— 首页 feed / 搜索索引用. */
    public List<String> myReadableDocs(String userId, int max) {
        return client.on(Document.TYPE)
                .findBy(SubjectRef.of("user", userId))
                .limit(max)
                .can(Document.Perm.VIEW);
    }

    public List<String> myEditableDocs(String userId, int max) {
        return client.on(Document.TYPE)
                .findBy(SubjectRef.of("user", userId))
                .limit(max)
                .can(Document.Perm.EDIT);
    }

    /**
     * 多 permission 一次问 —— view / edit / comment 各一次 LookupResources RPC,
     * 返回 {@code Map<Perm, List<docId>>}. 用 {@code .can(Perm...)} 多态重载.
     */
    public Map<Document.Perm, List<String>> myDocsByPermissions(String userId, int max) {
        return client.on(Document.TYPE)
                .findBy(SubjectRef.of("user", userId))
                .limit(max)
                .can(Document.Perm.VIEW, Document.Perm.EDIT, Document.Perm.COMMENT);
    }

    /**
     * 多用户反查 —— N 个用户每人能看哪些 doc. 一次 findBy(Collection<SubjectRef>)
     * 收集成 {@code Map<subjectRef, List<docId>>}.
     */
    public Map<String, List<String>> readableDocsForUsers(List<String> userIds, int max) {
        var subjects = userIds.stream().map(u -> SubjectRef.of("user", u)).toList();
        return client.on(Document.TYPE)
                .findBy(subjects)
                .limit(max)
                .can(Document.Perm.VIEW);
    }

    /**
     * 批量撤销 —— 一次调用把文档从 N 个用户那里收回 VIEWER / COMMENTER / EDITOR 三种关系.
     */
    public void unshareWithMany(String docId, List<String> userIds) {
        client.on(Document.TYPE)
                .select(docId)
                .revoke(Document.Rel.VIEWER, Document.Rel.COMMENTER, Document.Rel.EDITOR)
                .from(userIds.stream().map(u -> "user:" + u).toArray(String[]::new));
    }

    // ─── internal ────────────────────────────────────────────────

    private static Document.Rel relFor(ShareLevel level) {
        return switch (level) {
            case VIEWER    -> Document.Rel.VIEWER;
            case COMMENTER -> Document.Rel.COMMENTER;
            case EDITOR    -> Document.Rel.EDITOR;
        };
    }
}
