package com.authx.sdk;

import com.authx.sdk.action.WhoBuilder;
import com.authx.sdk.model.CheckMatrix;
import com.authx.sdk.model.CheckRequest;
import com.authx.sdk.model.CheckResult;
import com.authx.sdk.model.Consistency;
import com.authx.sdk.model.Permission;
import com.authx.sdk.model.Relation;
import com.authx.sdk.model.ResourceRef;
import com.authx.sdk.model.SubjectRef;
import com.authx.sdk.transport.SdkTransport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Type-safe resource factory with chainable grant / revoke / check / who
 * / find operations. Relations and permissions are expressed as generated
 * enums so typos turn into compile errors, and every subject-type method
 * runtime-validates against the loaded SpiceDB schema so incompatible
 * (relation, subject) pairs fail locally with a clear message.
 *
 * <pre>
 * // ── Grant / revoke (per-relation enum, subject-type validated at runtime)
 * doc.select("doc-1").grant(Document.Rel.EDITOR).toUser("bob");
 * doc.select("doc-1").grant(Document.Rel.FOLDER).to(SubjectRef.of("folder","f-1",null));
 * doc.select("doc-1").revoke(Document.Rel.EDITOR).fromUser("bob");
 *
 * // ── Single-check: boolean
 * boolean canView = doc.select("doc-1").check(Document.Perm.VIEW).by("alice");
 *
 * // ── Detailed check (caveats / conditional permissions)
 * CheckResult r = doc.select("doc-1").check(Document.Perm.EDIT)
 *         .withContext(Map.of("user_ip", "10.0.0.5"))
 *         .detailedBy("alice");
 * if (r.permissionship() == Permissionship.CONDITIONAL_PERMISSION) {
 *     log.warn("need more context: {}", r.missingContext());
 * }
 *
 * // ── Matrix check (N × M × K) → CheckMatrix
 * CheckMatrix m = doc.select("doc-1", "doc-2")
 *         .check(Document.Perm.VIEW, Document.Perm.EDIT)
 *         .by("alice", "bob");
 * m.allowed("doc-1", "view", "alice");
 *
 * // ── Typed lookupSubjects ("who can view doc-1?")
 * List&lt;String&gt; viewers = doc.select("doc-1").who(Document.Perm.VIEW).asUserIds();
 *
 * // ── Typed lookupResources ("which docs can alice view?")
 * List&lt;String&gt; myDocs = doc.findBy(SubjectRef.user("alice")).can(Document.Perm.VIEW);
 * </pre>
 */
