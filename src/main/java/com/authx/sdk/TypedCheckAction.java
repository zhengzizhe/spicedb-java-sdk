package com.authx.sdk;

import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Typed check terminator — resolves a permission check into a boolean
 * (single cell), a full {@link CheckResult} (caveat-aware), or a
 * {@link CheckMatrix} (N × M × K matrix via CheckBulkPermissions).
 *
 * <p>Construct via {@link TypedHandle#check(com.authx.sdk.model.Permission.Named)}.
 *
 * <pre>
 * // boolean
 * boolean b = Document.select(client, "doc-1").check(Document.Perm.VIEW).by("alice");
 *
 * // caveat-aware CheckResult
 * CheckResult r = Document.select(client, "doc-1")
 *         .check(Document.Perm.EDIT)
 *         .withContext(Map.of("user_ip", "10.0.0.5"))
 *         .detailedBy("alice");
 *
 * // matrix via CheckBulkPermissions
 * CheckMatrix m = Document.select(client, "doc-1", "doc-2")
 *         .check(Document.Perm.VIEW, Document.Perm.EDIT)
 *         .byAll("alice", "bob");
 * </pre>
 */
public class TypedCheckAction {

    private final ResourceFactory factory;
    private final String[] ids;
    private final String[] permissions;
    // Default to minimizeLatency; PolicyAwareConsistencyTransport in the
    // chain will upgrade this per-resource-policy when the user hasn't
    // explicitly overridden it. Never null — CheckRequest rejects null.
    private Consistency consistency = Consistency.minimizeLatency();
    private Map<String, Object> context;

    public TypedCheckAction(ResourceFactory factory, String[] ids, String[] permissions) {
        this.factory = factory;
        this.ids = ids;
        this.permissions = permissions;
    }

    /** Override consistency for this check. */
    public TypedCheckAction withConsistency(Consistency consistency) {
        this.consistency = consistency;
        return this;
    }

    /**
     * Provide caveat context (conditional permission variables). Only
     * meaningful if the schema uses caveats; otherwise ignored by SpiceDB.
     */
    public TypedCheckAction withContext(Map<String, Object> context) {
        this.context = context;
        return this;
    }

    // ────────────────────────────────────────────────────────────────
    //  Simple path: single (id × perm) → boolean
    // ────────────────────────────────────────────────────────────────

    /**
     * Default single-check terminator. Requires exactly one resource id
     * and one permission. Returns a plain boolean — for the caveat path
     * (CONDITIONAL_PERMISSION) use {@link #detailedBy(String)}.
     */
    public boolean by(String userId) {
        if (ids.length != 1 || permissions.length != 1) {
            throw new IllegalStateException(
                    "by(String) requires a single resource id and a single permission; "
                            + "use byAll(String...) for matrix checks ("
                            + ids.length + " ids × " + permissions.length + " perms)");
        }
        CheckResult r = runSingle(ids[0], permissions[0],
                SubjectRef.of(factory.defaultSubjectType(), userId, null));
        return r.hasPermission();
    }

    /** Single-check against an explicit subject (non-default type). */
    public boolean by(SubjectRef subject) {
        if (ids.length != 1 || permissions.length != 1) {
            throw new IllegalStateException(
                    "by(SubjectRef) requires a single resource id and a single permission");
        }
        return runSingle(ids[0], permissions[0], subject).hasPermission();
    }

    // ────────────────────────────────────────────────────────────────
    //  Caveat path: full CheckResult
    // ────────────────────────────────────────────────────────────────

    /**
     * Detailed single-check terminator that returns the full
     * {@link CheckResult}. Use when the schema has caveats and you need to
     * distinguish HAS_PERMISSION / NO_PERMISSION / CONDITIONAL_PERMISSION,
     * or when you need the zedToken for consistency chaining.
     */
    public CheckResult detailedBy(String userId) {
        if (ids.length != 1 || permissions.length != 1) {
            throw new IllegalStateException(
                    "detailedBy(String) requires a single resource id and a single permission");
        }
        return runSingle(ids[0], permissions[0],
                SubjectRef.of(factory.defaultSubjectType(), userId, null));
    }

    public CheckResult detailedBy(SubjectRef subject) {
        if (ids.length != 1 || permissions.length != 1) {
            throw new IllegalStateException(
                    "detailedBy(SubjectRef) requires a single resource id and a single permission");
        }
        return runSingle(ids[0], permissions[0], subject);
    }

    // ────────────────────────────────────────────────────────────────
    //  Matrix path: N × M × K → CheckMatrix
    //
    //  Named byAll(...) rather than overloaded on by(...) because Java
    //  resolves by(String) (single-arg boolean) over by(String...) when
    //  given a single literal, so `.check(P1, P2, P3).by("alice")` would
    //  silently pick the boolean path and throw at runtime. Forcing
    //  callers to type byAll makes the intent explicit.
    // ────────────────────────────────────────────────────────────────

    /**
     * Matrix check terminator. Every (resource id × permission × subject id)
     * cell in the cartesian product is resolved and collected into a
     * {@link CheckMatrix}. Backed by {@code CheckBulkPermissions} in one
     * round-trip (auto-batched by the transport above MAX_BATCH_SIZE).
     */
    public CheckMatrix byAll(String... userIds) {
        if (userIds == null || userIds.length == 0) {
            throw new IllegalArgumentException("byAll(...) requires at least one subject id");
        }
        String subjectType = factory.defaultSubjectType();
        SubjectRef[] subs = new SubjectRef[userIds.length];
        for (int i = 0; i < userIds.length; i++) {
            subs[i] = SubjectRef.of(subjectType, userIds[i], null);
        }
        return byAll(subs);
    }

    public CheckMatrix byAll(Collection<String> userIds) {
        return byAll(userIds.toArray(String[]::new));
    }

    /** Matrix check against explicit {@link SubjectRef}s for mixed subject types. */
    public CheckMatrix byAll(SubjectRef... subjects) {
        if (subjects == null || subjects.length == 0) {
            throw new IllegalArgumentException("byAll(...) requires at least one subject");
        }
        if (ids.length == 1 && permissions.length == 1 && subjects.length == 1) {
            // Hot path: single cell — cheaper to hit plain CheckPermission.
            CheckResult r = runSingle(ids[0], permissions[0], subjects[0]);
            return CheckMatrix.builder()
                    .add(ids[0], permissions[0], subjects[0].id(), r.hasPermission())
                    .build();
        }

        String resourceType = factory.resourceType();
        List<SdkTransport.BulkCheckItem> items =
                new ArrayList<>(ids.length * permissions.length * subjects.length);
        String[] cellIds = new String[ids.length * permissions.length * subjects.length];
        String[] cellPerms = new String[cellIds.length];
        String[] cellSubjects = new String[cellIds.length];
        int k = 0;
        for (String id : ids) {
            for (String perm : permissions) {
                for (SubjectRef sub : subjects) {
                    items.add(new SdkTransport.BulkCheckItem(
                            ResourceRef.of(resourceType, id),
                            Permission.of(perm),
                            sub));
                    cellIds[k] = id;
                    cellPerms[k] = perm;
                    cellSubjects[k] = sub.id();
                    k++;
                }
            }
        }
        List<CheckResult> results = factory.transport().checkBulkMulti(items, consistency);
        var b = CheckMatrix.builder();
        for (int i = 0; i < results.size(); i++) {
            b.add(cellIds[i], cellPerms[i], cellSubjects[i], results.get(i).hasPermission());
        }
        return b.build();
    }

    private CheckResult runSingle(String id, String permission, SubjectRef subject) {
        var request = new CheckRequest(
                ResourceRef.of(factory.resourceType(), id),
                Permission.of(permission),
                subject,
                consistency,
                context);
        return factory.transport().check(request);
    }
}
