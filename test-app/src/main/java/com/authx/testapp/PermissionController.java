package com.authx.testapp;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.SubjectRef;
import com.authx.testapp.schema.DocumentResource;
import com.authx.testapp.schema.FolderResource;
import com.authx.testapp.schema.SpaceResource;
import com.authx.testapp.schema.constants.Document;
import com.authx.testapp.schema.constants.Folder;
import com.authx.testapp.schema.constants.Space;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Demo surface for the 2.0.0 typed chain API.
 *
 * <p>Every endpoint uses the compile-time-safe enum-parameterised chain:
 * {@code doc.select(id).grant(Rel.X).toUser(...)} /
 * {@code .check(Perm.X).by(...)} / {@code .who(Perm.X).asUserIds()} /
 * {@code .findBy(Subjects.user(...)).can(Perm.X)}. Subject-type mismatches
 * (e.g. granting a folder subject to an editor relation) are caught at
 * runtime by {@code SchemaCache.validateSubject} and raise
 * {@code IllegalArgumentException} with a clear message, without going to
 * SpiceDB at all.
 */
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

    // ═══════════════════════════════════════════════════════════════
    //  Document — check
    // ═══════════════════════════════════════════════════════════════

    /** Single-subject × single-permission — the 99% case. Returns boolean. */
    @GetMapping("/doc/check")
    public Map<String, Object> docCheck(@RequestParam String id,
                                         @RequestParam String permission,
                                         @RequestParam String user) {
        var perm = Document.Perm.valueOf(permission.toUpperCase());
        boolean allowed = doc.select(id).check(perm).by(user);
        return Map.of("allowed", allowed, "doc", id,
                "permission", perm.permissionName(), "user", user);
    }

    /** Caveat-aware check — returns full CheckResult with three-state permissionship. */
    @GetMapping("/doc/check/detailed")
    public Map<String, Object> docCheckDetailed(@RequestParam String id,
                                                  @RequestParam String permission,
                                                  @RequestParam String user) {
        var perm = Document.Perm.valueOf(permission.toUpperCase());
        CheckResult r = doc.select(id).check(perm).detailedBy(user);
        return Map.of(
                "permissionship", r.permissionship().name(),
                "hasPermission", r.hasPermission(),
                "zedToken", r.zedToken() != null ? r.zedToken() : "",
                "doc", id,
                "permission", perm.permissionName(),
                "user", user);
    }

    /** Matrix check — N docs × M perms × K users → CheckMatrix (flat, indexable). */
    @GetMapping("/doc/matrix")
    public Map<String, Object> docMatrix(@RequestParam List<String> ids,
                                          @RequestParam List<String> users) {
        CheckMatrix matrix = doc.select(ids.toArray(String[]::new))
                .check(Document.Perm.VIEW, Document.Perm.EDIT, Document.Perm.COMMENT)
                .by(users.toArray(String[]::new));
        return Map.of(
                "cells", matrix.size(),
                "allAllowed", matrix.allAllowed(),
                "anyDenied", matrix.anyDenied(),
                "rows", matrix.cells());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Document — grant / revoke (chain API, subject-type runtime-validated)
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/doc/grant")
    public Map<String, String> grantRel(@RequestParam String id,
                                          @RequestParam String relation,
                                          @RequestParam String user) {
        var rel = Document.Rel.valueOf(relation.toUpperCase());
        doc.select(id).grant(rel).toUser(user);
        return Map.of("status", "granted", "doc", id, "relation", rel.relationName(), "user", user);
    }

    @PostMapping("/doc/revoke")
    public Map<String, String> revokeRel(@RequestParam String id,
                                           @RequestParam String relation,
                                           @RequestParam String user) {
        var rel = Document.Rel.valueOf(relation.toUpperCase());
        doc.select(id).revoke(rel).fromUser(user);
        return Map.of("status", "revoked", "doc", id, "relation", rel.relationName(), "user", user);
    }

    /** Demonstrates a grant via SubjectRef for non-user subject types. */
    @PostMapping("/doc/grant/folder-link")
    public Map<String, String> linkDocToFolder(@RequestParam String docId,
                                                 @RequestParam String folderId) {
        // Schema: document.folder relation accepts folder subject type only
        doc.select(docId).grant(Document.Rel.FOLDER)
                .to(SubjectRef.of("folder", folderId, null));
        return Map.of("status", "linked", "doc", docId, "folder", folderId);
    }

    /**
     * Demonstrates runtime validation: editor relation rejects a folder subject
     * via {@code SchemaCache.validateSubject}. The SDK throws
     * {@link com.authx.sdk.exception.InvalidRelationException} with a clear
     * message listing the allowed subject types, so the business code can
     * surface a meaningful error instead of hitting an opaque SpiceDB gRPC
     * rejection after a round-trip.
     */
    @GetMapping("/doc/grant/invalid")
    public Map<String, Object> demonstrateInvalid(@RequestParam String docId) {
        try {
            doc.select(docId).grant(Document.Rel.EDITOR)
                    .to(SubjectRef.of("folder", "f-whatever", null));
            return Map.of("status", "unexpectedly succeeded");
        } catch (com.authx.sdk.exception.InvalidRelationException e) {
            return Map.of("status", "correctly rejected", "error", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Who / Find (typed lookupSubjects / lookupResources)
    // ═══════════════════════════════════════════════════════════════

    /** "Who can view this document?" — lookupSubjects */
    @GetMapping("/doc/viewers")
    public Map<String, Object> whoCanView(@RequestParam String id,
                                            @RequestParam(defaultValue = "50") int limit) {
        List<String> viewers = doc.select(id).who(Document.Perm.VIEW).limit(limit).asUserIds();
        return Map.of("doc", id, "viewers", viewers, "count", viewers.size());
    }

    /** "Who can edit this document?" */
    @GetMapping("/doc/editors")
    public Map<String, Object> whoCanEdit(@RequestParam String id,
                                            @RequestParam(defaultValue = "50") int limit) {
        List<String> editors = doc.select(id).who(Document.Perm.EDIT).limit(limit).asUserIds();
        return Map.of("doc", id, "editors", editors, "count", editors.size());
    }

    /** "Which documents can this user access for a given permission?" — lookupResources */
    @GetMapping("/doc/find")
    public Map<String, Object> findDocs(@RequestParam String user,
                                          @RequestParam(defaultValue = "view") String permission,
                                          @RequestParam(defaultValue = "100") int limit) {
        var perm = Document.Perm.valueOf(permission.toUpperCase());
        List<String> docs = doc.findBy(SubjectRef.user(user)).limit(limit).can(perm);
        return Map.of("user", user, "permission", permission, "docs", docs, "count", docs.size());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Folder / Space — same chain shape, narrower surface
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/folder/check")
    public Map<String, Object> folderCheck(@RequestParam String id,
                                             @RequestParam String permission,
                                             @RequestParam String user) {
        var perm = Folder.Perm.valueOf(permission.toUpperCase());
        boolean allowed = folder.select(id).check(perm).by(user);
        return Map.of("allowed", allowed, "folder", id,
                "permission", perm.permissionName(), "user", user);
    }

    @GetMapping("/space/check")
    public Map<String, Object> spaceCheck(@RequestParam String id,
                                            @RequestParam String permission,
                                            @RequestParam String user) {
        var perm = Space.Perm.valueOf(permission.toUpperCase());
        boolean allowed = space.select(id).check(perm).by(user);
        return Map.of("allowed", allowed, "space", id,
                "permission", perm.permissionName(), "user", user);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Health + metrics
    // ═══════════════════════════════════════════════════════════════

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
        return Map.of("metrics", m.toString(), "cacheSize", cache != null ? cache.size() : 0);
    }
}
