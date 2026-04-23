package com.authx.testapp.controller;

import com.authx.testapp.dto.ApiDtos.IdsResponse;
import com.authx.testapp.dto.ApiDtos.UserRequest;
import com.authx.testapp.dto.ApiDtos.UsersRequest;
import com.authx.testapp.schema.TenantDocument;
import com.authx.testapp.service.TenantDocumentService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST surface for {@code tenant_document} — illustrates the
 * "intersection with org membership" multi-tenant isolation pattern.
 *
 * <p>Invariants demonstrated across the endpoints:
 * <ul>
 *   <li>A document cannot be accessed without being bound to an org
 *       ({@code PUT /tenant-documents} creates + binds atomically)</li>
 *   <li>Removing a user from the org silently revokes their access to
 *       every tenant_document in that org (no tuple cleanup needed)</li>
 *   <li>Org admins automatically inherit access to all tenant_documents
 *       in their org</li>
 * </ul>
 */
@RestController
@RequestMapping("/tenant-documents")
public class TenantDocumentController {

    private final TenantDocumentService svc;

    public TenantDocumentController(TenantDocumentService svc) {
        this.svc = svc;
    }

    // ── Create & tenant binding ──────────────────────────────────────

    /** 创建文档并原子绑定 org。必须先调这个，后续授权才会真正生效。 */
    @PutMapping("/{docId}")
    public ResponseEntity<Void> create(@PathVariable String docId,
                                         @RequestParam String orgId,
                                         @RequestParam String ownerId) {
        svc.createInOrg(docId, orgId, ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/{docId}/org")
    public ResponseEntity<IdsResponse> orgOf(@PathVariable String docId) {
        return ResponseEntity.ok(new IdsResponse(svc.orgOf(docId)));
    }

    // ── Viewer / editor grants ───────────────────────────────────────

    @PostMapping("/{docId}/viewers")
    public ResponseEntity<Void> addViewer(@PathVariable String docId,
                                            @RequestBody UserRequest body) {
        svc.addViewer(docId, body.userId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{docId}/viewers/batch")
    public ResponseEntity<Void> addViewers(@PathVariable String docId,
                                             @RequestBody UsersRequest body) {
        svc.addViewers(docId, body.userIds());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{docId}/editors")
    public ResponseEntity<Void> addEditor(@PathVariable String docId,
                                            @RequestBody UserRequest body) {
        svc.addEditor(docId, body.userId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{docId}/share/group/{groupId}")
    public ResponseEntity<Void> shareWithGroup(@PathVariable String docId,
                                                 @PathVariable String groupId) {
        svc.shareWithGroup(docId, groupId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/{docId}/share/public")
    public ResponseEntity<Void> shareInOrgPublic(@PathVariable String docId) {
        svc.shareWithinOrgPublic(docId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // ── Role change & transfer (atomic) ──────────────────────────────

    @PutMapping("/{docId}/role/{userId}")
    public ResponseEntity<Void> changeRole(@PathVariable String docId,
                                             @PathVariable String userId,
                                             @RequestParam TenantDocument.Rel from,
                                             @RequestParam TenantDocument.Rel to) {
        svc.changeRole(docId, userId, from, to);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{docId}/owner")
    public ResponseEntity<Void> transferOwnership(@PathVariable String docId,
                                                    @RequestParam String from,
                                                    @RequestParam String to) {
        svc.transferOwnership(docId, from, to);
        return ResponseEntity.noContent().build();
    }

    // ── Revoke / ban ─────────────────────────────────────────────────

    @DeleteMapping("/{docId}/access/{userId}")
    public ResponseEntity<Void> removeAccess(@PathVariable String docId,
                                                @PathVariable String userId) {
        svc.removeAccess(docId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{docId}/ban/{userId}")
    public ResponseEntity<Void> ban(@PathVariable String docId,
                                      @PathVariable String userId) {
        svc.ban(docId, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{docId}/ban/{userId}")
    public ResponseEntity<Void> unban(@PathVariable String docId,
                                        @PathVariable String userId) {
        svc.unban(docId, userId);
        return ResponseEntity.noContent().build();
    }

    // ── Org membership (the tenant gate) ─────────────────────────────

    @PostMapping("/orgs/{orgId}/members")
    public ResponseEntity<Void> addOrgMember(@PathVariable String orgId,
                                                @RequestBody UserRequest body) {
        svc.addOrgMember(orgId, body.userId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** 踢出 org → 所有 tenant_document 权限瞬间失效。 */
    @DeleteMapping("/orgs/{orgId}/members/{userId}")
    public ResponseEntity<Void> removeOrgMember(@PathVariable String orgId,
                                                   @PathVariable String userId) {
        svc.removeOrgMember(orgId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/orgs/{orgId}/admins")
    public ResponseEntity<Void> promoteAdmin(@PathVariable String orgId,
                                                @RequestBody UserRequest body) {
        svc.promoteOrgAdmin(orgId, body.userId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // ── Check & read ─────────────────────────────────────────────────

    @GetMapping("/{docId}/can/{perm}")
    public ResponseEntity<Map<String, Object>> can(@PathVariable String docId,
                                                    @PathVariable TenantDocument.Perm perm,
                                                    @RequestParam String userId) {
        boolean allowed = svc.can(docId, perm, userId);
        return ResponseEntity.ok(Map.of(
                "docId", docId, "permission", perm.permissionName(),
                "userId", userId, "allowed", allowed));
    }

    @GetMapping("/{docId}/permissions")
    public ResponseEntity<Map<String, Boolean>> allPermissions(@PathVariable String docId,
                                                                 @RequestParam String userId) {
        return ResponseEntity.ok(svc.permissionsFor(docId, userId));
    }

    @GetMapping("/{docId}/viewers")
    public ResponseEntity<IdsResponse> viewersOf(@PathVariable String docId) {
        return ResponseEntity.ok(new IdsResponse(svc.viewersOf(docId)));
    }

    @GetMapping("/accessible")
    public ResponseEntity<IdsResponse> visibleTo(@RequestParam String userId,
                                                   @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(new IdsResponse(svc.visibleTo(userId, limit)));
    }

    @PostMapping("/bulk-can-view")
    public ResponseEntity<Map<String, Boolean>> bulkCanView(@RequestParam String userId,
                                                              @RequestBody List<String> docIds) {
        return ResponseEntity.ok(svc.canViewBulk(docIds, userId));
    }
}
