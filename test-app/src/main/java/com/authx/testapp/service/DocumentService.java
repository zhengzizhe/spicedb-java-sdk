package com.authx.testapp.service;

import static com.authx.testapp.schema.Schema.Department;
import static com.authx.testapp.schema.Schema.Document;
import static com.authx.testapp.schema.Schema.Folder;
import static com.authx.testapp.schema.Schema.Group;
import static com.authx.testapp.schema.Schema.User;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.SubjectRef;
import com.authx.testapp.dto.ApiDtos.BatchCheckRequest;
import com.authx.testapp.dto.ApiDtos.BatchCheckResponse;
import com.authx.testapp.dto.ApiDtos.BatchCheckResponse.BatchCheckOutcome;
import com.authx.testapp.schema.IpAllowlist;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Document permission business logic. Wraps every Document-scoped call
 * on {@link AuthxClient} — grants, caveat-gated public sharing, checks
 * (single + whole-enum matrix), reverse lookups, and cross-resource
 * batch checks. Controllers stay tiny and delegate through here.
 */
@Service
public class DocumentService {

    private final AuthxClient auth;

    public DocumentService(AuthxClient auth) {
        this.auth = auth;
    }

    // ── Structure ─────────────────────────────────────────────────────

    /** Place a document inside a folder so it inherits folder permissions. */
    public void linkToFolder(String docId, String folderId) {
        auth.on(Document).select(docId)
                .grant(Document.Rel.FOLDER)
                .to(Folder, folderId);
    }

    // ── Direct-user grants ────────────────────────────────────────────

    public int addOwner(String docId, String userId) {
        return auth.on(Document).select(docId)
                .grant(Document.Rel.OWNER)
                .to(User, userId)
                .result().count();
    }

    public int addEditor(String docId, String userId) {
        return auth.on(Document).select(docId)
                .grant(Document.Rel.EDITOR)
                .to(User, userId)
                .result().count();
    }

    public int addViewer(String docId, String userId) {
        return auth.on(Document).select(docId)
                .grant(Document.Rel.VIEWER)
                .to(User, userId)
                .result().count();
    }

    public void removeEditor(String docId, String userId) {
        auth.on(Document).select(docId)
                .revoke(Document.Rel.EDITOR)
                .from(User, userId);
    }

    // ── Subject-set grants (group / department) ───────────────────────

    /** Share with every member of {@code groupId} via a single tuple. */
    public int shareWithGroup(String docId, String groupId) {
        return auth.on(Document).select(docId)
                .grant(Document.Rel.VIEWER)
                .to(Group, groupId, Group.Rel.MEMBER)           // typed sub-relation
                .result().count();
    }

    /** Share with the entire {@code deptId} sub-tree via {@code all_members}. */
    public int shareWithDepartment(String docId, String deptId) {
        return auth.on(Document).select(docId)
                .grant(Document.Rel.VIEWER)
                .to(Department, deptId, Department.Perm.ALL_MEMBERS)  // typed sub-permission
                .result().count();
    }

    // ── Public share with IP allow-list caveat ────────────────────────

    /**
     * Public share with {@code user:*} gated on a CIDR allow-list. An
     * empty or null list gates the grant only on CIDR "" (i.e. matches
     * nothing) — callers should pre-validate; an empty list stored here
     * is still a legitimate "no one passes the caveat" state.
     */
    public int shareWithPublic(String docId, List<String> allowedCidrs) {
        return auth.on(Document).select(docId)
                .grant(Document.Rel.VIEWER)
                .withCaveat(IpAllowlist.ref(IpAllowlist.CIDRS, allowedCidrs))
                .toWildcard(User)
                .result().count();
    }

    // ── Checks ────────────────────────────────────────────────────────

    /**
     * Single-cell check. If {@code clientIp} is non-null the caveat
     * context is populated — the ip_allowlist caveat consults it at
     * evaluation time.
     */
    public boolean can(String docId, String permission, String userId, @Nullable String clientIp) {
        var chain = auth.on(Document).select(docId)
                .check(permissionOf(permission));
        if (clientIp != null) {
            chain.withContext(IpAllowlist.CLIENT_IP, clientIp);
        }
        return chain.by(User, userId);
    }

    /**
     * Whole-enum permission matrix for a single (doc, user) pair. Runs
     * one {@code CheckBulkPermissions} RPC behind the scenes.
     */
    public Map<String, Boolean> permissionsFor(String docId, String userId) {
        EnumMap<com.authx.testapp.schema.Document.Perm, Boolean> raw =
                auth.on(Document).select(docId)
                        .checkAll(Document.Perm)
                        .by(User, userId);
        var out = new LinkedHashMap<String, Boolean>(raw.size());
        raw.forEach((k, v) -> out.put(k.permissionName(), v));
        return out;
    }

    // ── Reverse lookups ───────────────────────────────────────────────

    /** "Who has EDIT on this document?" (lookupSubjects) */
    public List<String> editors(String docId) {
        return auth.on(Document).select(docId)
                .who(User, Document.Perm.EDIT)
                .fetchIds();
    }

    /** "Which documents can this user view?" (lookupResources) */
    public List<String> visibleTo(String userId, int limit) {
        return auth.on(Document)
                .findBy(User, userId)
                .limit(limit)
                .can(Document.Perm.VIEW);
    }

    // ── Cross-resource batch check ────────────────────────────────────

    /**
     * Single-RPC batched check for a UI dashboard — e.g. "for the doc
     * list this user is seeing, mark which ones are editable".
     */
    public BatchCheckResponse batchCheck(BatchCheckRequest req) {
        SubjectRef subject = SubjectRef.of(User.name(), req.userId());
        var batch = auth.batchCheck();
        for (var item : req.items()) {
            batch.add(Document, item.docId(), permissionOf(item.permission()), subject);
        }
        CheckMatrix matrix = batch.fetch();
        List<BatchCheckOutcome> out = new ArrayList<>(req.items().size());
        for (var item : req.items()) {
            String compositeId = Document.name() + ":" + item.docId();
            boolean allowed = matrix.allowed(compositeId, item.permission(), subject.toRefString());
            out.add(new BatchCheckOutcome(item.docId(), item.permission(), allowed));
        }
        return new BatchCheckResponse(out);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Map a REST-string permission name to its typed enum. Keeps a
     * single bounded allow-list so a bogus path doesn't blow up inside
     * the SDK — returns a 400 via the controller's exception handler.
     */
    private static com.authx.testapp.schema.Document.Perm permissionOf(String name) {
        try {
            return com.authx.testapp.schema.Document.Perm.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown document permission: '" + name + "'. Allowed: "
                            + java.util.Arrays.toString(
                                    com.authx.testapp.schema.Document.Perm.values()));
        }
    }
}
