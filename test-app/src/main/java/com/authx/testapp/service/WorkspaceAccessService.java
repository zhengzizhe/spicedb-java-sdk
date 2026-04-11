package com.authx.testapp.service;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CheckMatrix;
import com.authx.testapp.schema.Document;
import com.authx.testapp.schema.Folder;
import com.authx.testapp.schema.Space;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨资源类型的业务操作。
 *
 * <p>单资源类型的 check / grant 走 {@code client.on(Xxx.TYPE).select(...)}
 * 链。跨类型的批量 check 走 {@code client.batchCheck()}, 跨类型的原子批量写
 * 走 {@code client.batch()}。两条批量入口都支持把 {@code Xxx.TYPE} 描述符
 * 直接传进去, 不需要手动取字符串.
 */
@Service
public class WorkspaceAccessService {

    private final AuthxClient client;

    public WorkspaceAccessService(AuthxClient client) {
        this.client = client;
    }

    // ═══════════════════════════════════════════════════════════════
    //  跨资源批量 check
    // ═══════════════════════════════════════════════════════════════

    /**
     * 渲染"工作区侧边栏" —— space + folder 的 7 个按钮状态一次 RPC 决定。
     * 用 {@code client.batchCheck()} 跨类型混合判断。
     */
    public WorkspaceView renderWorkspace(String userId, String spaceId, String folderId) {
        CheckMatrix m = client.batchCheck()
                .add(Space.TYPE,  spaceId,  Space.Perm.VIEW,          userId)
                .add(Space.TYPE,  spaceId,  Space.Perm.EDIT,          userId)
                .add(Space.TYPE,  spaceId,  Space.Perm.MANAGE,        userId)
                .add(Folder.TYPE, folderId, Folder.Perm.VIEW,         userId)
                .add(Folder.TYPE, folderId, Folder.Perm.EDIT,         userId)
                .add(Folder.TYPE, folderId, Folder.Perm.CREATE_CHILD, userId)
                .add(Folder.TYPE, folderId, Folder.Perm.MANAGE,       userId)
                .fetch();

        return new WorkspaceView(
                m.allowed(Space.TYPE  + ":" + spaceId,  "view",         userId),
                m.allowed(Space.TYPE  + ":" + spaceId,  "edit",         userId),
                m.allowed(Space.TYPE  + ":" + spaceId,  "manage",       userId),
                m.allowed(Folder.TYPE + ":" + folderId, "view",         userId),
                m.allowed(Folder.TYPE + ":" + folderId, "edit",         userId),
                m.allowed(Folder.TYPE + ":" + folderId, "create_child", userId),
                m.allowed(Folder.TYPE + ":" + folderId, "manage",       userId));
    }

    /**
     * 批量判断用户对一批 mixed-type 资源的可见性 —— 前端分页一次拉 50 条
     * (doc/folder/space 混合), 一个 RPC 返回全部 boolean.
     */
    public Map<String, Boolean> filterVisibleMixed(String userId, List<WorkspaceItem> items) {
        if (items.isEmpty()) return Map.of();
        var builder = client.batchCheck();
        for (var item : items) {
            switch (item.type()) {
                case "document" -> builder.add(Document.TYPE, item.id(), Document.Perm.VIEW, userId);
                case "folder"   -> builder.add(Folder.TYPE,   item.id(), Folder.Perm.VIEW,   userId);
                case "space"    -> builder.add(Space.TYPE,    item.id(), Space.Perm.VIEW,    userId);
                default -> throw new IllegalArgumentException("Unknown resource type: " + item.type());
            }
        }
        CheckMatrix m = builder.fetch();
        var out = new LinkedHashMap<String, Boolean>();
        for (var item : items) {
            out.put(item.id(), m.allowed(item.type() + ":" + item.id(), "view", userId));
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    //  跨资源原子批量写
    // ═══════════════════════════════════════════════════════════════

    /**
     * "新员工入职一键配权" —— 原子 WriteRelationships 一次写入 space/folder/N 个 doc。
     * 全成全败, 返回 zedToken 给后续 session 一致性读用.
     */
    public String onboardNewMember(String spaceId, String defaultFolderId,
                                    String userId, List<String> welcomeDocIds) {
        // Single-id scope for space + folder, then a batched multi-id scope
        // fans the doc viewer grant across every welcome doc in the same
        // atomic WriteRelationships RPC.
        var batch = client.batch()
                .on(Space.TYPE,  spaceId).grant(Space.Rel.MEMBER).to(userId)
                .on(Folder.TYPE, defaultFolderId).grant(Folder.Rel.VIEWER).to(userId);
        if (!welcomeDocIds.isEmpty()) {
            batch.onAll(Document.TYPE, welcomeDocIds).grant(Document.Rel.VIEWER).to(userId);
        }
        return batch.commit().zedToken();
    }

    /** "员工离职清权" —— 撤销该用户在 space / N folder / N doc 上的所有关系. */
    public void offboardMember(String spaceId, List<String> folderIds, List<String> docIds,
                                String userId) {
        var batch = client.batch()
                .on(Space.TYPE, spaceId).revoke(Space.Rel.MEMBER).from(userId);
        for (String fid : folderIds) {
            batch.on(Folder.TYPE, fid).revoke(Folder.Rel.VIEWER).from(userId);
            batch.on(Folder.TYPE, fid).revoke(Folder.Rel.EDITOR).from(userId);
        }
        for (String did : docIds) {
            batch.on(Document.TYPE, did).revoke(Document.Rel.VIEWER).from(userId);
            batch.on(Document.TYPE, did).revoke(Document.Rel.EDITOR).from(userId);
            batch.on(Document.TYPE, did).revoke(Document.Rel.COMMENTER).from(userId);
        }
        batch.commit();
    }

    public record WorkspaceItem(String type, String id) {}

    public record WorkspaceView(
            boolean canViewSpace,
            boolean canEditSpace,
            boolean canManageSpace,
            boolean canViewFolder,
            boolean canEditFolder,
            boolean canCreateChildInFolder,
            boolean canManageFolder) {}
}
