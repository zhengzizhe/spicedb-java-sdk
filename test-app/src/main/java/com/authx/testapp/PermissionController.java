package com.authx.testapp;

import com.authx.sdk.AuthxClient;
import com.authx.testapp.schema.DocumentResource;
import com.authx.testapp.schema.FolderResource;
import com.authx.testapp.schema.constants.Document;
import com.authx.testapp.schema.constants.Folder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API — 3-segment chain: on(id).grant(Rel).toUser(...)
 */
@RestController
public class PermissionController {

    private final AuthxClient client;
    private final DocumentResource doc;
    private final FolderResource folder;

    public PermissionController(AuthxClient client) {
        this.client = client;
        this.doc = new DocumentResource(client);
        this.folder = new FolderResource(client);
    }

    // ---- Document ----

    @PostMapping("/doc/grant")
    public Map<String, String> docGrant(@RequestParam String id,
                                         @RequestParam String relation,
                                         @RequestParam String user) {
        var rel = Document.Rel.valueOf(relation.toUpperCase());
        doc.on(id).grant(rel).toUser(user);
        return Map.of("status", "granted", "relation", rel.relationName(), "user", user);
    }

    @PostMapping("/doc/revoke")
    public Map<String, String> docRevoke(@RequestParam String id,
                                          @RequestParam String relation,
                                          @RequestParam String user) {
        var rel = Document.Rel.valueOf(relation.toUpperCase());
        doc.on(id).revoke(rel).fromUser(user);
        return Map.of("status", "revoked", "relation", rel.relationName(), "user", user);
    }

    @GetMapping("/doc/check")
    public Map<String, Object> docCheck(@RequestParam String id,
                                         @RequestParam String permission,
                                         @RequestParam String user) {
        var perm = Document.Perm.valueOf(permission.toUpperCase());
        boolean allowed = doc.on(id).check(perm).by(user);
        return Map.of("allowed", allowed, "permission", perm.permissionName(), "user", user);
    }

    @GetMapping("/doc/permissions")
    public Map<String, Boolean> docPermissions(@RequestParam String id, @RequestParam String user) {
        var h = doc.on(id);
        return Map.of(
                "view",    h.check(Document.Perm.VIEW).by(user),
                "edit",    h.check(Document.Perm.EDIT).by(user),
                "comment", h.check(Document.Perm.COMMENT).by(user),
                "delete",  h.check(Document.Perm.DELETE).by(user),
                "share",   h.check(Document.Perm.SHARE).by(user),
                "manage",  h.check(Document.Perm.MANAGE).by(user));
    }

    @GetMapping("/doc/relations")
    public Map<String, List<String>> docRelations(@RequestParam String id) {
        return doc.allRelations(id);
    }

    // ---- Folder ----

    @PostMapping("/folder/grant")
    public Map<String, String> folderGrant(@RequestParam String id,
                                            @RequestParam String relation,
                                            @RequestParam String user) {
        var rel = Folder.Rel.valueOf(relation.toUpperCase());
        folder.on(id).grant(rel).toUser(user);
        return Map.of("status", "granted", "relation", rel.relationName(), "user", user);
    }

    @GetMapping("/folder/check")
    public Map<String, Object> folderCheck(@RequestParam String id,
                                            @RequestParam String permission,
                                            @RequestParam String user) {
        var perm = Folder.Perm.valueOf(permission.toUpperCase());
        boolean allowed = folder.on(id).check(perm).by(user);
        return Map.of("allowed", allowed, "permission", perm.permissionName(), "user", user);
    }

    // ---- Health ----

    @GetMapping("/health")
    public Map<String, Object> health() {
        var h = client.health();
        var m = client.metrics().snapshot();
        return Map.of(
                "healthy", h.isHealthy(),
                "latencyMs", h.spicedbLatencyMs(),
                "details", h.details(),
                "metrics", m.toString());
    }
}
