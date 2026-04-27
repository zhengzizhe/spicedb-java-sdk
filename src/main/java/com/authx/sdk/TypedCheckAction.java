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

    /** Caveat context from alternating key-value pairs, e.g. {@code withContext(IpAllowlist.CLIENT_IP, "10.0.0.5")}. */
    public TypedCheckAction withContext(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must have even length");
        }
        java.util.LinkedHashMap<java.lang.String,java.lang.Object> map = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (!(keyValues[i] instanceof String key)) {
                throw new IllegalArgumentException("Key at index " + i + " must be a String");
            }
            map.put(key, keyValues[i + 1]);
        }
        this.context = map;
        return this;
    }

    /** Alias for {@link #withContext(Map)} — reads as "check access given ...". */
    public TypedCheckAction given(Map<String, Object> context) { return withContext(context); }

    /** Alias for {@link #withContext(Object...)} — reads as "check access given CLIENT_IP, ip". */
    public TypedCheckAction given(Object... keyValues) { return withContext(keyValues); }

    // ────────────────────────────────────────────────────────────────
    //  Simple path: single (id × perm) → boolean
    // ────────────────────────────────────────────────────────────────

    /**
     * Single-check terminator against the given {@link SubjectRef subject}.
     * Requires exactly one resource id and one permission.
     */
    public boolean by(SubjectRef subject) {
        if (ids.length != 1 || permissions.length != 1) {
            throw new IllegalStateException(
                    "by(SubjectRef) requires a single resource id and a single permission; "
                            + "use byAll(...) for matrix checks ("
                            + ids.length + " ids × " + permissions.length + " perms)");
        }
        return runSingle(ids[0], permissions[0], subject).hasPermission();
    }

    /** Canonical-string form of {@link #by(SubjectRef)} — {@code "user:alice"} etc. */
    public boolean by(String subjectRef) {
        return by(SubjectRef.parse(subjectRef));
    }

    /**
     * Typed subject form: {@code check(Perm.VIEW).by(User, "alice")}.
     * Constructs the canonical {@code "user:alice"} and routes through
     * {@link #by(String)} for the single-cell check.
     */
    public <R2 extends Enum<R2> & com.authx.sdk.model.Relation.Named,
            P2 extends Enum<P2> & com.authx.sdk.model.Permission.Named>
    boolean by(com.authx.sdk.ResourceType<R2, P2> subjectType, String id) {
        return by(subjectType.name() + ":" + id);
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
    public CheckResult detailedBy(SubjectRef subject) {
        if (ids.length != 1 || permissions.length != 1) {
            throw new IllegalStateException(
                    "detailedBy(SubjectRef) requires a single resource id and a single permission");
        }
        return runSingle(ids[0], permissions[0], subject);
    }

    /** Canonical-string form of {@link #detailedBy(SubjectRef)}. */
    public CheckResult detailedBy(String subjectRef) {
        return detailedBy(SubjectRef.parse(subjectRef));
    }

    /**
     * Typed subject form: {@code check(Perm.EDIT).detailedBy(User, "alice")}.
     * Constructs the canonical subject ref and routes through
     * {@link #detailedBy(String)} to return the full {@link CheckResult}.
     */
    public <R2 extends Enum<R2> & com.authx.sdk.model.Relation.Named,
            P2 extends Enum<P2> & com.authx.sdk.model.Permission.Named>
    CheckResult detailedBy(com.authx.sdk.ResourceType<R2, P2> subjectType, String id) {
        return detailedBy(subjectType.name() + ":" + id);
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
    /** Matrix check against one or more canonical subject strings. */
    public CheckMatrix byAll(String... subjectRefs) {
        if (subjectRefs == null || subjectRefs.length == 0) {
            throw new IllegalArgumentException("byAll(...) requires at least one subject");
        }
        SubjectRef[] subs = new SubjectRef[subjectRefs.length];
        for (int i = 0; i < subjectRefs.length; i++) {
            subs[i] = SubjectRef.parse(subjectRefs[i]);
        }
        return byAll(subs);
    }

    /** Collection overload — canonical subject strings. */
    public CheckMatrix byAll(Collection<String> subjectRefs) {
        return byAll(subjectRefs.toArray(String[]::new));
    }

    /** {@link Iterable} overload of {@link #byAll(String...)}. */
    public CheckMatrix byAll(Iterable<String> subjectRefs) {
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String ref : subjectRefs) list.add(ref);
        return byAll(list.toArray(String[]::new));
    }

    /**
     * Typed batch form: same subject type, many ids. Each id is wrapped
     * as {@code "type:id"} before the matrix is built.
     */
    public <R2 extends Enum<R2> & com.authx.sdk.model.Relation.Named,
            P2 extends Enum<P2> & com.authx.sdk.model.Permission.Named>
    CheckMatrix byAll(com.authx.sdk.ResourceType<R2, P2> subjectType, Iterable<String> ids) {
        java.util.List<String> refs = new java.util.ArrayList<>();
        for (String id : ids) refs.add(subjectType.name() + ":" + id);
        return byAll(refs.toArray(String[]::new));
    }

    /** Matrix check against explicit {@link SubjectRef}s. */
    public CheckMatrix byAll(SubjectRef... subjects) {
        if (subjects == null || subjects.length == 0) {
            throw new IllegalArgumentException("byAll(...) requires at least one subject");
        }
        if (ids.length == 1 && permissions.length == 1 && subjects.length == 1) {
            // Hot path: single cell — cheaper to hit plain CheckPermission.
            CheckResult r = runSingle(ids[0], permissions[0], subjects[0]);
            return CheckMatrix.builder()
                    .add(ids[0], permissions[0], subjects[0].toRefString(), r.hasPermission())
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
                    cellSubjects[k] = sub.toRefString();
                    k++;
                }
            }
        }
        List<CheckResult> results = factory.transport().checkBulkMulti(items, consistency);
        com.authx.sdk.model.CheckMatrix.Builder b = CheckMatrix.builder();
        for (int i = 0; i < results.size(); i++) {
            b.add(cellIds[i], cellPerms[i], cellSubjects[i], results.get(i).hasPermission());
        }
        return b.build();
    }

    private CheckResult runSingle(String id, String permission, SubjectRef subject) {
        com.authx.sdk.model.CheckRequest request = new CheckRequest(
                ResourceRef.of(factory.resourceType(), id),
                Permission.of(permission),
                subject,
                consistency,
                context);
        return factory.transport().check(request);
    }
}
