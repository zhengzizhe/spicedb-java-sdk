package com.authcses.testapp;

import com.authcses.sdk.AuthCsesClient;
import com.authcses.testapp.schema.constants.Document;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for testing permissions — uses codegen enums.
 *
 * curl localhost:8081/check?type=document&id=doc-1&permission=view&user=alice
 * curl -X POST localhost:8081/grant -d '{"type":"document","id":"doc-1","relation":"viewer","user":"alice"}'
 */
@RestController
public class PermissionController {

    private final AuthCsesClient client;

    public PermissionController(AuthCsesClient client) {
        this.client = client;
    }

    @GetMapping("/check")
    public Map<String, Object> check(@RequestParam String type,
                                      @RequestParam String id,
                                      @RequestParam String permission,
                                      @RequestParam String user) {
        // Resolve enum from string (API still accepts string for flexibility)
        boolean allowed = client.on(type).check(id, permission, user);
        return Map.of("allowed", allowed, "type", type, "id", id,
                "permission", permission, "user", user);
    }

    @PostMapping("/grant")
    public Map<String, String> grant(@RequestBody Map<String, String> body) {
        client.on(body.get("type")).grant(body.get("id"), body.get("relation"), body.get("user"));
        return Map.of("status", "granted");
    }

    @PostMapping("/revoke")
    public Map<String, String> revoke(@RequestBody Map<String, String> body) {
        client.on(body.get("type")).revoke(body.get("id"), body.get("relation"), body.get("user"));
        return Map.of("status", "revoked");
    }

    /**
     * Type-safe endpoint — uses codegen enums.
     * Demonstrates compile-time safety: Document.Perm.VIEW, Document.Rel.EDITOR etc.
     */
    @GetMapping("/doc/permissions")
    public Map<String, Boolean> docPermissions(@RequestParam String id, @RequestParam String user) {
        var doc = client.on("document");
        return Map.of(
                "view",   doc.check(id, Document.Perm.VIEW, user),
                "edit",   doc.check(id, Document.Perm.EDIT, user),
                "delete", doc.check(id, Document.Perm.DELETE, user),
                "share",  doc.check(id, Document.Perm.SHARE, user));
    }

    @PostMapping("/doc/grant")
    public Map<String, String> docGrant(@RequestParam String id,
                                         @RequestParam String relation,
                                         @RequestParam String user) {
        var doc = client.on("document");
        var rel = Document.Rel.valueOf(relation.toUpperCase());  // string → enum
        doc.grant(id, rel, user);
        return Map.of("status", "granted", "relation", rel.relationName(), "user", user);
    }

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
