package com.authx.testapp;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.SubjectRef;
import com.authx.testapp.schema.Document;
import com.authx.testapp.schema.Folder;
import com.authx.testapp.schema.Space;
import com.authx.testapp.service.DocumentSharingService;
import com.authx.testapp.service.WorkspaceAccessService;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP 面板 —— 薄的一层。业务逻辑在 {@link DocumentSharingService} /
 * {@link WorkspaceAccessService}，Controller 只管参数解析和 JSON 序列化。
 *
 * <p>Controller 只持有 {@link AuthxClient} + 两个 Service。业务代码需要
 * check/grant 时, 直接从 {@code client} 开始链：
 * {@code client.on(Document.TYPE).select(id).check(Document.Perm.VIEW).by(user)}.
 */
@RestController
public class PermissionController {

    private final AuthxClient client;
    private final DocumentSharingService docs;
    private final WorkspaceAccessService workspace;

    public PermissionController(AuthxClient client,
                                 DocumentSharingService docs,
                                 WorkspaceAccessService workspace) {
        this.client = client;
        this.docs = docs;
        this.workspace = workspace;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Document check
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/doc/can-open")
    public Map<String, Object> canOpen(@RequestParam String user, @RequestParam String doc) {
        return Map.of("allowed", docs.canOpen(user, doc), "user", user, "doc", doc);
    }

    @GetMapping("/doc/can-edit")
    public Map<String, Object> canEdit(@RequestParam String user, @RequestParam String doc) {
        return Map.of("allowed", docs.canEdit(user, doc), "user", user, "doc", doc);
    }

    /**
     * 文档详情页工具栏 —— 一次 RPC 拿 schema 里所有 permission 的状态。
     * 返回 EnumMap, schema 加 permission 这里完全不用改。
     */
    @GetMapping("/doc/toolbar")
    public Map<String, Boolean> toolbar(@RequestParam String user, @RequestParam String doc) {
        var perms = docs.computeToolbarFor(user, doc);
        var out = new java.util.LinkedHashMap<String, Boolean>();
        perms.forEach((k, v) -> out.put(k.name().toLowerCase(), v));
        return out;
    }

    /** 文档列表页过滤可见项. */
    @GetMapping("/doc/filter-visible")
    public Map<String, Boolean> filterVisible(@RequestParam String user,
                                                @RequestParam List<String> ids) {
        return docs.filterVisible(user, ids);
    }

    /** 文档列表页完整权限矩阵 —— N 个 doc × 所有 perm, 一次 RPC. */
    @GetMapping("/doc/list-permissions")
    public Map<String, Map<String, Boolean>> listPermissions(@RequestParam String user,
                                                               @RequestParam List<String> ids) {
        var full = docs.permissionsForList(user, ids);
        var out = new java.util.LinkedHashMap<String, Map<String, Boolean>>();
        full.forEach((docId, perms) -> {
            var row = new java.util.LinkedHashMap<String, Boolean>();
            perms.forEach((k, v) -> row.put(k.name().toLowerCase(), v));
            out.put(docId, row);
        });
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Document grant / revoke
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/doc/share-user")
    public Map<String, String> shareUser(@RequestParam String doc,
                                           @RequestParam String user,
                                           @RequestParam String level) {
        docs.shareWithUser(doc, user, DocumentSharingService.ShareLevel.valueOf(level.toUpperCase()));
        return Map.of("status", "shared", "doc", doc, "user", user, "level", level);
    }

    @PostMapping("/doc/share-group")
    public Map<String, String> shareGroup(@RequestParam String doc,
                                            @RequestParam String group,
                                            @RequestParam String level) {
        docs.shareWithGroup(doc, group, DocumentSharingService.ShareLevel.valueOf(level.toUpperCase()));
        return Map.of("status", "shared", "doc", doc, "group", group, "level", level);
    }

    @PostMapping("/doc/make-public")
    public Map<String, String> makePublic(@RequestParam String doc) {
        docs.makePublic(doc);
        return Map.of("status", "public", "doc", doc);
    }

    /** 限时分享 —— 默认 30 天后自动失效. */
    @PostMapping("/doc/share-temp")
    public Map<String, String> shareTemp(@RequestParam String doc,
                                           @RequestParam String user,
                                           @RequestParam(defaultValue = "30") int days,
                                           @RequestParam(defaultValue = "VIEWER") String level) {
        docs.shareTemporarily(doc, user,
                DocumentSharingService.ShareLevel.valueOf(level.toUpperCase()),
                Duration.ofDays(days));
        return Map.of("status", "shared", "doc", doc, "user", user,
                "expiresInDays", String.valueOf(days));
    }

    @PostMapping("/doc/unshare")
    public Map<String, String> unshare(@RequestParam String doc, @RequestParam String user) {
        docs.unshareWithUser(doc, user);
        return Map.of("status", "unshared", "doc", doc, "user", user);
    }

    /** 跨类型 grant: 文档挂到文件夹下 (document.folder 关系). */
    @PostMapping("/doc/move-to-folder")
    public Map<String, String> moveToFolder(@RequestParam String doc,
                                              @RequestParam String folder) {
        docs.moveIntoFolder(doc, folder);
        return Map.of("status", "moved", "doc", doc, "folder", folder);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Document lookup
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/doc/editors")
    public Map<String, Object> listEditors(@RequestParam String doc,
                                             @RequestParam(defaultValue = "50") int max) {
        var list = docs.listEditors(doc, max);
        return Map.of("doc", doc, "editors", list, "count", list.size());
    }

    @GetMapping("/doc/viewers")
    public Map<String, Object> listViewers(@RequestParam String doc,
                                             @RequestParam(defaultValue = "50") int max) {
        var list = docs.listViewers(doc, max);
        return Map.of("doc", doc, "viewers", list, "count", list.size());
    }

    @GetMapping("/doc/my-readable")
    public Map<String, Object> myReadable(@RequestParam String user,
                                            @RequestParam(defaultValue = "100") int max) {
        var list = docs.myReadableDocs(user, max);
        return Map.of("user", user, "docs", list, "count", list.size());
    }

    @GetMapping("/doc/my-editable")
    public Map<String, Object> myEditable(@RequestParam String user,
                                            @RequestParam(defaultValue = "100") int max) {
        var list = docs.myEditableDocs(user, max);
        return Map.of("user", user, "docs", list, "count", list.size());
    }

    /** 多 permission 一次问 —— findByUser.limit.can(P...) 变长重载. */
    @GetMapping("/doc/my-docs-by-perm")
    public Map<String, List<String>> myDocsByPerm(@RequestParam String user,
                                                    @RequestParam(defaultValue = "100") int max) {
        var result = docs.myDocsByPermissions(user, max);
        var out = new java.util.LinkedHashMap<String, List<String>>();
        result.forEach((p, ids) -> out.put(p.name().toLowerCase(), ids));
        return out;
    }

    /** 多用户反查 —— findByUsers(Collection).can(Perm). */
    @GetMapping("/doc/readable-for-users")
    public Map<String, List<String>> readableForUsers(@RequestParam List<String> users,
                                                        @RequestParam(defaultValue = "100") int max) {
        return docs.readableDocsForUsers(users, max);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Workspace — 跨资源
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/workspace/view")
    public WorkspaceAccessService.WorkspaceView workspaceView(@RequestParam String user,
                                                                 @RequestParam String space,
                                                                 @RequestParam String folder) {
        return workspace.renderWorkspace(user, space, folder);
    }

    @PostMapping("/workspace/onboard")
    public Map<String, Object> onboard(@RequestParam String user,
                                         @RequestParam String space,
                                         @RequestParam String defaultFolder,
                                         @RequestParam(required = false) List<String> welcomeDocs) {
        String zedToken = workspace.onboardNewMember(space, defaultFolder, user,
                welcomeDocs != null ? welcomeDocs : List.of());
        return Map.of(
                "status", "onboarded",
                "user", user,
                "space", space,
                "zedToken", zedToken != null ? zedToken : "",
                "writesCount", 2 + (welcomeDocs != null ? welcomeDocs.size() : 0));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Folder / Space direct check — 展示不同类型的链起点对等
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/folder/check")
    public Map<String, Object> folderCheck(@RequestParam String id,
                                             @RequestParam String permission,
                                             @RequestParam String user) {
        var perm = Folder.Perm.valueOf(permission.toUpperCase());
        boolean allowed = client.on(Folder.TYPE).select(id).check(perm).by(user);
        return Map.of("allowed", allowed, "folder", id, "permission", perm.permissionName(), "user", user);
    }

    @GetMapping("/space/check")
    public Map<String, Object> spaceCheck(@RequestParam String id,
                                            @RequestParam String permission,
                                            @RequestParam String user) {
        var perm = Space.Perm.valueOf(permission.toUpperCase());
        boolean allowed = client.on(Space.TYPE).select(id).check(perm).by(user);
        return Map.of("allowed", allowed, "space", id, "permission", perm.permissionName(), "user", user);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Runtime validation demo
    // ═══════════════════════════════════════════════════════════════

    /**
     * 故意在 editor relation 上挂 folder 主体 —— SchemaCache 本地拦住,
     * 不发 RPC.
     */
    @GetMapping("/demo/invalid-grant")
    public Map<String, Object> invalidGrantDemo(@RequestParam String doc) {
        try {
            client.on(Document.TYPE)
                    .select(doc)
                    .grant(Document.Rel.EDITOR)
                    .to(SubjectRef.of("folder", "f-invalid", null));
            return Map.of("status", "unexpectedly succeeded");
        } catch (com.authx.sdk.exception.InvalidRelationException e) {
            return Map.of(
                    "status", "rejected_locally",
                    "error", e.getMessage(),
                    "note", "SchemaCache 在本地 fast-fail, 完全没有发 RPC 到 SpiceDB");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Health + metrics
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/health")
    public Map<String, Object> health() {
        var h = client.health();
        return Map.of("healthy", h.isHealthy(),
                "latencyMs", h.spicedbLatencyMs(),
                "details", h.details());
    }

    @GetMapping("/metrics/sdk")
    public Map<String, Object> sdkMetrics() {
        var m = client.metrics().snapshot();
        return Map.of("metrics", m.toString());
    }
}
