package com.authx.testapp;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.ResourceFactory;
import com.authx.testapp.schema.ResourceTypes;
import com.authx.testapp.schema.constants.Document;
import com.authx.testapp.schema.constants.Folder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API — all methods use codegen enums, zero hardcoded strings.
 */
@RestController
public class PermissionController {

    private final AuthxClient client;
    private final ResourceFactory doc;
    private final ResourceFactory folder;

    public PermissionController(AuthxClient client) {
        this.client = client;
        this.doc = client.on(ResourceTypes.DOCUMENT);
        this.folder = client.on(ResourceTypes.FOLDER);
    }

    // ---- Document ----

    @PostMapping("/doc/grant")
    public Map<String, String> docGrant(@RequestParam String id,
                                         @RequestParam String relation,
                                         @RequestParam String user) {
        var rel = Document.Rel.valueOf(relation.toUpperCase());
        doc.grant(id, rel, user);
        return Map.of("status", "granted", "relation", rel.relationName(), "user", user);
    }

    @PostMapping("/doc/revoke")
    public Map<String, String> docRevoke(@RequestParam String id,
                                          @RequestParam String relation,
                                          @RequestParam String user) {
        var rel = Document.Rel.valueOf(relation.toUpperCase());
        doc.revoke(id, rel, user);
        return Map.of("status", "revoked", "relation", rel.relationName(), "user", user);
    }

    @GetMapping("/doc/check")
    public Map<String, Object> docCheck(@RequestParam String id,
                                         @RequestParam String permission,
                                         @RequestParam String user) {
        var perm = Document.Perm.valueOf(permission.toUpperCase());
        boolean allowed = doc.check(id, perm, user);
        return Map.of("allowed", allowed, "permission", perm.permissionName(), "user", user);
    }

    @GetMapping("/doc/permissions")
    public Map<String, Boolean> docPermissions(@RequestParam String id, @RequestParam String user) {
        return Map.of(
                "view",   doc.check(id, Document.Perm.VIEW, user),
                "edit",   doc.check(id, Document.Perm.EDIT, user),
                "comment", doc.check(id, Document.Perm.COMMENT, user),
                "delete", doc.check(id, Document.Perm.DELETE, user),
                "share",  doc.check(id, Document.Perm.SHARE, user),
                "manage", doc.check(id, Document.Perm.MANAGE, user));
    }

    @GetMapping("/doc/viewers")
    public List<String> docViewers(@RequestParam String id) {
        return doc.subjects(id, Document.Perm.VIEW.permissionName());
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
        folder.grant(id, rel, user);
        return Map.of("status", "granted", "relation", rel.relationName(), "user", user);
    }

    @GetMapping("/folder/check")
    public Map<String, Object> folderCheck(@RequestParam String id,
                                            @RequestParam String permission,
                                            @RequestParam String user) {
        var perm = Folder.Perm.valueOf(permission.toUpperCase());
        boolean allowed = folder.check(id, perm, user);
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
