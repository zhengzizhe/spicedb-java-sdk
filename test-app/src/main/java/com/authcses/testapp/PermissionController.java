package com.authcses.testapp;

import com.authcses.sdk.AuthCsesClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Simple REST API for manually testing permissions.
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
        boolean allowed = client.check(type, id, permission, user);
        return Map.of("allowed", allowed, "type", type, "id", id,
                "permission", permission, "user", user);
    }

    @PostMapping("/grant")
    public Map<String, String> grant(@RequestBody Map<String, String> body) {
        client.grant(body.get("type"), body.get("id"), body.get("relation"), body.get("user"));
        return Map.of("status", "granted");
    }

    @PostMapping("/revoke")
    public Map<String, String> revoke(@RequestBody Map<String, String> body) {
        client.revoke(body.get("type"), body.get("id"), body.get("relation"), body.get("user"));
        return Map.of("status", "revoked");
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