public class TypedResourceFactory<R extends Relation.Named, P extends Permission.Named>
        extends ResourceFactory {

    protected TypedResourceFactory() {
        super();
    }

    protected void init(String resourceType, AuthxClient client) {
        init(resourceType, client.transport(), client.defaultSubjectType(),
                client.asyncExecutor(), client.internalSchemaCache());
    }

    // ────────────────────────────────────────────────────────────────
    //  Handle entry points
    // ────────────────────────────────────────────────────────────────

    public TypedHandle<R, P> select(String... ids) {
        if (ids == null || ids.length == 0) {
            throw new IllegalArgumentException("select() requires at least one id");
        }
        return new TypedHandle<>(this, ids);
    }

    public TypedHandle<R, P> select(Collection<String> ids) {
        return select(ids.toArray(String[]::new));
    }

    // ────────────────────────────────────────────────────────────────
    //  Reverse lookup (lookupResources) — "what can this subject access?"
    // ────────────────────────────────────────────────────────────────

    /**
     * Start a reverse-lookup query: "which resources of this type can
     * {@code subject} do things to?". Terminate the chain with
     * {@link TypedFinder#can(Permission.Named)} to pick the permission and
     * fetch ids.
     */
    public TypedFinder<P> findBy(SubjectRef subject) {
        return new TypedFinder<>(this, subject);
    }

    // ════════════════════════════════════════════════════════════════
    //  Handle — grant / revoke / check / who, bound to one or more ids
    // ════════════════════════════════════════════════════════════════

    public static class TypedHandle<R extends Relation.Named, P extends Permission.Named> {
        protected final ResourceFactory factory;
        protected final String[] ids;

        public TypedHandle(ResourceFactory factory, String[] ids) {
            this.factory = factory;
            this.ids = ids;
        }

        @SuppressWarnings("unchecked")
        public TypedGrantAction<R> grant(R... relations) {
            return new TypedGrantAction<>(factory, ids, relations);
        }

        @SuppressWarnings("unchecked")
        public TypedRevokeAction<R> revoke(R... relations) {
            return new TypedRevokeAction<>(factory, ids, relations);
        }

        /**
         * Single-permission check — the 99% path. Terminate with
         * {@link TypedCheckAction#by(String)} for a simple boolean, or
         * {@link TypedCheckAction#detailedBy(String)} for a
         * {@link CheckResult} carrying caveat / CONDITIONAL information.
         */
        public TypedCheckAction check(P permission) {
            return new TypedCheckAction(factory, ids, new String[]{permission.permissionName()});
        }

        /**
         * Multi-permission check — returns a plan that can resolve against
         * one or more subjects and produce a {@link CheckMatrix}. Use this
         * for batch UIs (permission sidebar, list views, dashboards).
         */
        @SafeVarargs
        public final TypedCheckAction check(P... permissions) {
            String[] perms = new String[permissions.length];
            for (int i = 0; i < permissions.length; i++) perms[i] = permissions[i].permissionName();
            return new TypedCheckAction(factory, ids, perms);
        }

        /**
         * Typed lookupSubjects — "who has this permission on the selected
         * resource?". Requires exactly one resource id (SpiceDB's
         * LookupSubjects is a per-resource RPC). The returned
         * {@link TypedWhoQuery} lets you set a limit and resolve to a
         * {@code List<String>} of subject ids.
         */
        public TypedWhoQuery who(P permission) {
            if (ids.length != 1) {
                throw new IllegalStateException(
                        "who(Perm) requires exactly one selected resource id; got " + ids.length);
            }
            return new TypedWhoQuery(factory, ids[0], permission.permissionName());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Check action — boolean / CheckResult / matrix
    // ════════════════════════════════════════════════════════════════

    public static class TypedCheckAction {
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
         * meaningful if the schema uses caveats; otherwise ignored by
         * SpiceDB.
         */
        public TypedCheckAction withContext(Map<String, Object> context) {
            this.context = context;
            return this;
        }

        // ─── Simple path: single (id × perm) → boolean ───

        /**
         * Default single-check terminator. Requires exactly one resource id
         * and one permission. Returns a plain boolean — for the caveat path
         * (CONDITIONAL_PERMISSION) use {@link #detailedBy(String)}.
         */
        public boolean by(String userId) {
            if (ids.length != 1 || permissions.length != 1) {
                throw new IllegalStateException(
                        "by(String) requires a single resource id and a single permission; "
                                + "use by(String...) for matrix checks ("
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

        // ─── Caveat path: full CheckResult ───

        /**
         * Detailed single-check terminator that returns the full
         * {@link CheckResult}. Use this when your schema has caveats and
         * you need to distinguish HAS_PERMISSION / NO_PERMISSION /
         * CONDITIONAL_PERMISSION, or when you need the zedToken for
         * consistency chaining.
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

        // ─── Matrix path: N × M × K → CheckMatrix ───

        /**
         * Matrix check. Every (resource id × permission × subject id) cell
         * is resolved and collected into a {@link CheckMatrix} with O(1)
         * point-lookup plus whole-axis query helpers.
         *
         * <p>Backed by SpiceDB's {@code CheckBulkPermissions} RPC in a
         * single round-trip (see F14-11). For pre-F14-11 the implementation
         * loops with individual checks — still correct, just slower.
         */
        public CheckMatrix by(String... userIds) {
            if (userIds == null || userIds.length == 0) {
                throw new IllegalArgumentException("by(...) requires at least one subject id");
            }
            String subjectType = factory.defaultSubjectType();
            SubjectRef[] subs = new SubjectRef[userIds.length];
            for (int i = 0; i < userIds.length; i++) {
                subs[i] = SubjectRef.of(subjectType, userIds[i], null);
            }
            return by(subs);
        }

        public CheckMatrix by(Collection<String> userIds) {
            return by(userIds.toArray(String[]::new));
        }

        /**
         * Matrix check against explicit {@link SubjectRef}s for mixed subject
         * types. Backed by SpiceDB's {@code CheckBulkPermissions} RPC — the
         * entire (ids × permissions × subjects) cartesian product is sent
         * in a single round trip (auto-batched above MAX_BATCH_SIZE), so
         * this is O(1) network calls regardless of matrix size.
         */
        public CheckMatrix by(SubjectRef... subjects) {
            if (subjects == null || subjects.length == 0) {
                throw new IllegalArgumentException("by(...) requires at least one subject");
            }
            // Single cell — cheaper to use the plain CheckPermission RPC
            // than to set up a bulk request for one item. Matches the
            // server-side hot path for the "only one check" case.
            if (ids.length == 1 && permissions.length == 1 && subjects.length == 1) {
                CheckResult r = runSingle(ids[0], permissions[0], subjects[0]);
                return CheckMatrix.builder()
                        .add(ids[0], permissions[0], subjects[0].id(), r.hasPermission())
                        .build();
            }

            // Build the full cartesian product as BulkCheckItems and send it
            // in one RPC (the transport chain handles batching for very
            // large matrices). Results come back in the same order as items.
            String resourceType = factory.resourceType();
            List<SdkTransport.BulkCheckItem> items =
                    new ArrayList<>(ids.length * permissions.length * subjects.length);
            // Parallel arrays to rebuild the CheckMatrix with the right keys.
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

    // ════════════════════════════════════════════════════════════════
    //  Who query — typed lookupSubjects
    // ════════════════════════════════════════════════════════════════

    public static class TypedWhoQuery {
        private final ResourceFactory factory;
        private final String resourceId;
        private final String permission;
        private int limit = 0;

        public TypedWhoQuery(ResourceFactory factory, String resourceId, String permission) {
            this.factory = factory;
            this.resourceId = resourceId;
            this.permission = permission;
        }

        /** Limit the number of subjects returned. 0 = no limit (default). */
        public TypedWhoQuery limit(int n) {
            this.limit = n;
            return this;
        }

        /**
         * Fetch the subject ids that have the permission, using the client's
         * default subject type (usually {@code "user"}). For non-user
         * subject types, drop to the raw API via
         * {@code factory.resource(id).who().withPermission(perm).fetch()}.
         */
        public java.util.List<String> asUserIds() {
            return new WhoBuilder(factory.resourceType(), resourceId, factory.transport(),
                    factory.defaultSubjectType(), factory.asyncExecutor())
                    .withPermission(permission)
                    .limit(limit)
                    .fetch();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Finder — typed lookupResources
    // ════════════════════════════════════════════════════════════════

    public static class TypedFinder<P extends Permission.Named> {
        private final ResourceFactory factory;
        private final SubjectRef subject;
        private int limit = 0;

        public TypedFinder(ResourceFactory factory, SubjectRef subject) {
            this.factory = factory;
            this.subject = subject;
        }

        /** Limit the number of resources returned. 0 = no limit. */
        public TypedFinder<P> limit(int n) {
            this.limit = n;
            return this;
        }

        /**
         * Fetch the ids of resources of the bound type that the subject can
         * perform {@code permission} on (reverse lookup via
         * {@code LookupResources}).
         */
        public java.util.List<String> can(P permission) {
            return factory.lookup()
                    .withPermission(permission.permissionName())
                    .by(subject)
                    .limit(limit)
                    .fetch();
        }
    }
}
