package com.authx.testapp;

import com.authx.sdk.AuthxClient;
import com.authx.testapp.schema.DocumentResource;
import com.authx.testapp.schema.FolderResource;
import com.authx.testapp.schema.SpaceResource;
import com.authx.testapp.schema.constants.Document;
import com.authx.testapp.schema.constants.Folder;
import com.authx.testapp.schema.constants.Space;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class PermissionController {

    private final AuthxClient client;
    private final DocumentResource doc;
    private final FolderResource folder;
    private final SpaceResource space;

    public PermissionController(AuthxClient client) {
        this.client = client;
        this.doc = new DocumentResource(client);
        this.folder = new FolderResource(client);
        this.space = new SpaceResource(client);
    }

    // ---- Document ----

    @GetMapping("/doc/check")
    public Map<String, Object> docCheck(@RequestParam String id,
                                        @RequestParam String permission,
                                        @RequestParam String user) {
        var perm = Document.Perm.valueOf(permission.toUpperCase());
        boolean allowed = doc.select(id).check(perm).by(user);
        return Map.of("allowed", allowed, "doc", id, "permission", perm.permissionName(), "user", user);
    }

    @PostMapping("/doc/grant")
    public Map<String, String> docGrant(@RequestParam String id,
                                        @RequestParam String relation,
                                        @RequestParam String user) {
        var rel = Document.Rel.valueOf(relation.toUpperCase());
        doc.select(id).grant(rel).toUser(user);
        return Map.of("status", "granted", "doc", id, "relation", rel.relationName(), "user", user);
    }

    @PostMapping("/doc/revoke")
    public Map<String, String> docRevoke(@RequestParam String id,
                                         @RequestParam String relation,
                                         @RequestParam String user) {
        var rel = Document.Rel.valueOf(relation.toUpperCase());
        doc.select(id).revoke(rel).fromUser(user);
        return Map.of("status", "revoked", "doc", id, "relation", rel.relationName(), "user", user);
    }

    @GetMapping("/doc/permissions")
    public Map<String, Boolean> docPermissions(@RequestParam String id,
                                               @RequestParam String user) {
        var h = doc.select(id);
        return Map.of(
                "view",    h.check(Document.Perm.VIEW).by(user),
                "edit",    h.check(Document.Perm.EDIT).by(user),
                "comment", h.check(Document.Perm.COMMENT).by(user),
                "manage",  h.check(Document.Perm.MANAGE).by(user));
    }

    // ---- Folder ----

    @GetMapping("/folder/check")
    public Map<String, Object> folderCheck(@RequestParam String id,
                                           @RequestParam String permission,
                                           @RequestParam String user) {
        var perm = Folder.Perm.valueOf(permission.toUpperCase());
        boolean allowed = folder.select(id).check(perm).by(user);
        return Map.of("allowed", allowed, "folder", id, "permission", perm.permissionName(), "user", user);
    }

    // ---- Space ----

    @GetMapping("/space/check")
    public Map<String, Object> spaceCheck(@RequestParam String id,
                                          @RequestParam String permission,
                                          @RequestParam String user) {
        var perm = Space.Perm.valueOf(permission.toUpperCase());
        boolean allowed = space.select(id).check(perm).by(user);
        return Map.of("allowed", allowed, "space", id, "permission", perm.permissionName(), "user", user);
    }

    // ---- Health & Metrics ----

    @GetMapping("/health")
    public Map<String, Object> health() {
        var h = client.health();
        return Map.of(
                "healthy", h.isHealthy(),
                "latencyMs", h.spicedbLatencyMs(),
                "details", h.details());
    }

    @GetMapping("/metrics/sdk")
    public Map<String, Object> sdkMetrics() {
        var m = client.metrics().snapshot();
        var cache = client.cache();
        return Map.of(
                "metrics", m.toString(),
                "cacheSize", cache != null ? cache.size() : 0);
    }
}
