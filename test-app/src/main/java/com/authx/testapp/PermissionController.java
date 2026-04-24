package com.authx.testapp;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.PermissionSet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/permissions")
public class PermissionController {

    private final AuthxClient client;

    public PermissionController(AuthxClient client) {
        this.client = client;
    }

    @PostMapping("/check")
    public CheckResponse check(@RequestBody CheckRequest req) {
        boolean allowed = client.on(req.resourceType())
                .resource(req.resourceId())
                .check(req.permission())
                .by(req.subject()).hasPermission();
        return new CheckResponse(allowed);
    }

    @PostMapping("/check-all")
    public CheckAllResponse checkAll(@RequestBody CheckAllRequest req) {
        PermissionSet set = client.on(req.resourceType())
                .resource(req.resourceId())
                .checkAll(req.permissions().toArray(new String[0]))
                .by(req.subject());
        return new CheckAllResponse(set.allowed(), set.denied());
    }

    @PostMapping("/grant")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(@RequestBody WriteRequest req) {
        client.on(req.resourceType())
                .resource(req.resourceId())
                .grant(req.relation())
                .to(req.subject());
    }

    @PostMapping("/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@RequestBody WriteRequest req) {
        client.on(req.resourceType())
                .resource(req.resourceId())
                .revoke(req.relation())
                .from(req.subject());
    }

    @GetMapping("/who")
    public WhoResponse who(@RequestParam String resourceType,
                           @RequestParam String resourceId,
                           @RequestParam String permission,
                           @RequestParam(defaultValue = "user") String subjectType) {
        List<String> subjects = client.on(resourceType)
                .resource(resourceId)
                .who(subjectType)
                .withPermission(permission)
                .fetch();
        return new WhoResponse(subjects);
    }

    @GetMapping("/lookup")
    public LookupResponse lookup(@RequestParam String resourceType,
                                 @RequestParam String permission,
                                 @RequestParam String subject) {
        List<String> resources = client.lookup(resourceType)
                .withPermission(permission)
                .by(subject)
                .fetch();
        return new LookupResponse(resources);
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse(client.health().isHealthy());
    }

    public record CheckRequest(String resourceType, String resourceId, String permission, String subject) {}
    public record CheckResponse(boolean allowed) {}

    public record CheckAllRequest(String resourceType, String resourceId, List<String> permissions, String subject) {}
    public record CheckAllResponse(Set<String> allowed, Set<String> denied) {}

    public record WriteRequest(String resourceType, String resourceId, String relation, String subject) {}

    public record WhoResponse(List<String> subjects) {}
    public record LookupResponse(List<String> resources) {}
    public record HealthResponse(boolean healthy) {}
}
