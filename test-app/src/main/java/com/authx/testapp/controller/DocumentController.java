package com.authx.testapp.controller;

import com.authx.testapp.dto.ApiDtos.BatchCheckRequest;
import com.authx.testapp.dto.ApiDtos.BatchCheckResponse;
import com.authx.testapp.dto.ApiDtos.CheckResponse;
import com.authx.testapp.dto.ApiDtos.IdsResponse;
import com.authx.testapp.dto.ApiDtos.PermissionMatrixResponse;
import com.authx.testapp.dto.ApiDtos.PublicShareRequest;
import com.authx.testapp.dto.ApiDtos.UserRequest;
import com.authx.testapp.dto.ApiDtos.WriteResponse;
import com.authx.testapp.service.DocumentService;

import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
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

/**
 * Document permission REST surface — the richest of the test-app
 * controllers. Every shape of grant ({@code to(User, id)}, typed
 * sub-relation for groups, typed sub-permission for department
 * {@code all_members}, wildcard with a caveat) and every shape of
 * read (single check, whole-enum matrix, {@code who}, {@code findBy},
 * cross-resource batch) has a matching endpoint here.
 */
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentService docs;

    public DocumentController(DocumentService docs) {
        this.docs = docs;
    }

    // ── Structure ─────────────────────────────────────────────────────

    @PutMapping("/{docId}/folder/{folderId}")
    public ResponseEntity<Void> linkFolder(@PathVariable String docId,
                                             @PathVariable String folderId) {
        docs.linkToFolder(docId, folderId);
        return ResponseEntity.noContent().build();
    }

    // ── Grants ────────────────────────────────────────────────────────

    @PostMapping("/{docId}/owners")
    public ResponseEntity<WriteResponse> addOwner(@PathVariable String docId,
                                                    @RequestBody UserRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new WriteResponse(docs.addOwner(docId, body.userId())));
    }

    @PostMapping("/{docId}/editors")
    public ResponseEntity<WriteResponse> addEditor(@PathVariable String docId,
                                                     @RequestBody UserRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new WriteResponse(docs.addEditor(docId, body.userId())));
    }

    @DeleteMapping("/{docId}/editors/{userId}")
    public ResponseEntity<Void> removeEditor(@PathVariable String docId,
                                               @PathVariable String userId) {
        docs.removeEditor(docId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{docId}/viewers")
    public ResponseEntity<WriteResponse> addViewer(@PathVariable String docId,
                                                     @RequestBody UserRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new WriteResponse(docs.addViewer(docId, body.userId())));
    }

    @PostMapping("/{docId}/viewers/group/{groupId}")
    public ResponseEntity<WriteResponse> shareWithGroup(@PathVariable String docId,
                                                          @PathVariable String groupId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new WriteResponse(docs.shareWithGroup(docId, groupId)));
    }

    @PostMapping("/{docId}/viewers/department/{deptId}")
    public ResponseEntity<WriteResponse> shareWithDepartment(@PathVariable String docId,
                                                               @PathVariable String deptId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new WriteResponse(docs.shareWithDepartment(docId, deptId)));
    }

    @PostMapping("/{docId}/viewers/public")
    public ResponseEntity<WriteResponse> shareWithPublic(@PathVariable String docId,
                                                           @RequestBody PublicShareRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new WriteResponse(docs.shareWithPublic(docId, body.allowedCidrs())));
    }

    // ── Checks ────────────────────────────────────────────────────────

    @GetMapping("/{docId}/can/{permission}")
    public CheckResponse check(@PathVariable String docId,
                                @PathVariable String permission,
                                @RequestParam String userId,
                                @RequestParam(required = false) @Nullable String clientIp) {
        return new CheckResponse(docs.can(docId, permission, userId, clientIp));
    }

    @GetMapping("/{docId}/permissions")
    public PermissionMatrixResponse permissions(@PathVariable String docId,
                                                  @RequestParam String userId) {
        return new PermissionMatrixResponse(docs.permissionsFor(docId, userId));
    }

    // ── Reverse lookups ───────────────────────────────────────────────

    @GetMapping("/{docId}/editors")
    public IdsResponse editors(@PathVariable String docId) {
        return new IdsResponse(docs.editors(docId));
    }

    @GetMapping("/accessible")
    public IdsResponse visible(@RequestParam String userId,
                                @RequestParam(defaultValue = "100") int limit) {
        return new IdsResponse(docs.visibleTo(userId, limit));
    }

    // ── Batch check ───────────────────────────────────────────────────

    @PostMapping("/batch-check")
    public BatchCheckResponse batchCheck(@RequestBody BatchCheckRequest body) {
        return docs.batchCheck(body);
    }
}
